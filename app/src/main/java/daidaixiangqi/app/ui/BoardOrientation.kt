package daidaixiangqi.app.ui

import daidaixiangqi.app.model.Board
import daidaixiangqi.app.model.Position

object BoardOrientation {

    fun toScreen(pos: Position, flipped: Boolean): Position =
        if (flipped) Position(Board.ROWS - 1 - pos.row, Board.COLS - 1 - pos.col) else pos

    fun fromScreen(row: Int, col: Int, flipped: Boolean): Position =
        toScreen(Position(row, col), flipped)
}
