package daidaixiangqi.app.model

data class Move(
    val from: Position,
    val to: Position,
    val piece: Piece,
    val capturedPiece: Piece?
) {
    override fun toString(): String {
        return "${piece.type.chineseName} $from -> $to" +
                if (capturedPiece != null) " captures ${capturedPiece.type.chineseName}" else ""
    }
}
