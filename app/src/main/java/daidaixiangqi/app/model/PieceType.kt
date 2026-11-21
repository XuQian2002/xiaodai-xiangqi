package daidaixiangqi.app.model

enum class PieceType(val chineseName: String, val baseValue: Int) {
    GENERAL("将/帥", 10000),
    ADVISOR("士/仕", 120),
    ELEPHANT("象/相", 120),
    HORSE("马/傌", 400),
    CHARIOT("车/俥", 900),
    CANNON("炮/炮", 450),
    SOLDIER("卒/兵", 100);

    fun getDisplayName(color: PieceColor): String {
        return when (this) {
            GENERAL -> if (color == PieceColor.RED) "帥" else "将"
            ADVISOR -> if (color == PieceColor.RED) "仕" else "士"
            ELEPHANT -> if (color == PieceColor.RED) "相" else "象"
            HORSE -> if (color == PieceColor.RED) "傌" else "马"
            CHARIOT -> if (color == PieceColor.RED) "俥" else "车"
            CANNON -> "炮"
            SOLDIER -> if (color == PieceColor.RED) "兵" else "卒"
        }
    }
}
