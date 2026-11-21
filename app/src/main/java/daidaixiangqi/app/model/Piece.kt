package daidaixiangqi.app.model

data class Piece(
    val type: PieceType,
    val color: PieceColor,
    val position: Position
) {

    fun getLegalMoves(board: Board): List<Move> {
        val moves = mutableListOf<Move>()

        when (type) {
            PieceType.GENERAL -> generateGeneralMoves(board, moves)
            PieceType.ADVISOR -> generateAdvisorMoves(board, moves)
            PieceType.ELEPHANT -> generateElephantMoves(board, moves)
            PieceType.HORSE -> generateHorseMoves(board, moves)
            PieceType.CHARIOT -> generateChariotMoves(board, moves)
            PieceType.CANNON -> generateCannonMoves(board, moves)
            PieceType.SOLDIER -> generateSoldierMoves(board, moves)
        }

        return moves
    }

    private fun generateGeneralMoves(board: Board, moves: MutableList<Move>) {

        val palaceRows = if (color == PieceColor.RED) 7..9 else 0..2
        val palaceCols = 3..5

        val directions = listOf(
            Position(-1, 0), Position(1, 0),
            Position(0, -1), Position(0, 1)
        )

        for (dir in directions) {
            val newPos = Position(position.row + dir.row, position.col + dir.col)
            if (newPos.row in palaceRows && newPos.col in palaceCols) {
                val target = board.getPiece(newPos)
                if (target == null || target.color != color) {
                    moves.add(Move(position, newPos, this, target))
                }
            }
        }

        moves.removeIf { move ->
            val testBoard = board.makeMove(move)
            testBoard.isGeneralsFacing()
        }
    }

    private fun generateAdvisorMoves(board: Board, moves: MutableList<Move>) {

        val palaceRows = if (color == PieceColor.RED) 7..9 else 0..2
        val palaceCols = 3..5

        val diagonals = listOf(
            Position(-1, -1), Position(-1, 1),
            Position(1, -1), Position(1, 1)
        )

        for (diag in diagonals) {
            val newPos = Position(position.row + diag.row, position.col + diag.col)
            if (newPos.row in palaceRows && newPos.col in palaceCols) {
                val target = board.getPiece(newPos)
                if (target == null || target.color != color) {
                    moves.add(Move(position, newPos, this, target))
                }
            }
        }
    }

    private fun generateElephantMoves(board: Board, moves: MutableList<Move>) {

        val allowedRows = if (color == PieceColor.RED) 5..9 else 0..4

        val elephantMoves = listOf(
            Pair(Position(-2, -2), Position(-1, -1)),
            Pair(Position(-2, 2), Position(-1, 1)),
            Pair(Position(2, -2), Position(1, -1)),
            Pair(Position(2, 2), Position(1, 1))
        )

        for ((dest, block) in elephantMoves) {
            val newPos = Position(position.row + dest.row, position.col + dest.col)
            val blockPos = Position(position.row + block.row, position.col + block.col)

            if (newPos.isValid() && newPos.row in allowedRows) {

                if (board.getPiece(blockPos) == null) {
                    val target = board.getPiece(newPos)
                    if (target == null || target.color != color) {
                        moves.add(Move(position, newPos, this, target))
                    }
                }
            }
        }
    }

    private fun generateHorseMoves(board: Board, moves: MutableList<Move>) {

        val horseMoves = listOf(
            Pair(Position(-2, -1), Position(-1, 0)),
            Pair(Position(-2, 1), Position(-1, 0)),
            Pair(Position(-1, -2), Position(0, -1)),
            Pair(Position(-1, 2), Position(0, 1)),
            Pair(Position(1, -2), Position(0, -1)),
            Pair(Position(1, 2), Position(0, 1)),
            Pair(Position(2, -1), Position(1, 0)),
            Pair(Position(2, 1), Position(1, 0))
        )

        for ((dest, block) in horseMoves) {
            val newPos = Position(position.row + dest.row, position.col + dest.col)
            val blockPos = Position(position.row + block.row, position.col + block.col)

            if (newPos.isValid()) {

                if (board.getPiece(blockPos) == null) {
                    val target = board.getPiece(newPos)
                    if (target == null || target.color != color) {
                        moves.add(Move(position, newPos, this, target))
                    }
                }
            }
        }
    }

    private fun generateChariotMoves(board: Board, moves: MutableList<Move>) {

        val directions = listOf(
            Position(-1, 0), Position(1, 0),
            Position(0, -1), Position(0, 1)
        )

        for (dir in directions) {
            var steps = 1
            while (true) {
                val newPos = Position(
                    position.row + dir.row * steps,
                    position.col + dir.col * steps
                )

                if (!newPos.isValid()) break

                val target = board.getPiece(newPos)
                if (target == null) {
                    moves.add(Move(position, newPos, this, null))
                    steps++
                } else {
                    if (target.color != color) {
                        moves.add(Move(position, newPos, this, target))
                    }
                    break
                }
            }
        }
    }

    private fun generateCannonMoves(board: Board, moves: MutableList<Move>) {

        val directions = listOf(
            Position(-1, 0), Position(1, 0),
            Position(0, -1), Position(0, 1)
        )

        for (dir in directions) {
            var steps = 1
            var jumped = false

            while (true) {
                val newPos = Position(
                    position.row + dir.row * steps,
                    position.col + dir.col * steps
                )

                if (!newPos.isValid()) break

                val target = board.getPiece(newPos)

                if (!jumped) {

                    if (target == null) {
                        moves.add(Move(position, newPos, this, null))
                    } else {
                        jumped = true
                    }
                } else {

                    if (target != null) {
                        if (target.color != color) {
                            moves.add(Move(position, newPos, this, target))
                        }
                        break
                    }
                }

                steps++
            }
        }
    }

    private fun generateSoldierMoves(board: Board, moves: MutableList<Move>) {

        val forwardDir = if (color == PieceColor.RED) -1 else 1
        val hasCrossedRiver = if (color == PieceColor.RED) position.row <= 4 else position.row >= 5

        val forwardPos = Position(position.row + forwardDir, position.col)
        if (forwardPos.isValid()) {
            val target = board.getPiece(forwardPos)
            if (target == null || target.color != color) {
                moves.add(Move(position, forwardPos, this, target))
            }
        }

        if (hasCrossedRiver) {
            for (colDir in listOf(-1, 1)) {
                val sidePos = Position(position.row, position.col + colDir)
                if (sidePos.isValid()) {
                    val target = board.getPiece(sidePos)
                    if (target == null || target.color != color) {
                        moves.add(Move(position, sidePos, this, target))
                    }
                }
            }
        }
    }

    fun copy(newPosition: Position = position): Piece {
        return Piece(type, color, newPosition)
    }
}
