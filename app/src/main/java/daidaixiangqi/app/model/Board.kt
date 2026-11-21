package daidaixiangqi.app.model

class Board {
    private val pieces = mutableMapOf<Position, Piece>()
    var currentPlayer = PieceColor.RED
        internal set

    companion object {
        const val ROWS = 10
        const val COLS = 9

        fun createInitialBoard(): Board {
            val board = Board()

            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.BLACK, Position(0, 0)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.BLACK, Position(0, 1)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.BLACK, Position(0, 2)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)))
            board.addPiece(Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.BLACK, Position(0, 6)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.BLACK, Position(0, 7)))
            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.BLACK, Position(0, 8)))

            board.addPiece(Piece(PieceType.CANNON, PieceColor.BLACK, Position(2, 1)))
            board.addPiece(Piece(PieceType.CANNON, PieceColor.BLACK, Position(2, 7)))

            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 0)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 2)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 4)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 6)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 8)))

            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.RED, Position(9, 0)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.RED, Position(9, 1)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.RED, Position(9, 2)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.RED, Position(9, 3)))
            board.addPiece(Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.RED, Position(9, 5)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.RED, Position(9, 6)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.RED, Position(9, 7)))
            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.RED, Position(9, 8)))

            board.addPiece(Piece(PieceType.CANNON, PieceColor.RED, Position(7, 1)))
            board.addPiece(Piece(PieceType.CANNON, PieceColor.RED, Position(7, 7)))

            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 0)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 2)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 4)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 6)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 8)))

            return board
        }
    }

    private fun addPiece(piece: Piece) {
        pieces[piece.position] = piece
    }

    fun getPiece(position: Position): Piece? = pieces[position]

    fun getAllPieces(): List<Piece> = pieces.values.toList()

    /** 绘制热路径用：直接遍历内部集合，避免每帧复制一份列表。 */
    fun pieceValues(): Collection<Piece> = pieces.values

    fun getPiecesByColor(color: PieceColor): List<Piece> =
        pieces.values.filter { it.color == color }

    fun getAllLegalMoves(): List<Move> {
        val moves = mutableListOf<Move>()
        for (piece in getPiecesByColor(currentPlayer)) {
            moves.addAll(piece.getLegalMoves(this))
        }

        return moves.filterNot { move ->
            val testBoard = makeMove(move)
            testBoard.isInCheck(currentPlayer)
        }
    }

    fun isGeneralsFacing(): Boolean {
        val redGeneral = pieces.values.find { it.type == PieceType.GENERAL && it.color == PieceColor.RED }
        val blackGeneral = pieces.values.find { it.type == PieceType.GENERAL && it.color == PieceColor.BLACK }

        if (redGeneral == null || blackGeneral == null) return false
        if (redGeneral.position.col != blackGeneral.position.col) return false

        val minRow = minOf(redGeneral.position.row, blackGeneral.position.row)
        val maxRow = maxOf(redGeneral.position.row, blackGeneral.position.row)

        for (row in (minRow + 1) until maxRow) {
            if (getPiece(Position(row, redGeneral.position.col)) != null) {
                return false
            }
        }

        return true
    }

    fun isInCheck(color: PieceColor): Boolean {
        val general = pieces.values.find { it.type == PieceType.GENERAL && it.color == color }
            ?: return true

        if (isGeneralsFacing()) return true

        val opponentMoves = getPiecesByColor(color.opposite()).flatMap { piece ->
            piece.getLegalMoves(this)
        }

        return opponentMoves.any { it.to == general.position }
    }

    fun isCheckmate(): Boolean {
        return isInCheck(currentPlayer) && getAllLegalMoves().isEmpty()
    }

    fun isStalemate(): Boolean {
        return !isInCheck(currentPlayer) && getAllLegalMoves().isEmpty()
    }

    /**
     * 展示层一次性状态快照：将「被将 / 合法着法是否为空 / 将死 / 困毙」合并为一次计算，
     * 复用同一盘面的中间结果，避免同一界面刷新里重复生成两遍合法着法、重复判定多次被将。
     * 各字段与 [isInCheck]、[isCheckmate]、[isStalemate] 的定义严格等价，不改变任何判决结果。
     */
    data class StatusSnapshot(
        val inCheck: Boolean,
        val legalMovesEmpty: Boolean,
        val checkmate: Boolean,
        val stalemate: Boolean
    )

    fun statusSnapshot(): StatusSnapshot {
        val inCheck = isInCheck(currentPlayer)
        val legalEmpty = getAllLegalMoves().isEmpty()
        return StatusSnapshot(
            inCheck = inCheck,
            legalMovesEmpty = legalEmpty,
            checkmate = inCheck && legalEmpty,
            stalemate = !inCheck && legalEmpty
        )
    }

    fun makeMove(move: Move): Board {
        val newBoard = Board()
        newBoard.pieces.putAll(this.pieces)
        newBoard.currentPlayer = this.currentPlayer

        newBoard.pieces.remove(move.from)

        if (move.capturedPiece != null) {
            newBoard.pieces.remove(move.to)
        }

        newBoard.pieces[move.to] = move.piece.copy(newPosition = move.to)

        return newBoard
    }

    fun makeMoveInPlace(move: Move) {
        pieces.remove(move.from)
        if (move.capturedPiece != null) {
            pieces.remove(move.to)
        }
        pieces[move.to] = move.piece.copy(newPosition = move.to)
        currentPlayer = currentPlayer.opposite()
    }

    fun copy(): Board {
        val newBoard = Board()
        newBoard.pieces.putAll(this.pieces)
        newBoard.currentPlayer = this.currentPlayer
        return newBoard
    }

    fun getPositionHash(): Long = daidaixiangqi.app.ai.ZobristHash.hash(this)
}
