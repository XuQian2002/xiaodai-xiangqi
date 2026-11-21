package daidaixiangqi.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import daidaixiangqi.app.ai.ChessEngine
import daidaixiangqi.app.audio.GameAudioManager
import daidaixiangqi.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GameController(
    private val context: Context,
    aiDifficulty: AIDifficulty = AIDifficulty.PROFESSIONAL,
    private val audioManager: GameAudioManager
) {
    private val difficulty: AIDifficulty = aiDifficulty

    private var gameGeneration = 0

    private val engineMutex = Mutex()

    enum class AIDifficulty(
        val engineDepth: Int,
        val minMoveMs: Long,
        val maxMoveMs: Long
    ) {
        INTERMEDIATE(0, 1000, 2000),
        ADVANCED(0, 2000, 2500),
        PROFESSIONAL(0, 3000, 3500),
        MASTER(0, 3500, 4000),
        GRANDMASTER(0, 4000, 4500)
    }

    enum class GameMode {
        PLAYER_VS_PLAYER,
        PLAYER_VS_AI
    }

    private var board = Board.createInitialBoard()
    private var initialBoard = Board.createInitialBoard()
    private var gameMode = GameMode.PLAYER_VS_AI

    private var aiColor = PieceColor.BLACK

    private var gameOver = false
    private var chessEngine: ChessEngine? = null

    private var moveHistory = mutableListOf<Move>()
    private var positionHashes = mutableListOf<Long>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val clock = GameClock(SystemClock::elapsedRealtime)

    private var firstMovePlayed = false
    private var redScore = 0
    private var blackScore = 0
    private val redCapturedPieces = mutableListOf<Piece>()
    private val blackCapturedPieces = mutableListOf<Piece>()

    var onBoardUpdated: ((Board) -> Unit)? = null
    var onGameOver: ((GameResult) -> Unit)? = null
    var onAIThinking: ((Boolean) -> Unit)? = null
    var onMoveCompleted: ((Move, Board) -> Unit)? = null
    var onStatsUpdated: ((GameStats) -> Unit)? = null
    var onMoveAnimationRequested: ((Move, Board) -> Unit)? = null

    data class GameStats(
        val redScore: Int,
        val blackScore: Int,
        val moveNumber: Int,
        val redCapturedPieces: List<Piece> = emptyList(),
        val blackCapturedPieces: List<Piece> = emptyList()
    )

    sealed class GameResult {

        abstract val winner: PieceColor?
        data class Checkmate(override val winner: PieceColor) : GameResult()

        data class Stalemate(override val winner: PieceColor) : GameResult()

        data class PerpetualCheck(override val winner: PieceColor) : GameResult()

        data class PerpetualChase(override val winner: PieceColor) : GameResult()
        object RepetitionDraw : GameResult() {
            override val winner: PieceColor? get() = null
        }
    }

    fun setGameMode(mode: GameMode, aiColor: PieceColor = PieceColor.BLACK) {
        this.gameMode = mode
        this.aiColor = aiColor
    }

    fun startNewGame() {
        gameGeneration++
        gameOver = false
        board = Board.createInitialBoard()
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        firstMovePlayed = false
        clock.reset()
        syncClock()
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        onBoardUpdated?.invoke(board)
        updateStats()
        prewarmEngine()

        if (shouldAIMove()) {
            makeAIMove()
        }
    }

    fun getCurrentBoard(): Board = board

    fun getInitialBoard(): Board = initialBoard

    fun getMoveHistory(): List<Move> = moveHistory.toList()

    fun makePlayerMove(move: Move): Boolean {

        val legalMoves = board.getAllLegalMoves()
        if (move !in legalMoves) {
            return false
        }

        playMove(move)

        if (checkGameOver()) {
            return true
        }

        if (shouldAIMove()) {
            makeAIMove()
        }

        return true
    }

    private fun playMove(move: Move) {

        val preBoard = board.copy()

        if (move.capturedPiece != null) {
            val captureValue = move.capturedPiece.type.baseValue
            if (move.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(move.capturedPiece)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(move.capturedPiece)
            }
        }

        onMoveAnimationRequested?.invoke(move, preBoard)

        board.makeMoveInPlace(move)
        moveHistory.add(move)
        positionHashes.add(board.getPositionHash())
        firstMovePlayed = true
        syncClock()

        // 播报最先入队，后台与后续棋盘重绘/统计刷新并行合成，避免 UI 重活拖住出声。
        onMoveCompleted?.invoke(move, preBoard)

        if (move.capturedPiece != null) audioManager.playCaptureSound() else audioManager.playMoveSound()

        onBoardUpdated?.invoke(board)
        updateStats()
    }

    private fun shouldAIMove(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> false
            GameMode.PLAYER_VS_AI -> board.currentPlayer == aiColor
        }
    }

    private suspend fun ensureEngine(): ChessEngine? {
        chessEngine?.let { return it }
        return withContext(NonCancellable) {
            val engine = ChessEngine(context)
            try {
                engine.start()
                chessEngine = engine
                engine
            } catch (e: Exception) {
                Log.e("GameController", "Failed to start engine", e)
                engine.close()
                null
            }
        }
    }

    /**
     * 人机模式开局后在后台 IO 线程预热引擎进程与模型，缩短 AI 首步等待；不改变线程/哈希/时长/深度。
     * 人人模式不预热。预热失败静默，不影响对局。
     */
    fun prewarmEngine() {
        if (gameMode != GameMode.PLAYER_VS_AI) return
        coroutineScope.launch(Dispatchers.IO) {
            engineMutex.withLock { ensureEngine() }
        }
    }

    fun makeAIMove() {
        if (board.isCheckmate() || board.isStalemate()) return

        onAIThinking?.invoke(true)

        coroutineScope.launch {
            try {
                val generation = gameGeneration
                val thinkStart = System.currentTimeMillis()
                val sideToMove = board.currentPlayer
                var (move, score) = searchBestMove(null)

                val legal = board.getAllLegalMoves()
                val winning = legal.firstOrNull { repetitionAfter(it)?.winner == sideToMove }
                if (winning != null) {
                    move = winning
                    score = null
                }

                if (move != null && winning == null && (timesSeenAfter(move) >= 1 || looksLikeShuffling(move, sideToMove))) {
                    val repeated = repetitionAfter(move)
                    val loses = repeated != null && repeated.winner == sideToMove.opposite()
                    val fresh = legal.filter { timesSeenAfter(it) == 0 && !looksLikeShuffling(it, sideToMove) }
                    val candidates = if (fresh.isNotEmpty() || !loses) fresh
                        else legal.filter { it != move && repetitionAfter(it)?.winner != sideToMove.opposite() }
                    val (alternative, altScore) = searchBestMove(candidates)
                    if (alternative != null && (repeated != null || acceptableAlternative(score, altScore))) {
                        move = alternative
                        score = altScore
                    }
                }

                if (move != null) {

                    val elapsed = System.currentTimeMillis() - thinkStart
                    val minThink = difficulty.minMoveMs
                    if (elapsed < minThink) delay(minThink - elapsed)
                    if (generation == gameGeneration) {
                        applyAIMove(move)
                    }
                }
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    /**
     * 依据盘面复杂程度在 [minMoveMs, maxMoveMs] 内自适应选取思考时长：
     * 子力多、分支多、被将军、有吃子兑换时靠近上限，残局平静局面靠近下限；始终不越界。
     */
    private fun sampleMoveTime(d: AIDifficulty, board: Board): Long {
        val complexity = estimateComplexity(board)
        return d.minMoveMs + Math.round((d.maxMoveMs - d.minMoveMs) * complexity)
    }

    /** 提示支路：同样按盘面复杂度在 [HINT_MIN_MS, HINT_MAX_MS] 内自适应。 */
    private fun hintMoveTime(board: Board): Long {
        val complexity = estimateComplexity(board)
        return HINT_MIN_MS + Math.round((HINT_MAX_MS - HINT_MIN_MS) * complexity)
    }

    /** 0~1 的盘面复杂度估计：子力密度、合法分支、被将军、存在吃子兑换四项加权。 */
    private fun estimateComplexity(board: Board): Double {
        val pieces = board.getAllPieces().size
        val pieceScore = (pieces - 8).coerceIn(0, 24) / 24.0
        val legalMoves = board.getAllLegalMoves()
        val branchScore = legalMoves.size.coerceIn(0, 60) / 60.0
        val checkScore = if (board.isInCheck(board.currentPlayer)) 0.15 else 0.0
        val captureScore = legalMoves.count { it.capturedPiece != null }
            .coerceIn(0, 8) / 8.0 * 0.15
        return (pieceScore * 0.5 + branchScore * 0.3 + checkScore + captureScore).coerceIn(0.0, 1.0)
    }

    private suspend fun searchBestMove(candidates: List<Move>?): Pair<Move?, ChessEngine.Score?> {
        if (candidates != null && candidates.isEmpty()) return null to null
        val depth = difficulty.engineDepth

        val timeMs = sampleMoveTime(difficulty, board)
        val fromEngine = engineMutex.withLock {
            val engine = ensureEngine() ?: return@withLock null
            val move = engine.findBestMove(board, depth = depth, moveTimeMs = timeMs, searchMoves = candidates)
            if (move != null) move to engine.lastScore else null
        }
        if (fromEngine != null) return fromEngine

        return null to null
    }

    private suspend fun applyAIMove(finalMove: Move) {
        val preBoard = board.copy()
        val captured = finalMove.capturedPiece
        if (captured != null) {
            val captureValue = captured.type.baseValue
            if (finalMove.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(captured)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(captured)
            }
        }

        onMoveAnimationRequested?.invoke(finalMove, preBoard)

        board.makeMoveInPlace(finalMove)
        moveHistory.add(finalMove)
        positionHashes.add(board.getPositionHash())
        firstMovePlayed = true
        syncClock()

        // 播报最先入队，后台与后续棋盘重绘/统计刷新并行合成。
        onMoveCompleted?.invoke(finalMove, preBoard)

        if (finalMove.capturedPiece != null) audioManager.playCaptureSound() else audioManager.playMoveSound()

        onBoardUpdated?.invoke(board)
        updateStats()

        checkGameOver()
    }

    private fun timesSeenAfter(move: Move): Int {
        val testBoard = board.copy()
        testBoard.makeMoveInPlace(move)
        val hash = testBoard.getPositionHash()
        return positionHashes.count { it == hash }
    }

    private fun repetitionAfter(move: Move): GameResult? {
        val testBoard = board.copy()
        testBoard.makeMoveInPlace(move)
        val hash = testBoard.getPositionHash()
        if (positionHashes.count { it == hash } < 2) return null
        return Repetition.judge(initialBoard, moveHistory + move, positionHashes + hash)?.result
    }

    private fun looksLikeShuffling(move: Move, side: PieceColor): Boolean {
        val own = moveHistory.filter { it.piece.color == side }
        val last = own.lastOrNull() ?: return false
        if (last.from == move.to && last.to == move.from) return true
        val twoAgo = own.getOrNull(own.size - 2) ?: return false
        return twoAgo.from == move.from && twoAgo.to == move.to
    }

    private fun acceptableAlternative(best: ChessEngine.Score?, alt: ChessEngine.Score?): Boolean {
        if (best == null || alt == null) return true
        best.mate?.let { return it < 0 }
        alt.mate?.let { return it > 0 }
        val bestCp = best.cp ?: return true
        val altCp = alt.cp ?: return true
        return altCp >= bestCp - 60
    }

    fun isGameOver(): Boolean = gameOver

    private fun checkGameOver(): Boolean {
        val result = resultOf(board, positionHashes, initialBoard, moveHistory)

        gameOver = result != null
        syncClock()
        if (result == null) return false
        onGameOver?.invoke(result)
        return true
    }

    fun undoLastMove(): Boolean {
        gameGeneration++
        gameOver = false
        if (moveHistory.isEmpty()) return false

        val movesToUndo = if (gameMode == GameMode.PLAYER_VS_AI) 2 else 1

        repeat(movesToUndo.coerceAtMost(moveHistory.size)) {
            moveHistory.removeAt(moveHistory.size - 1)
        }

        board = initialBoard.copy()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        for (move in moveHistory) {
            if (move.capturedPiece != null) {
                val captureValue = move.capturedPiece.type.baseValue
                if (move.piece.color == PieceColor.RED) {
                    redScore += captureValue
                    redCapturedPieces.add(move.capturedPiece)
                } else {
                    blackScore += captureValue
                    blackCapturedPieces.add(move.capturedPiece)
                }
            }
            board.makeMoveInPlace(move)
            positionHashes.add(board.getPositionHash())
        }

        firstMovePlayed = moveHistory.isNotEmpty()
        syncClock()

        onBoardUpdated?.invoke(board)
        updateStats()
        return true
    }

    fun getGameMode(): GameMode = gameMode

    fun getAIColor(): PieceColor = aiColor

    fun getDifficulty(): AIDifficulty = difficulty

    fun clockTime(side: PieceColor): Long = clock.elapsed(side)

    fun clockTurn(): PieceColor? = clock.turn

    private fun syncClock() {
        val stopped = gameOver || board.isCheckmate() || board.isStalemate()
        when {
            stopped -> clock.setTurn(null)
            firstMovePlayed -> clock.setTurn(board.currentPlayer)
            shouldAIMove() -> clock.setTurn(board.currentPlayer)
            else -> clock.setTurn(null)
        }
    }

    fun onPlayerSelectedPiece() {
        if (gameOver || firstMovePlayed || shouldAIMove()) return
        clock.setTurn(board.currentPlayer)
    }

    fun isPlayerTurn(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> true
            GameMode.PLAYER_VS_AI -> board.currentPlayer != aiColor
        }
    }

    private fun updateStats() {
        val stats = GameStats(
            redScore = redScore,
            blackScore = blackScore,
            moveNumber = moveHistory.size,
            redCapturedPieces = redCapturedPieces.toList(),
            blackCapturedPieces = blackCapturedPieces.toList()
        )
        onStatsUpdated?.invoke(stats)
    }

    companion object {

        // 提示支路：思考时长区间（毫秒），不限深度、满线程大哈希，搜索更充分。
        private const val HINT_MIN_MS = 4500L
        private const val HINT_MAX_MS = 5500L

        fun resultOf(board: Board, positionHashes: List<Long>, start: Board, moves: List<Move>): GameResult? {
            val toMove = board.currentPlayer
            if (board.getAllLegalMoves().isEmpty()) {
                return if (board.isInCheck(toMove)) GameResult.Checkmate(toMove.opposite()) else GameResult.Stalemate(toMove.opposite())
            }
            return Repetition.judge(start, moves, positionHashes)?.result
        }
    }

    fun setInForeground(inForeground: Boolean) {
        clock.setPaused(!inForeground)
    }

    fun getHint(callback: (Move?) -> Unit) {
        if (board.isCheckmate() || board.isStalemate()) {
            callback(null)
            return
        }

        onAIThinking?.invoke(true)
        coroutineScope.launch {
            val generation = gameGeneration
            try {
                val fromEngine = engineMutex.withLock {
                    val engine = ensureEngine() ?: return@withLock null
                    val hintTime = hintMoveTime(board)
                    val move = engine.findBestMove(board, depth = 0, moveTimeMs = hintTime)
                    if (move != null) move to engine.lastScore else null
                }
                if (generation != gameGeneration) {
                    // 思考期间已新开局或悔棋，丢弃过期建议
                } else if (fromEngine != null) {
                    callback(fromEngine.first)
                } else {

                    callback(null)
                }
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    fun destroy() {
        coroutineScope.cancel()
        chessEngine?.close()
        chessEngine = null
    }
}
