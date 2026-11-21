package daidaixiangqi.app

import daidaixiangqi.app.model.PieceColor
import java.util.Locale

class GameClock(private val now: () -> Long) {
    private var redMs = 0L
    private var blackMs = 0L

    private var since = now()

    var turn: PieceColor? = null
        private set

    var paused = false
        private set

    val running: PieceColor? get() = if (paused) null else turn

    fun elapsed(side: PieceColor): Long {
        val banked = if (side == PieceColor.RED) redMs else blackMs
        return if (running == side) banked + (now() - since).coerceAtLeast(0L) else banked
    }

    fun reset(redMs: Long = 0L, blackMs: Long = 0L, turn: PieceColor? = null) {
        this.redMs = redMs.coerceAtLeast(0L)
        this.blackMs = blackMs.coerceAtLeast(0L)
        this.turn = turn
        since = now()
    }

    fun setTurn(side: PieceColor?) {
        if (side == turn) return
        bank()
        turn = side
    }

    fun setPaused(paused: Boolean) {
        if (paused == this.paused) return
        bank()
        this.paused = paused
    }

    private fun bank() {
        val t = now()
        when (running) {
            PieceColor.RED -> redMs += (t - since).coerceAtLeast(0L)
            PieceColor.BLACK -> blackMs += (t - since).coerceAtLeast(0L)
            null -> {}
        }
        since = t
    }

    companion object {

        fun format(ms: Long): String {
            val totalSeconds = ms.coerceAtLeast(0L) / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            else String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
