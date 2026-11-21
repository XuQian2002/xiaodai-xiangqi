package daidaixiangqi.app.model

/**
 * 将军后伏抽大子的静态识别。
 *
 * 只依据走子后的终局盘面判断：走动棋子在其攻击线上，除对方将之外还能吃到对方大子
 * （车、炮、马），即视为"抽车 / 抽炮 / 抽马"，返回被抽大子的显示名；否则返回 null。
 * 兵卒价值低，不单独作为抽子播报。识别为保守口径，拿不准时返回 null，由播报层退回基础句。
 */
object SweepCapture {

    /** 只识别大子，按子力价值从高到低取一枚。 */
    private val MAJOR = listOf(PieceType.CHARIOT, PieceType.CANNON, PieceType.HORSE)

    fun detect(board: Board, move: Move, attacker: PieceColor): String? {
        val moved = board.getPiece(move.to)?.takeIf { it.color == attacker }
            ?: move.piece
        val defender = attacker.opposite()
        val general = board.getAllPieces()
            .find { it.type == PieceType.GENERAL && it.color == defender }
            ?: return null

        val targets = moved.getLegalMoves(board)
            .filter {
                it.capturedPiece != null &&
                    it.capturedPiece!!.color == defender &&
                    it.to != general.position
            }

        for (type in MAJOR) {
            val hit = targets.firstOrNull { it.capturedPiece!!.type == type }
            if (hit != null) return type.getDisplayName(defender)
        }
        return null
    }
}
