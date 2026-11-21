package daidaixiangqi.app.model

data class Position(val row: Int, val col: Int) {
    fun isValid(): Boolean = row in 0..9 && col in 0..8

    override fun toString(): String = "($row,$col)"
}
