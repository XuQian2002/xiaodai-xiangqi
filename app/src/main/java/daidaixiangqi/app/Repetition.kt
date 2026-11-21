package daidaixiangqi.app

import daidaixiangqi.app.GameController.GameResult
import daidaixiangqi.app.model.Board
import daidaixiangqi.app.model.Move
import daidaixiangqi.app.model.Piece
import daidaixiangqi.app.model.PieceColor
import daidaixiangqi.app.model.PieceType
import daidaixiangqi.app.model.Position

object Repetition {
    enum class Conduct { NONE, CHASE, CHECK_AND_CHASE, CHECK }

    data class MoveKind(val check: Boolean, val chase: Boolean)

    data class Verdict(val result: GameResult, val red: Conduct, val black: Conduct)

    private fun chaseRank(type: PieceType): Int = when (type) {
        PieceType.CHARIOT -> 3
        PieceType.HORSE, PieceType.CANNON -> 2
        else -> 1
    }

    private fun hasCrossedRiver(piece: Piece): Boolean =
        if (piece.color == PieceColor.RED) piece.position.row <= 4 else piece.position.row >= 5

    private fun Board.withSideToMove(color: PieceColor): Board = copy().also { it.currentPlayer = color }

    private fun isLegalFor(board: Board, move: Move, color: PieceColor): Boolean =
        !board.makeMove(move).isInCheck(color)

    private fun reachedSquares(board: Board, attacker: Piece): Set<Position> =
        attacker.getLegalMoves(board).filter { it.capturedPiece != null }.map { it.to }.toSet()

    private fun isProtected(after: Board, target: Position, defender: PieceColor): Boolean {
        val board = after.withSideToMove(defender)
        return board.getPiecesByColor(defender).any { piece ->
            piece.getLegalMoves(board).any { it.to == target && isLegalFor(board, it, defender) }
        }
    }

    private fun boardAfter(before: Board, move: Move): Board = before.copy().also { it.makeMoveInPlace(move) }

    fun classifyMove(before: Board, move: Move): MoveKind {
        val mover = move.piece.color
        val opponent = mover.opposite()
        val after = boardAfter(before, move)
        val check = after.isInCheck(opponent)

        val reachedBefore = before.getPiecesByColor(mover)
            .filter { it.type != PieceType.GENERAL && it.type != PieceType.SOLDIER }
            .associate { it.position to reachedSquares(before, it) }

        val post = after.withSideToMove(mover)
        val chase = post.getPiecesByColor(mover).any { attacker ->
            if (attacker.type == PieceType.GENERAL || attacker.type == PieceType.SOLDIER) return@any false
            val oldSquare = if (attacker.position == move.to) move.from else attacker.position
            val before_ = reachedBefore[oldSquare] ?: emptySet()
            attacker.getLegalMoves(post).any { capture ->
                val target = capture.capturedPiece ?: return@any false
                if (capture.to in before_) return@any false
                if (target.type == PieceType.GENERAL) return@any false
                if (target.type == PieceType.SOLDIER && !hasCrossedRiver(target)) return@any false
                if (!isLegalFor(post, capture, mover)) return@any false
                chaseRank(attacker.type) < chaseRank(target.type) ||
                    !isProtected(post.makeMove(capture), capture.to, opponent)
            }
        }
        return MoveKind(check, chase)
    }

    fun conductOf(kinds: List<MoveKind>): Conduct = when {
        kinds.isEmpty() -> Conduct.NONE
        kinds.all { it.check } -> Conduct.CHECK
        kinds.all { it.check || it.chase } -> if (kinds.any { it.check }) Conduct.CHECK_AND_CHASE else Conduct.CHASE
        else -> Conduct.NONE
    }

    private fun severity(conduct: Conduct): Int = when (conduct) {
        Conduct.CHECK, Conduct.CHECK_AND_CHASE -> 2
        Conduct.CHASE -> 1
        Conduct.NONE -> 0
    }

    fun outcome(red: Conduct, black: Conduct): GameResult {
        val r = severity(red)
        val b = severity(black)
        if (r == b) return GameResult.RepetitionDraw
        val winner = if (r > b) PieceColor.BLACK else PieceColor.RED
        return if (maxOf(r, b) == 2) GameResult.PerpetualCheck(winner) else GameResult.PerpetualChase(winner)
    }

    fun judgeCycle(start: Board, moves: List<Move>, from: Int, to: Int): Verdict {
        var board = start
        for (i in 0 until from) board = boardAfter(board, moves[i])
        val red = mutableListOf<MoveKind>()
        val black = mutableListOf<MoveKind>()
        for (i in from until to) {
            val move = moves[i]
            (if (move.piece.color == PieceColor.RED) red else black).add(classifyMove(board, move))
            board = boardAfter(board, move)
        }
        val redConduct = conductOf(red)
        val blackConduct = conductOf(black)
        return Verdict(outcome(redConduct, blackConduct), redConduct, blackConduct)
    }

    fun judge(start: Board, moves: List<Move>, positionHashes: List<Long>): Verdict? {
        val last = positionHashes.size - 1
        if (last < 0) return null
        val hash = positionHashes[last]
        var seen = 0
        var previous = -1
        for (i in 0 until last) {
            if (positionHashes[i] == hash) {
                seen++
                previous = i
            }
        }
        if (seen < 2) return null
        return judgeCycle(start, moves, previous, last)
    }
}
