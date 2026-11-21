package daidaixiangqi.app.model

enum class PieceColor {
    RED,
    BLACK;

    fun opposite(): PieceColor = if (this == RED) BLACK else RED
}
