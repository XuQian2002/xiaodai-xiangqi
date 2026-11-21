package daidaixiangqi.app

import daidaixiangqi.app.model.Board
import daidaixiangqi.app.model.Move
import daidaixiangqi.app.model.PieceColor
import daidaixiangqi.app.model.PieceType
import kotlin.math.abs

object MoveNotation {

    private val RED_FILES = listOf("玖", "捌", "柒", "陸", "伍", "肆", "叁", "貳", "壹")

    private val BLACK_FILES = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val RED_STEPS = listOf("", "壹", "貳", "叁", "肆", "伍", "陸", "柒", "捌", "玖")
    private val BLACK_STEPS = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    // 落点简写标签保留上一轮的正常写法（红汉字、黑全角数字），不随完整记谱改繁体。
    private val SHORT_RED_FILES = listOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
    private val SHORT_BLACK_FILES = listOf("１", "２", "３", "４", "５", "６", "７", "８", "９")
    private val SHORT_RED_STEPS = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val SHORT_BLACK_STEPS = listOf("", "１", "２", "３", "４", "５", "６", "７", "８", "９")
    private val DIAGONAL = setOf(PieceType.HORSE, PieceType.ELEPHANT, PieceType.ADVISOR)

    fun format(move: Move, boardBefore: Board): String {
        val piece = move.piece
        val isRed = piece.color == PieceColor.RED
        val name = piece.type.getDisplayName(piece.color)
        val files = if (isRed) RED_FILES else BLACK_FILES
        val steps = if (isRed) RED_STEPS else BLACK_STEPS
        val rowDiff = move.to.row - move.from.row

        val forward = if (isRed) rowDiff < 0 else rowDiff > 0

        val sameFile = boardBefore.getAllPieces()
            .filter { it.color == piece.color && it.type == piece.type && it.position.col == move.from.col }
            .sortedBy { it.position.row }
        val head = if (sameFile.size >= 2 && piece.type != PieceType.GENERAL) {

            val idx = sameFile.indexOfFirst { it.position == move.from }
            val rank = if (isRed) idx else sameFile.size - 1 - idx
            val label = when {
                rank == 0 -> "前"
                rank == sameFile.size - 1 -> "后"
                else -> "中"
            }
            label + name
        } else {
            name + files[move.from.col]
        }

        return when {
            rowDiff == 0 -> head + "平" + files[move.to.col]
            piece.type in DIAGONAL -> head + (if (forward) "进" else "退") + files[move.to.col]
            else -> head + (if (forward) "进" else "退") + steps[abs(rowDiff)]
        }
    }

    /**
     * 落点提示用简写：只保留动作与终点（如「平五」「进一」「退二」），
     * 不含棋子名与起点路数，避免单格内文字拥挤。
     */
    fun formatShort(move: Move): String {
        val piece = move.piece
        val isRed = piece.color == PieceColor.RED
        val files = if (isRed) SHORT_RED_FILES else SHORT_BLACK_FILES
        val steps = if (isRed) SHORT_RED_STEPS else SHORT_BLACK_STEPS
        val rowDiff = move.to.row - move.from.row
        val forward = if (isRed) rowDiff < 0 else rowDiff > 0

        return when {
            rowDiff == 0 -> "平" + files[move.to.col]
            piece.type in DIAGONAL -> (if (forward) "进" else "退") + files[move.to.col]
            else -> (if (forward) "进" else "退") + steps[abs(rowDiff)]
        }
    }

    fun formatAll(moves: List<Move>, initialBoard: Board): List<String> {
        val board = initialBoard.copy()
        return moves.map { move ->
            val text = format(move, board)
            board.makeMoveInPlace(move)
            text
        }
    }
}
