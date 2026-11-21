package daidaixiangqi.app.model

import kotlin.math.abs

/**
 * 非法走法的具体成因。
 *
 * 只用于向玩家解释"这一步为什么走不了"，不参与任何规则判决；
 * 合法走法的生成与判负逻辑仍以 [Piece.getLegalMoves] 与 [Board.getAllLegalMoves] 为准，
 * 因此这里的判定结论不会放宽或收紧可走范围。
 */
enum class MoveViolation {
    TARGET_OWN_PIECE,
    HORSE_SHAPE,
    HORSE_LEG_BLOCKED,
    ELEPHANT_SHAPE,
    ELEPHANT_EYE_BLOCKED,
    ELEPHANT_RIVER,
    CHARIOT_SHAPE,
    CHARIOT_BLOCKED,
    CANNON_SHAPE,
    CANNON_BLOCKED,
    CANNON_SCREEN_MISSING,
    CANNON_SCREEN_EXTRA,
    ADVISOR_SHAPE,
    ADVISOR_PALACE,
    GENERAL_SHAPE,
    GENERAL_PALACE,
    SOLDIER_SHAPE,
    SOLDIER_BACKWARD,
    SOLDIER_SIDE
}

object MoveRules {

    /**
     * 判定从 [piece] 当前位置走到 [to] 违反哪条走法。
     *
     * @return null 表示走法本身符合棋规——此时若走完仍被将军，属于"送将"，
     *         由上层另行判定并以语音提示。
     */
    fun violation(board: Board, piece: Piece, to: Position): MoveViolation? {
        if (!to.isValid() || to == piece.position) return null

        val target = board.getPiece(to)
        if (target != null && target.color == piece.color) return MoveViolation.TARGET_OWN_PIECE

        val dr = to.row - piece.position.row
        val dc = to.col - piece.position.col
        val adr = abs(dr)
        val adc = abs(dc)

        return when (piece.type) {
            PieceType.GENERAL -> generalViolation(piece, to, adr, adc)
            PieceType.ADVISOR -> advisorViolation(piece, to, adr, adc)
            PieceType.ELEPHANT -> elephantViolation(board, piece, to, adr, adc)
            PieceType.HORSE -> horseViolation(board, piece, dr, dc, adr, adc)
            PieceType.CHARIOT -> chariotViolation(board, piece, to, dr, dc)
            PieceType.CANNON -> cannonViolation(board, piece, to, target, dr, dc)
            PieceType.SOLDIER -> soldierViolation(piece, dr, dc, adc)
        }
    }

    private fun generalViolation(piece: Piece, to: Position, adr: Int, adc: Int): MoveViolation? {
        if (adr + adc != 1) return MoveViolation.GENERAL_SHAPE
        return if (!inPalace(piece.color, to)) MoveViolation.GENERAL_PALACE else null
    }

    private fun advisorViolation(piece: Piece, to: Position, adr: Int, adc: Int): MoveViolation? {
        if (adr != 1 || adc != 1) return MoveViolation.ADVISOR_SHAPE
        return if (!inPalace(piece.color, to)) MoveViolation.ADVISOR_PALACE else null
    }

    private fun elephantViolation(
        board: Board, piece: Piece, to: Position, adr: Int, adc: Int
    ): MoveViolation? {
        if (adr != 2 || adc != 2) return MoveViolation.ELEPHANT_SHAPE
        val allowedRows = if (piece.color == PieceColor.RED) 5..9 else 0..4
        if (to.row !in allowedRows) return MoveViolation.ELEPHANT_RIVER
        val eye = Position((piece.position.row + to.row) / 2, (piece.position.col + to.col) / 2)
        return if (board.getPiece(eye) != null) MoveViolation.ELEPHANT_EYE_BLOCKED else null
    }

    private fun horseViolation(
        board: Board, piece: Piece, dr: Int, dc: Int, adr: Int, adc: Int
    ): MoveViolation? {
        if (adr * adc != 2) return MoveViolation.HORSE_SHAPE
        // 马腿在长轴方向紧邻的一格：横二时看横向，竖二时看纵向。
        val legRow = piece.position.row + if (adr == 2) dr / 2 else 0
        val legCol = piece.position.col + if (adc == 2) dc / 2 else 0
        return if (board.getPiece(Position(legRow, legCol)) != null) {
            MoveViolation.HORSE_LEG_BLOCKED
        } else {
            null
        }
    }

    private fun chariotViolation(
        board: Board, piece: Piece, to: Position, dr: Int, dc: Int
    ): MoveViolation? {
        if (dr != 0 && dc != 0) return MoveViolation.CHARIOT_SHAPE
        return if (countBetween(board, piece.position, to) > 0) {
            MoveViolation.CHARIOT_BLOCKED
        } else {
            null
        }
    }

    private fun cannonViolation(
        board: Board, piece: Piece, to: Position, target: Piece?, dr: Int, dc: Int
    ): MoveViolation? {
        if (dr != 0 && dc != 0) return MoveViolation.CANNON_SHAPE
        val screens = countBetween(board, piece.position, to)
        return if (target == null) {
            // 不吃子：与车一样，路径上不能有任何棋子。
            if (screens > 0) MoveViolation.CANNON_BLOCKED else null
        } else {
            // 吃子：必须恰好隔一个炮架。
            when {
                screens == 0 -> MoveViolation.CANNON_SCREEN_MISSING
                screens > 1 -> MoveViolation.CANNON_SCREEN_EXTRA
                else -> null
            }
        }
    }

    private fun soldierViolation(piece: Piece, dr: Int, dc: Int, adc: Int): MoveViolation? {
        val forward = if (piece.color == PieceColor.RED) -1 else 1
        val crossedRiver =
            if (piece.color == PieceColor.RED) piece.position.row <= 4 else piece.position.row >= 5
        return when {
            dr == forward && dc == 0 -> null
            dr == 0 && adc == 1 -> if (crossedRiver) null else MoveViolation.SOLDIER_SIDE
            dr == -forward && dc == 0 -> MoveViolation.SOLDIER_BACKWARD
            else -> MoveViolation.SOLDIER_SHAPE
        }
    }

    private fun inPalace(color: PieceColor, pos: Position): Boolean {
        val rows = if (color == PieceColor.RED) 7..9 else 0..2
        return pos.row in rows && pos.col in 3..5
    }

    /** 同行或同列两点之间的棋子数；不同行列时返回 -1。 */
    private fun countBetween(board: Board, from: Position, to: Position): Int {
        val dr = Integer.compare(to.row, from.row)
        val dc = Integer.compare(to.col, from.col)
        if (dr != 0 && dc != 0) return -1
        var row = from.row + dr
        var col = from.col + dc
        var count = 0
        while (row != to.row || col != to.col) {
            if (board.getPiece(Position(row, col)) != null) count++
            row += dr
            col += dc
        }
        return count
    }
}
