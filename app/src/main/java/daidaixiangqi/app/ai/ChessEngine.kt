package daidaixiangqi.app.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import daidaixiangqi.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

class ChessEngine(private val context: Context) : Closeable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isReady = false

    data class Score(val cp: Int?, val mate: Int?)

    var lastScore: Score? = null
        private set

    companion object {
        private const val TAG = "ChessEngine"
        private const val BINARY_NAME = "libpikafish.so"
        private const val NNUE_NAME = "pikafish.nnue"

        private val PIECE_TO_FEN = mapOf(
            PieceType.CHARIOT to 'R',
            PieceType.HORSE to 'N',
            PieceType.ELEPHANT to 'B',
            PieceType.ADVISOR to 'A',
            PieceType.GENERAL to 'K',
            PieceType.CANNON to 'C',
            PieceType.SOLDIER to 'P'
        )
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val binaryFile = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        val nnueFile = File(filesDir, NNUE_NAME)

        check(binaryFile.canExecute()) { "Engine binary is missing or not executable: $binaryFile" }

        if (!nnueFile.exists()) {
            extractAsset(NNUE_NAME, nnueFile)
        }

        val pb = ProcessBuilder(binaryFile.absolutePath)
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        process = pb.start()
        writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
        reader = BufferedReader(InputStreamReader(process!!.inputStream))

        sendCommand("uci")
        waitForResponse("uciok")

        val threads = resolveThreads()
        val hashMb = resolveHashMb()
        sendCommand("setoption name Threads value $threads")
        sendCommand("setoption name Hash value $hashMb")
        sendCommand("isready")
        waitForResponse("readyok")

        isReady = true
        Log.i(TAG, "Engine started (threads=$threads, hash=${hashMb}MB)")
    }

    private fun resolveThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private fun resolveHashMb(): Int = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        when {
            am == null -> 256
            am.isLowRamDevice -> 64
            else -> minOf(1024, am.memoryClass.coerceAtLeast(96))
        }
    } catch (e: Exception) {
        256
    }

    suspend fun findBestMove(
        board: Board,
        depth: Int = 0,
        moveTimeMs: Long = 0,
        searchMoves: List<Move>? = null
    ): Move? = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext null
        if (searchMoves != null && searchMoves.isEmpty()) return@withContext null

        val fen = boardToFen(board)
        sendCommand("position fen $fen")

        val goCmd = buildString {
            append("go")
            if (depth > 0) append(" depth $depth")
            if (moveTimeMs > 0) append(" movetime $moveTimeMs")
            if (searchMoves != null) {
                append(" searchmoves ")
                append(searchMoves.joinToString(" ") { posToUci(it.from) + posToUci(it.to) })
            }
        }
        sendCommand(goCmd)

        val bestMoveUci = readUntilBestMove()
        if (bestMoveUci == null || bestMoveUci == "(none)") return@withContext null

        uciToMove(bestMoveUci, board)
    }

    private fun readUntilBestMove(): String? {
        var bestMoveUci: String? = null
        while (true) {
            val line = reader?.readLine() ?: break
            if (line.startsWith("info ")) {
                parseScore(line)?.let { lastScore = it }
            } else if (line.startsWith("bestmove")) {
                bestMoveUci = line.split(" ").getOrNull(1)
                break
            }
        }
        return bestMoveUci
    }

    private fun parseScore(line: String): Score? {
        val parts = line.split(" ")

        val pvIndex = parts.indexOf("multipv")
        if (pvIndex >= 0 && parts.getOrNull(pvIndex + 1) != "1") return null
        val i = parts.indexOf("score")
        if (i < 0) return null
        val kind = parts.getOrNull(i + 1) ?: return null
        val value = parts.getOrNull(i + 2)?.toIntOrNull() ?: return null
        return when (kind) {
            "cp" -> Score(cp = value, mate = null)
            "mate" -> Score(cp = null, mate = value)
            else -> null
        }
    }

    override fun close() {
        try {
            sendCommandSync("quit")
        } catch (_: Exception) {}
        process?.destroy()
        process = null
        writer = null
        reader = null
        isReady = false
    }

    private fun boardToFen(board: Board): String {
        val sb = StringBuilder()

        for (row in 0 until Board.ROWS) {
            var empty = 0
            for (col in 0 until Board.COLS) {
                val piece = board.getPiece(Position(row, col))
                if (piece == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    val ch = PIECE_TO_FEN[piece.type] ?: 'P'
                    sb.append(if (piece.color == PieceColor.RED) ch.uppercaseChar() else ch.lowercaseChar())
                }
            }
            if (empty > 0) sb.append(empty)
            if (row < Board.ROWS - 1) sb.append('/')
        }

        sb.append(if (board.currentPlayer == PieceColor.RED) " w" else " b")
        sb.append(" - - 0 1")

        return sb.toString()
    }

    private fun posToUci(pos: Position): String {
        val col = ('a' + pos.col)
        val row = 9 - pos.row
        return "$col$row"
    }

    private fun uciToPos(uci: String): Position {
        val col = uci[0] - 'a'
        val row = 9 - (uci[1] - '0')
        return Position(row, col)
    }

    private fun uciToMove(uci: String, board: Board): Move? {
        if (uci.length < 4) return null
        val from = uciToPos(uci.substring(0, 2))
        val to = uciToPos(uci.substring(2, 4))
        val piece = board.getPiece(from) ?: return null
        val captured = board.getPiece(to)
        return Move(from, to, piece, captured)
    }

    private fun sendCommand(cmd: String) {
        writer?.write("$cmd\n")
        writer?.flush()
    }

    private fun sendCommandSync(cmd: String) {
        writer?.write("$cmd\n")
        writer?.flush()
    }

    private fun waitForResponse(expected: String) {
        while (true) {
            val line = reader?.readLine() ?: break
            if (line.startsWith(expected)) break
        }
    }

    private fun extractAsset(assetName: String, targetFile: File) {
        Log.i(TAG, "Extracting $assetName to ${targetFile.absolutePath}")
        context.assets.open(assetName).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
