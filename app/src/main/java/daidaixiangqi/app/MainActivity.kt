package daidaixiangqi.app

import android.app.Dialog
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ScrollView
import android.widget.TextView
import daidaixiangqi.app.audio.OfflineTtsSpeaker
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import daidaixiangqi.app.GameController.AIDifficulty
import daidaixiangqi.app.GameController.GameMode
import daidaixiangqi.app.model.Piece
import daidaixiangqi.app.model.PieceColor
import daidaixiangqi.app.model.MatingPattern
import daidaixiangqi.app.model.Move
import daidaixiangqi.app.model.MoveRules
import daidaixiangqi.app.model.MoveViolation
import daidaixiangqi.app.ui.BoardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val appTypeface: Typeface by lazy { Typeface.SERIF }

    /**
     * 全界面字体统一入口：把一棵视图树里所有 TextView / Button / 菜单项 / 对话框正文
     * 全部换成楷体，保留各自原有的粗细（粗细只是强调层级，字族必须只有一个）。
     */
    private fun unifyTypeface(view: View?) {
        when (view) {
            null -> return
            is TextView -> {
                val style = view.typeface?.style ?: Typeface.NORMAL
                view.typeface = Typeface.create(appTypeface, style)
            }
            is ViewGroup -> for (i in 0 until view.childCount) unifyTypeface(view.getChildAt(i))
        }
    }

    /** 对话框的内容在显示时才 inflate，因此把统一动作挂到 onShow 上，确保正文、标题、按钮、列表项全覆盖。 */
    private fun AlertDialog.Builder.showUnified(): AlertDialog {
        val dialog = create()
        dialog.setOnShowListener { d -> unifyTypeface((d as? Dialog)?.window?.decorView) }
        dialog.show()
        return dialog
    }

    companion object {

        private const val CLOCK_REFRESH_MS = 200L

        private const val MODE_PREFS = "chess_mode_prefs"
        private const val KEY_MODE_SELECTED = "mode_selected"
        private const val KEY_GAME_MODE = "game_mode"
        private const val KEY_AI_COLOR = "ai_color"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_ACK_COMPLETED = "about_acknowledged"
        private const val KEY_FLIP_TEXT_BY_SIDE = "flip_text_by_side"

        private const val CONTACT_QQ = "3121013269"
        private const val CONTACT_QQ_EMAIL = "3121013269@qq.com"
        private const val CONTACT_GMAIL = "xuqian701@gmail.com"
        private const val CONTACT_HELLOTALK = "@XuQian20040430"
    }

    private lateinit var boardView: BoardView

    private lateinit var redCard: View
    private lateinit var blackCard: View
    private lateinit var redTurnDot: View
    private lateinit var blackTurnDot: View
    private lateinit var redRoleText: TextView
    private lateinit var blackRoleText: TextView
    private lateinit var redCapturedLayout: LinearLayout
    private lateinit var blackCapturedLayout: LinearLayout
    private lateinit var redClockText: TextView
    private lateinit var blackClockText: TextView
    private lateinit var moveCountText: TextView
    private lateinit var gameModeText: TextView

    private lateinit var statusText: TextView
    private lateinit var aiThinkingIndicator: LinearLayout
    private lateinit var thinkingDot1: View
    private lateinit var thinkingDot2: View
    private lateinit var thinkingDot3: View

    private lateinit var moveHistoryList: LinearLayout
    private lateinit var moveHistoryScroll: ScrollView
    private lateinit var moveStrip: View
    private lateinit var hintButton: MaterialButton
    private lateinit var undoButton: MaterialButton
    private lateinit var newGameButton: MaterialButton
    private lateinit var muteButton: ImageButton
    private lateinit var speakButton: ImageButton
    private lateinit var flipTextButton: ImageButton

    private lateinit var gameController: GameController
    private lateinit var audioManager: daidaixiangqi.app.audio.GameAudioManager
    private lateinit var ttsSpeaker: daidaixiangqi.app.audio.OfflineTtsSpeaker
    private var thinkingAnimator: AnimatorSet? = null
    private var aiThinking = false
    private var modeSelected = false

    private var globalMuted = false
    private var savedSoundMuted = false
    private var savedSpeakEnabled = true

    private val modePrefs by lazy { getSharedPreferences(MODE_PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        audioManager = daidaixiangqi.app.audio.GameAudioManager(this)
        ttsSpeaker = daidaixiangqi.app.audio.OfflineTtsSpeaker(this)
        gameController = GameController(this, loadSavedDifficulty(), audioManager)
        initViews()
        setupGameControllerCallbacks()
        startClockUpdates()

        // 主界面所有文字（含四个 MaterialButton）统一一次，杜绝库自带字体混入。
        unifyTypeface(window.decorView)

        if (!restoreSavedSelection()) {
            modeSelected = false
            showAboutDialog { showNewGameDialog() }
        }
    }

    private fun loadSavedDifficulty(): AIDifficulty {
        val name = modePrefs.getString(KEY_DIFFICULTY, null) ?: return AIDifficulty.PROFESSIONAL
        return runCatching { AIDifficulty.valueOf(name) }.getOrDefault(AIDifficulty.PROFESSIONAL)
    }

    private fun restoreSavedSelection(): Boolean {
        if (!modePrefs.getBoolean(KEY_MODE_SELECTED, false)) return false
        val mode = runCatching {
            GameMode.valueOf(modePrefs.getString(KEY_GAME_MODE, "") ?: "")
        }.getOrNull() ?: return false
        val aiColor = if (mode == GameMode.PLAYER_VS_AI) {
            runCatching {
                PieceColor.valueOf(modePrefs.getString(KEY_AI_COLOR, "") ?: "")
            }.getOrNull() ?: PieceColor.BLACK
        } else {
            PieceColor.BLACK
        }
        gameController.setGameMode(mode, aiColor)
        gameController.startNewGame()
        updateGameModeDisplay()
        modeSelected = true
        return true
    }

    private fun saveSelection(mode: GameMode, aiColor: PieceColor?, difficulty: AIDifficulty) {
        modePrefs.edit()
            .putBoolean(KEY_MODE_SELECTED, true)
            .putString(KEY_GAME_MODE, mode.name)
            .putString(KEY_AI_COLOR, aiColor?.name)
            .putString(KEY_DIFFICULTY, difficulty.name)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        gameController.setInForeground(true)
    }

    override fun onPause() {
        super.onPause()

        gameController.setInForeground(false)
    }

    private fun updateSystemBarAppearance() {

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
    }

    private fun initViews() {
        boardView = findViewById(R.id.boardView)
        redCard = findViewById(R.id.redCard)
        blackCard = findViewById(R.id.blackCard)
        redTurnDot = findViewById(R.id.redTurnDot)
        blackTurnDot = findViewById(R.id.blackTurnDot)
        redRoleText = findViewById(R.id.redRoleText)
        blackRoleText = findViewById(R.id.blackRoleText)
        redCapturedLayout = findViewById(R.id.redCapturedPieces)
        blackCapturedLayout = findViewById(R.id.blackCapturedPieces)
        redClockText = findViewById(R.id.redClockText)
        blackClockText = findViewById(R.id.blackClockText)
        moveCountText = findViewById(R.id.moveCountText)
        gameModeText = findViewById(R.id.gameModeText)
        statusText = findViewById(R.id.statusText)
        aiThinkingIndicator = findViewById(R.id.aiThinkingIndicator)
        thinkingDot1 = findViewById(R.id.thinkingDot1)
        thinkingDot2 = findViewById(R.id.thinkingDot2)
        thinkingDot3 = findViewById(R.id.thinkingDot3)
        moveHistoryList = findViewById(R.id.moveHistoryList)
        moveHistoryScroll = findViewById(R.id.moveHistoryScroll)
        moveStrip = findViewById(R.id.moveStrip)
        moveStrip.setOnClickListener { showFullHistory() }
        hintButton = findViewById(R.id.hintButton)
        undoButton = findViewById(R.id.undoButton)
        newGameButton = findViewById(R.id.newGameButton)
        muteButton = findViewById(R.id.muteButton)
        speakButton = findViewById(R.id.speakButton)
        flipTextButton = findViewById(R.id.flipTextButton)
        flipTextButton.setOnClickListener { toggleFlipText() }
        updateFlipTextButton()
        val aboutButton = findViewById<View>(R.id.aboutButton)
        aboutButton.setOnClickListener { showAboutDialog() }

        newGameButton.setOnClickListener { confirmAbandonThen { showNewGameDialog() } }
        muteButton.setOnClickListener { toggleGlobalMute() }
        speakButton.setOnClickListener { toggleSpeak() }
        updateMuteIcon()
        updateSpeakIcon()

        undoButton.setOnClickListener {
            if (!modeSelected) {
                toast(R.string.mode_not_selected)
                return@setOnClickListener
            }
            if (gameController.getMoveHistory().isEmpty()) {
                toast(R.string.undo_none)
                return@setOnClickListener
            }
            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle(R.string.undo_confirm_title)
                .setMessage(R.string.undo_confirm_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (gameController.undoLastMove()) {
                        boardView.clearSelection()
                        boardView.highlightMove(gameController.getMoveHistory().lastOrNull())
                        toast(R.string.undo_done)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .showUnified()
        }

        hintButton.setOnClickListener {
            if (!modeSelected) {
                toast(R.string.mode_not_selected)
                return@setOnClickListener
            }
            if (gameController.getGameMode() == GameMode.PLAYER_VS_PLAYER) {

                boardView.flipped = !boardView.flipped
                return@setOnClickListener
            }
            if (!gameController.isPlayerTurn()) {
                toast(R.string.not_your_turn)
                return@setOnClickListener
            }
            gameController.getHint { move ->
                runOnUiThread {
                    if (move != null) {
                        boardView.highlightMove(move)
                        val text = notation(move, gameController.getCurrentBoard())
                        val snack = Snackbar.make(boardView, getString(R.string.hint_suggest, text), 4000)
                        snack.show()
                        unifyTypeface(snack.view)
                    } else {
                        val snack = Snackbar.make(boardView, R.string.hint_unavailable, Snackbar.LENGTH_SHORT)
                        snack.show()
                        unifyTypeface(snack.view)
                    }
                }
            }
        }

        moveCountText.text = getString(R.string.round_label, 0)
        updateClocks()
    }

    private fun setupGameControllerCallbacks() {
        gameController.onBoardUpdated = { board ->
            runOnUiThread {
                boardView.setBoard(board)
                updateStatus()

                if (gameController.getMoveHistory().isEmpty()) {
                    boardView.highlightMove(null)
                }
            }
        }

        gameController.onGameOver = { result ->
            runOnUiThread {
                showGameOver(result)
                val history = gameController.getMoveHistory()
                val lastMove = history.lastOrNull()
                val boardSnapshot = gameController.getCurrentBoard().copy()
                val initial = gameController.getInitialBoard()
                // 杀招识别与整盘复盘记谱移到后台，避免主线程重算拖住终局语音入队。
                lifecycleScope.launch(Dispatchers.Default) {
                    val pattern = if (result is GameController.GameResult.Checkmate) {
                        daidaixiangqi.app.model.MatingPattern.detect(
                            boardSnapshot, lastMove, result.winner
                        )
                    } else {
                        null
                    }
                    val notations = MoveNotation.formatAll(history, initial)
                    val notation = notations.lastOrNull()?.takeIf { it.isNotBlank() }
                    ttsSpeaker.announceStatus(
                        OfflineTtsSpeaker.statusAnnouncement(result, notation, pattern)
                    )
                }
            }
        }

        gameController.onAIThinking = { isThinking ->
            aiThinking = isThinking
            runOnUiThread {
                aiThinkingIndicator.visibility = if (isThinking) View.VISIBLE else View.GONE
                if (isThinking) startThinkingAnimation() else stopThinkingAnimation()
                undoButton.isEnabled = !isThinking
                refreshPrimaryButton()
                statusText.text = if (isThinking) getString(R.string.ai_thinking) else getStatusText()
            }
        }

        gameController.onMoveCompleted = { move, before ->
            // 当前盘拷一份快照交给合成线程跨线程读取；构文在合成线程内完成，回调线程即刻提交任务。
            val current = gameController.getCurrentBoard().copy()
            // 先把播报任务提交到合成线程，再排 UI 高亮/震动，避免界面重活晚于入队拖住首块出声。
            ttsSpeaker.announceMove(move, before, current)
            boardView.post {
                boardView.highlightMove(move)
                boardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        gameController.onStatsUpdated = { stats ->
            runOnUiThread { updateGameStats(stats) }
        }

        gameController.onMoveAnimationRequested = { move, preBoard ->
            runOnUiThread {
                boardView.animateMove(move, preBoard) {
                    boardView.setBoard(gameController.getCurrentBoard())
                }
            }
        }

        boardView.setOnMoveListener { move ->
            if (!modeSelected) {
                boardView.clearSelection()
                return@setOnMoveListener
            }
            if (!gameController.isPlayerTurn()) {
                toast(R.string.not_your_turn)
                boardView.clearSelection()
                return@setOnMoveListener
            }
            if (gameController.makePlayerMove(move)) {
                boardView.clearSelection()
            } else {
                // 走法本身不合规时说明具体原因；合规但走完被将军则按送将语音提示。
                val snapshot = gameController.getCurrentBoard()
                // 走子前是否已处于被将军：决定念「保护将军」还是「不可送将」。
                val alreadyInCheck = snapshot.isInCheck(snapshot.currentPlayer)
                val violation = MoveRules.violation(snapshot, move.piece, move.to)
                if (alreadyInCheck) {
                    // 本来就被将军还在走别的着法：只念保护将军，不念送将。
                    ttsSpeaker.announceStatus(getString(R.string.check_protect_general_speech))
                    if (violation != null) toast(violationMessage(violation), snapshot.currentPlayer)
                } else if (violation != null) {
                    toast(violationMessage(violation), snapshot.currentPlayer)
                } else if (leavesSelfInCheck(move)) {
                    // 本来没被将军，这一步走完反被将军：送将，不可走。
                    ttsSpeaker.announceStatus(getString(R.string.suicide_check_speech))
                } else {
                    toast(R.string.illegal_move, snapshot.currentPlayer)
                }
                boardView.clearSelection()
            }
        }
        boardView.setOnPieceSelectedListener {
            if (modeSelected) gameController.onPlayerSelectedPiece()
        }
        boardView.setOnBlockedSelectionListener {
            if (modeSelected && gameController.isPlayerTurn()) {
                val current = gameController.getCurrentBoard()
                if (current.isInCheck(current.currentPlayer)) {
                    toast(R.string.check_must_reply, current.currentPlayer)
                    // 老将被将军时去动别的棋子，这步解不了将：语音直接提醒先保将。
                    ttsSpeaker.announceStatus(getString(R.string.check_protect_general_speech))
                }
            }
        }
        boardView.setOnIllegalTargetListener { from, to ->
            if (modeSelected && gameController.isPlayerTurn()) {
                val board = gameController.getCurrentBoard()
                val piece = board.getPiece(from)
                if (piece != null) {
                    // 落子前是否已处于被将军：决定念「保护将军」还是「不可送将」。
                    val alreadyInCheck = board.isInCheck(board.currentPlayer)
                    val violation = MoveRules.violation(board, piece, to)
                    if (violation != null) {
                        // 走法本身不合规：文字说明是塞象眼、别马腿还是别的，不执行走子。
                        toast(violationMessage(violation), board.currentPlayer)
                        if (alreadyInCheck) {
                            // 被将军时还在乱走别的着法：只念保护将军。
                            ttsSpeaker.announceStatus(getString(R.string.check_protect_general_speech))
                        }
                    } else if (alreadyInCheck) {
                        // 本来就被将军，这步同样解不了将：只念保护将军，不念送将。
                        ttsSpeaker.announceStatus(getString(R.string.check_protect_general_speech))
                    } else {
                        // 本来没被将军，走完会不会反被将军：是则送将不可走。
                        val probe = board.makeMove(Move(from, to, piece, board.getPiece(to)))
                        if (probe.isInCheck(board.currentPlayer)) {
                            ttsSpeaker.announceStatus(getString(R.string.suicide_check_speech))
                        } else {
                            toast(R.string.illegal_move, board.currentPlayer)
                        }
                    }
                }
            }
        }
        boardView.moveLabelProvider = { move -> shortNotation(move) }

        updateGameModeDisplay()
    }

    private fun sideName(color: PieceColor): String =
        getString(if (color == PieceColor.RED) R.string.red_side else R.string.black_side)

    private fun updateStatus() {
        val board = gameController.getCurrentBoard()
        val snap = board.statusSnapshot()
        val checkmate = snap.checkmate
        val stalemate = snap.stalemate
        val over = checkmate || stalemate
        val checked = snap.inCheck
        statusText.text = statusText(board, checkmate, stalemate, checked)
        val redActive = !over && board.currentPlayer == PieceColor.RED
        val blackActive = !over && board.currentPlayer == PieceColor.BLACK
        redCard.setBackgroundResource(if (redActive) R.drawable.player_card_active else R.drawable.player_card)
        blackCard.setBackgroundResource(if (blackActive) R.drawable.player_card_active else R.drawable.player_card)
        redTurnDot.visibility = if (redActive) View.VISIBLE else View.INVISIBLE
        blackTurnDot.visibility = if (blackActive) View.VISIBLE else View.INVISIBLE
        updateClocks()
    }

    /** 试走预判：这步棋走完后，走子方是否反而被将军（送将）。只在副本上推演，不改动当前盘面。 */
    private fun leavesSelfInCheck(move: Move): Boolean {
        val cur = gameController.getCurrentBoard()
        val test = cur.makeMove(move)
        return test.isInCheck(cur.currentPlayer)
    }

    /** 把走法违规类型映射为上屏文案。 */
    private fun violationMessage(violation: MoveViolation): Int = when (violation) {
        MoveViolation.TARGET_OWN_PIECE -> R.string.illegal_target_own
        MoveViolation.HORSE_SHAPE -> R.string.illegal_horse_shape
        MoveViolation.HORSE_LEG_BLOCKED -> R.string.illegal_horse_leg
        MoveViolation.ELEPHANT_SHAPE -> R.string.illegal_elephant_shape
        MoveViolation.ELEPHANT_EYE_BLOCKED -> R.string.illegal_elephant_eye
        MoveViolation.ELEPHANT_RIVER -> R.string.illegal_elephant_river
        MoveViolation.CHARIOT_SHAPE -> R.string.illegal_chariot_shape
        MoveViolation.CHARIOT_BLOCKED -> R.string.illegal_chariot_blocked
        MoveViolation.CANNON_SHAPE -> R.string.illegal_cannon_shape
        MoveViolation.CANNON_BLOCKED -> R.string.illegal_cannon_blocked
        MoveViolation.CANNON_SCREEN_MISSING -> R.string.illegal_cannon_no_screen
        MoveViolation.CANNON_SCREEN_EXTRA -> R.string.illegal_cannon_extra_screen
        MoveViolation.ADVISOR_SHAPE -> R.string.illegal_advisor_shape
        MoveViolation.ADVISOR_PALACE -> R.string.illegal_advisor_palace
        MoveViolation.GENERAL_SHAPE -> R.string.illegal_general_shape
        MoveViolation.GENERAL_PALACE -> R.string.illegal_general_palace
        MoveViolation.SOLDIER_SHAPE -> R.string.illegal_soldier_shape
        MoveViolation.SOLDIER_BACKWARD -> R.string.illegal_soldier_backward
        MoveViolation.SOLDIER_SIDE -> R.string.illegal_soldier_side
    }

    private fun updateClocks() {
        val running = gameController.clockTurn()
        showClock(redClockText, gameController.clockTime(PieceColor.RED), running == PieceColor.RED)
        showClock(blackClockText, gameController.clockTime(PieceColor.BLACK), running == PieceColor.BLACK)
    }

    private fun showClock(view: TextView, ms: Long, running: Boolean) {
        val text = GameClock.format(ms)
        if (view.text.toString() != text) view.text = text
        if (view.isActivated != running) {
            view.isActivated = running
            view.setTypeface(appTypeface, if (running) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun statusText(
        board: daidaixiangqi.app.model.Board,
        checkmate: Boolean,
        stalemate: Boolean,
        checked: Boolean
    ): String {
        val side = sideName(board.currentPlayer)
        return when {
            checkmate -> getString(R.string.wins, sideName(board.currentPlayer.opposite()))
            stalemate -> getString(R.string.stalemate_win_short, sideName(board.currentPlayer.opposite()))
            checked -> getString(R.string.in_check, side)
            else -> getString(R.string.side_to_move, side)
        }
    }

    private fun getStatusText(): String {
        val board = gameController.getCurrentBoard()
        val snap = board.statusSnapshot()
        return statusText(board, snap.checkmate, snap.stalemate, snap.inCheck)
    }

    private fun updateGameStats(stats: GameController.GameStats) {
        updateClocks()

        moveCountText.text = getString(R.string.round_label, (stats.moveNumber + 1) / 2)
        updateMoveHistory()

        updateCapturedRow(redCapturedLayout, stats.redCapturedPieces)
        updateCapturedRow(blackCapturedLayout, stats.blackCapturedPieces)
    }

    private fun updateCapturedRow(container: LinearLayout, pieces: List<Piece>) {
        container.removeAllViews()
        if (pieces.isEmpty()) return
        val summary = pieces.groupBy { it.type }.entries.sortedByDescending { it.key.baseValue }
            .joinToString("  ") { (type, group) ->
                type.getDisplayName(group.first().color) + if (group.size > 1) "×${group.size}" else ""
            }
        container.addView(TextView(this).apply {
            text = summary
            typeface = appTypeface

            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (pieces.first().color == PieceColor.RED) R.color.chess_red_side else R.color.chess_black_side
                )
            )
            gravity = (if (container.id == R.id.blackCapturedPieces) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL

            maxLines = 1
            includeFontPadding = false
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.captured_row_height)
            )
        })
    }

    private fun startThinkingAnimation() {
        val dots = listOf(thinkingDot1, thinkingDot2, thinkingDot3)
        val animators = dots.mapIndexed { index, dot ->
            ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f, 0.3f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                startDelay = index * 200L
            }
        }
        thinkingAnimator = AnimatorSet().apply {
            playTogether(animators.map { it as android.animation.Animator })
            start()
        }
    }

    private fun stopThinkingAnimation() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
    }

    private fun updateMoveHistory() {
        val moves = gameController.getMoveHistory()
        moveHistoryList.removeAllViews()
        if (moves.isEmpty()) {
            moveHistoryList.addView(emptyHistoryView())
            return
        }
        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard())
        val dp = resources.displayMetrics.density
        var index = 0
        while (index < moves.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            row.addView(moveCell(index, notations[index], PieceColor.RED))
            val blackIndex = index + 1
            if (blackIndex < moves.size) {
                row.addView(moveCell(blackIndex, notations[blackIndex], PieceColor.BLACK))
            } else {

                row.addView(TextView(this))
            }
            moveHistoryList.addView(row)
            index += 2
        }
        moveHistoryScroll.post { moveHistoryScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun moveCell(index: Int, notation: String, side: PieceColor): TextView {
        val sb = SpannableStringBuilder()
        val num = "${index + 1}. "
        sb.append(num)
        sb.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.chess_history_text)),
            0, num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val notationStart = sb.length
        sb.append(notation)
        val notationColor = if (side == PieceColor.RED) R.color.chess_piece_red_ink else R.color.chess_move_black
        sb.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, notationColor)),
            notationStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return TextView(this).apply {
            text = sb
            typeface = appTypeface
            textSize = 16f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun emptyHistoryView(): TextView = TextView(this).apply {
        text = getString(R.string.history_empty)
        typeface = appTypeface
        textSize = 16f
        gravity = Gravity.START
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_history_text))
    }

    private fun fullHistoryText(): String {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) return getString(R.string.history_empty)
        val history = StringBuilder()
        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard())
        moves.forEachIndexed { index, _ ->
            val num = index + 1
            if (index % 2 == 0) {
                history.append(String.format(Locale.US, "%2d. %s", num, notations[index]))
            } else {
                history.append("    ").append(String.format(Locale.US, "%d. %s", num, notations[index])).append('\n')
            }
        }
        return history.toString().trimEnd()
    }

    private fun showFullHistory() {
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            text = fullHistoryText()
            textSize = 15f
            typeface = appTypeface
            setLineSpacing(4 * dp, 1f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_history_text))
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(text) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.history_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .showUnified()
    }

    private fun startClockUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    updateClocks()
                    delay(CLOCK_REFRESH_MS)
                }
            }
        }
    }

    private fun difficultyShortName(): String =
        resources.getStringArray(R.array.difficulty_short)[gameController.getDifficulty().ordinal]

    private fun modeCaption(): String = when (gameController.getGameMode()) {
        GameMode.PLAYER_VS_PLAYER -> getString(R.string.mode_pvp)
        GameMode.PLAYER_VS_AI -> getString(R.string.mode_pvai, difficultyShortName())
    }

    private fun updateGameModeDisplay() {
        findViewById<TextView>(R.id.screenTitle).setText(R.string.jade_game_title)
        gameModeText.text = modeCaption()
        val (redRole, blackRole) = when (gameController.getGameMode()) {
            GameMode.PLAYER_VS_PLAYER -> R.string.role_player to R.string.role_player
            GameMode.PLAYER_VS_AI ->
                if (gameController.getAIColor() == PieceColor.RED) R.string.role_ai to R.string.role_player
                else R.string.role_player to R.string.role_ai
        }
        redRoleText.setText(redRole)
        blackRoleText.setText(blackRole)

        // 仅人人对战可切换文字朝向，选择记入偏好；人机对战固定统一正向，按钮不可用。
        val pvpMode = gameController.getGameMode() == GameMode.PLAYER_VS_PLAYER
        boardView.flipTextBySide = if (pvpMode) modePrefs.getBoolean(KEY_FLIP_TEXT_BY_SIDE, true) else false

        if (gameController.getGameMode() == GameMode.PLAYER_VS_AI) {
            boardView.flipped = gameController.getAIColor() == PieceColor.RED
        }
        refreshPrimaryButton()
        updateFlipTextButton()
        updateStatus()
    }

    private fun refreshPrimaryButton() {
        val pvp = gameController.getGameMode() == GameMode.PLAYER_VS_PLAYER
        if (pvp) {
            hintButton.setText(R.string.flip)
            hintButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_flip)
        } else {
            hintButton.setText(R.string.hint)
            hintButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_hint)
        }
        hintButton.isEnabled = when {
            pvp -> true
            else -> !aiThinking
        }
    }

    /** 切换棋子文字的翻转行为：随翻转（双方文字各朝自己）或固定正向；默认随翻转。 */
    private fun toggleFlipText() {
        if (!flipTextButton.isEnabled) return
        val next = !boardView.flipTextBySide
        boardView.flipTextBySide = next
        modePrefs.edit().putBoolean(KEY_FLIP_TEXT_BY_SIDE, next).apply()
        updateFlipTextButton()
        toast(if (next) R.string.flip_text_on_toast else R.string.flip_text_off_toast)
    }

    private fun updateFlipTextButton() {
        val pvp = gameController.getGameMode() == GameMode.PLAYER_VS_PLAYER
        flipTextButton.isEnabled = pvp
        flipTextButton.visibility = if (pvp) View.VISIBLE else View.GONE
        val on = boardView.flipTextBySide
        flipTextButton.alpha = if (on) 1f else 0.45f
        flipTextButton.contentDescription = getString(if (on) R.string.flip_text_on else R.string.flip_text_off)
    }

    private fun showGameOver(result: GameController.GameResult) {
        val headline = when (result) {
            is GameController.GameResult.Checkmate -> getString(R.string.wins, sideName(result.winner))
            is GameController.GameResult.Stalemate -> getString(R.string.stalemate_win, sideName(result.winner))
            is GameController.GameResult.PerpetualCheck -> getString(R.string.perpetual_check_loss, sideName(result.winner))
            is GameController.GameResult.PerpetualChase -> getString(R.string.perpetual_chase_loss, sideName(result.winner))
            GameController.GameResult.RepetitionDraw -> getString(R.string.repetition_draw)
        }

        val builder = AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.game_over)
            .setMessage(headline)
            .setPositiveButton(R.string.play_again) { _, _ ->
                gameController.startNewGame()
                updateGameModeDisplay()
            }
        builder.setNeutralButton(R.string.close, null)
        builder.showUnified()
    }

    private fun gameInProgress(): Boolean {
        return gameController.getMoveHistory().isNotEmpty() && !gameController.isGameOver()
    }

    private fun confirmAbandonThen(action: () -> Unit) {
        if (!gameInProgress()) {
            action()
            return
        }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.abandon_title)
            .setMessage(R.string.abandon_message)
            .setPositiveButton(R.string.abandon_confirm) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .showUnified()
    }

    private fun showNewGameDialog() {
        val modes = arrayOf(
            getString(R.string.play_red_vs_ai),
            getString(R.string.play_black_vs_ai),
            getString(R.string.two_players)
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.mode_select_title)
            .setCancelable(false)
            .setAdapter(styledListAdapter(modes)) { _, which ->
                when (which) {
                    0 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_AI, PieceColor.BLACK)
                        showDifficultyDialog()
                    }
                    1 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_AI, PieceColor.RED)
                        showDifficultyDialog()
                    }
                    2 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_PLAYER)
                        gameController.startNewGame()
                        updateGameModeDisplay()
                        modeSelected = true
                        saveSelection(GameMode.PLAYER_VS_PLAYER, null, gameController.getDifficulty())
                    }
                }
            }
            .showUnified()
    }

    private fun notation(move: daidaixiangqi.app.model.Move, boardBefore: daidaixiangqi.app.model.Board): String =
        MoveNotation.format(move, boardBefore)

    private fun shortNotation(move: daidaixiangqi.app.model.Move): String =
        MoveNotation.formatShort(move)

    private fun showDifficultyDialog() {
        val names = resources.getStringArray(R.array.difficulty_short)
        val notes = resources.getStringArray(R.array.difficulty_notes)
        val items = names.indices.map { i ->
            SpannableStringBuilder(names[i]).apply {
                append("\n")
                val start = length
                append(notes[i])
                setSpan(RelativeSizeSpan(0.8f), start, length, 0)
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary)),
                    start, length, 0
                )
            }
        }.toTypedArray<CharSequence>()
        var chosen = gameController.getDifficulty().ordinal
        val currentMode = gameController.getGameMode()
        val currentAIColor = gameController.getAIColor()

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.difficulty_title)
            .setSingleChoiceItems(items, chosen) { _, which -> chosen = which }
            .setPositiveButton(R.string.ok) { _, _ ->
                val difficulty = AIDifficulty.values()[chosen]

                gameController.destroy()
                gameController = GameController(this@MainActivity, difficulty, audioManager)
                setupGameControllerCallbacks()

                gameController.setGameMode(currentMode, currentAIColor)
                gameController.startNewGame()
                updateGameModeDisplay()
                modeSelected = true
                saveSelection(currentMode, currentAIColor, difficulty)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (!modeSelected) showNewGameDialog()
            }
            .showUnified()
    }

    private fun styledListAdapter(items: Array<String>): ListAdapter {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text))
                    textSize = 16f
                    typeface = appTypeface
                    setBackgroundColor(Color.TRANSPARENT)
                }
                return view
            }
        }
    }

    /** 全局总静音：一键同时关闭/恢复落子音效与语音播报。 */
    private fun toggleGlobalMute() {
        if (!globalMuted) {
            savedSoundMuted = audioManager.isMuted()
            savedSpeakEnabled = ttsSpeaker.isEnabled()
            audioManager.setMuted(true)
            ttsSpeaker.setEnabled(false)
            globalMuted = true
            toast(R.string.sound_muted)
        } else {
            audioManager.setMuted(savedSoundMuted)
            ttsSpeaker.setEnabled(savedSpeakEnabled)
            globalMuted = false
            toast(R.string.sound_unmuted_toast)
        }
        updateMuteIcon()
        updateSpeakIcon()
    }

    /** 单独切换语音播报；全局总静音中不允许单独开播报，弹窗提示先开总开关。 */
    private fun toggleSpeak() {
        val target = !ttsSpeaker.isEnabled()
        if (target && globalMuted) {
            showGlobalMutedBlockedDialog()
            return
        }
        ttsSpeaker.setEnabled(target)
        toast(if (target) R.string.speak_on_toast else R.string.speak_off_toast)
        updateMuteIcon()
        updateSpeakIcon()
    }

    /** 全局总静音下，分项声音（语音播报）被拦截时的统一提示。 */
    private fun showGlobalMutedBlockedDialog() {
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setMessage(R.string.sound_global_off_hint)
            .setPositiveButton(R.string.ok, null)
            .showUnified()
    }

    private fun updateMuteIcon() {
        muteButton.setImageResource(if (globalMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        muteButton.contentDescription = getString(if (globalMuted) R.string.sound_unmute else R.string.sound_mute)
    }

    private fun updateSpeakIcon() {
        val enabled = ttsSpeaker.isEnabled()
        speakButton.setImageResource(if (enabled) R.drawable.ic_speak_on else R.drawable.ic_speak_off)
        speakButton.contentDescription = getString(if (enabled) R.string.speak_on else R.string.speak_off)
    }

    private fun showAboutDialog(onClose: (() -> Unit)? = null) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val letterTypeface = appTypeface
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.about_title)
            textSize = 20f
            typeface = appTypeface
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_dialog_title))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = pad / 3 }
        }
        container.addView(titleView)

        val subtitle = TextView(this).apply {
            text = getString(R.string.about_subtitle)
            textSize = 13f
            typeface = appTypeface
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = pad / 2 }
        }
        container.addView(subtitle)

        val aboutBody = getString(R.string.about_body)
        aboutBody.split("\n").forEachIndexed { index, raw ->
            val seg = raw.trimStart('\u3000').trim()
            if (seg.isEmpty()) return@forEachIndexed
            val isSalutation = index == 0
            val isHeading = seg.matches(Regex("^[一二三四五六七八九十]+、"))
            val para = TextView(this).apply {
                val content = if (isSalutation) seg else "\u3000\u3000$seg"
                text = if (isHeading) {
                    SpannableStringBuilder(content).apply {
                        setSpan(StyleSpan(Typeface.BOLD), 0, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else content
                textSize = 15f
                typeface = letterTypeface
                setLineSpacing(0f, 1.1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (isHeading && index != 0) pad / 3 else 0
                    bottomMargin = if (isHeading) pad / 4 else pad / 2
                }
            }
            container.addView(para)
        }

        val signature = TextView(this).apply {
            text = getString(R.string.about_signature)
            textSize = 15f
            typeface = letterTypeface
            gravity = Gravity.END
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
        }
        container.addView(signature)

        val signDate = TextView(this).apply {
            text = getString(R.string.about_sign_date)
            textSize = 15f
            typeface = letterTypeface
            gravity = Gravity.END
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 4 }
        }
        container.addView(signDate)

        val contactHeader = TextView(this).apply {
            text = getString(R.string.contact_section)
            textSize = 15f
            typeface = letterTypeface
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (pad * 0.75f).toInt() }
        }
        container.addView(contactHeader)
        container.addView(contactRow(R.string.contact_qq_label, CONTACT_QQ, pad))
        container.addView(contactRow(R.string.contact_qq_email_label, CONTACT_QQ_EMAIL, pad))
        container.addView(contactRow(R.string.contact_gmail_label, CONTACT_GMAIL, pad))
        container.addView(contactRow(R.string.contact_hellotalk_label, CONTACT_HELLOTALK, pad))

        // 纯净源码版：联系方式二维码图片属二进制资源，已移除二维码展示与保存按钮；上方联系方式文本保留。
        val disclaimerText = getString(R.string.about_disclaimer)
        val copyrightStart = disclaimerText.indexOf("本应用程序代码")
        val copyrightEndMark = "改编或商用。"
        val copyrightEnd = disclaimerText.indexOf(copyrightEndMark) + copyrightEndMark.length
        val disclaimerSpannable = SpannableStringBuilder(disclaimerText).apply {
            if (copyrightStart in 0 until copyrightEnd) {
                setSpan(StyleSpan(Typeface.BOLD), copyrightStart, copyrightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val disclaimer = TextView(this).apply {
            text = disclaimerSpannable
            textSize = 14f
            typeface = appTypeface
            setTextColor(Color.BLACK)
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad }
        }
        container.addView(disclaimer)

        val scroll = ScrollView(this).apply { addView(container) }

        val ackButton = MaterialButton(this).apply {
            text = getString(R.string.about_acknowledge)
            textSize = 15f
            typeface = appTypeface
            isAllCaps = false
        }
        val disabledBg = ContextCompat.getColor(this, R.color.chess_button_bg)
        val disabledText = ContextCompat.getColor(this, R.color.chess_text_secondary)
        val enabledBg = ContextCompat.getColor(this, R.color.chess_accent)
        val enabledText = ContextCompat.getColor(this, R.color.chess_on_accent)

        val acknowledged = modePrefs.getBoolean(KEY_ACK_COMPLETED, false)
        val baseMinDelayMs = 45000L
        var effectiveMinDelayMs = baseMinDelayMs
        val ackStartTime = System.currentTimeMillis()
        var countdownJob: Job? = null
        fun applyAckState() {
            if (acknowledged) {
                ackButton.isEnabled = true
                ackButton.text = getString(R.string.about_acknowledge)
                ackButton.backgroundTintList = ColorStateList.valueOf(enabledBg)
                ackButton.setTextColor(enabledText)
                return
            }
            val elapsed = System.currentTimeMillis() - ackStartTime
            val atBottom = !scroll.canScrollVertically(1)
            val timeOk = elapsed >= effectiveMinDelayMs
            val ready = atBottom && timeOk
            ackButton.isEnabled = ready
            if (ready) {
                ackButton.text = getString(R.string.about_acknowledge)
                ackButton.backgroundTintList = ColorStateList.valueOf(enabledBg)
                ackButton.setTextColor(enabledText)
            } else {
                val remainSec = ((effectiveMinDelayMs - elapsed + 999) / 1000).coerceAtLeast(0)
                ackButton.text = if (timeOk) getString(R.string.about_acknowledge)
                    else getString(R.string.about_acknowledge_countdown, remainSec)
                ackButton.backgroundTintList = ColorStateList.valueOf(disabledBg)
                ackButton.setTextColor(disabledText)
            }
        }
        var autoScroll: ValueAnimator? = null
        if (!acknowledged) {
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> applyAckState() }
            // 自动滑动运行期间屏蔽触摸，用户无法手动滑动，完全由动画匀速滚动；动画结束后恢复可滑动。
            scroll.setOnTouchListener { _, _ -> autoScroll?.isRunning == true }
            countdownJob = lifecycleScope.launch {
                while (System.currentTimeMillis() - ackStartTime < effectiveMinDelayMs) {
                    delay(1000L)
                    applyAckState()
                }
                applyAckState()
            }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
        }
        buttonRow.addView(ackButton)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttonRow)

        val dialog = AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setView(root)
            .setCancelable(false)
            .create()
        dialog.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) true else false
        }
        dialog.setOnDismissListener {
            countdownJob?.cancel()
            autoScroll?.cancel()
        }
        dialog.setOnShowListener {
            val window = (it as? Dialog)?.window
            unifyTypeface(window?.decorView)
        }
        ackButton.setOnClickListener {
            modePrefs.edit().putBoolean(KEY_ACK_COMPLETED, true).apply()
            dialog.dismiss()
            onClose?.invoke()
        }
        // 初始外观：已确认过直接可点；首次先置禁用外观，避免布局测量前闪现可点状态。
        if (acknowledged) {
            ackButton.backgroundTintList = ColorStateList.valueOf(enabledBg)
            ackButton.setTextColor(enabledText)
            ackButton.isEnabled = true
        } else {
            ackButton.backgroundTintList = ColorStateList.valueOf(disabledBg)
            ackButton.setTextColor(disabledText)
            ackButton.isEnabled = false
        }
        dialog.show()
        scroll.post {
            applyAckState()
            if (!acknowledged) {
                val content = scroll.getChildAt(0)
                val maxY = if (content != null) (content.height - scroll.height).coerceAtLeast(0) else 0
                effectiveMinDelayMs = baseMinDelayMs
                if (maxY > 0) {
                    applyAckState()
                    autoScroll = ValueAnimator.ofInt(0, maxY).apply {
                        duration = effectiveMinDelayMs
                        interpolator = LinearInterpolator()
                        addUpdateListener { va -> scroll.scrollTo(0, va.animatedValue as Int) }
                        start()
                    }
                }
            }
        }
    }

    private fun contactRow(labelRes: Int, value: String, pad: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 3 }
        }
        val label = TextView(this).apply {
            text = getString(labelRes)
            textSize = 15f
            typeface = appTypeface
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val copyButton = MaterialButton(this).apply {
            text = getString(R.string.copy)
            textSize = 12f
            typeface = appTypeface
            setOnClickListener { copyContact(value) }
        }
        row.addView(label)
        row.addView(spacer)
        row.addView(copyButton)
        return row
    }

    private fun copyContact(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("contact", text))
        toast(R.string.copied_toast)
    }

    private var toastBubble: TextView? = null
    private var toastHideJob: Job? = null

    private fun toast(resId: Int, anchor: PieceColor? = null) = toast(getString(resId), anchor)

    /**
     * 应用内自绘提示条。系统 Toast 无法指定字体，会与全工程统一的楷体不一致，
     * 因此改用与对话框同底纹的浮层，字体同样走 appTypeface。
     *
     * 传入 anchor（走棋方）时，提示跟随该方所在半场：位于上方的一方显示在顶部，
     * 并按文字朝向规则旋转 180°；不传 anchor 时保持底部居中正向（菜单/复制等通用提示）。
     */
    private fun toast(message: CharSequence, anchor: PieceColor? = null) {
        if (isFinishing) return
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        val density = resources.displayMetrics.density

        val bottomColor = if (boardView.flipped) PieceColor.BLACK else PieceColor.RED
        val onTop = anchor != null && anchor != bottomColor
        val angle = if (onTop && boardView.flipTextBySide) 180f else 0f

        val bubble = toastBubble ?: TextView(this).apply {
            typeface = appTypeface
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text))
            textSize = 15f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dialog_background)
            setPadding(
                (20 * density).toInt(), (12 * density).toInt(),
                (20 * density).toInt(), (12 * density).toInt()
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            elevation = 6f * density
        }.also {
            root.addView(it, toastLayoutParams(onTop, density))
            toastBubble = it
        }
        bubble.layoutParams = toastLayoutParams(onTop, density)
        bubble.rotation = angle
        bubble.text = message
        bubble.animate().cancel()
        bubble.alpha = 1f
        bubble.visibility = View.VISIBLE
        toastHideJob?.cancel()
        toastHideJob = lifecycleScope.launch {
            delay(1500L)
            bubble.animate().alpha(0f).setDuration(220L)
                .withEndAction { bubble.visibility = View.GONE }.start()
        }
    }

    private fun toastLayoutParams(onTop: Boolean, density: Float): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            if (onTop) Gravity.TOP or Gravity.CENTER_HORIZONTAL
            else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        val margin = (112 * density).toInt()
        if (onTop) lp.topMargin = margin else lp.bottomMargin = margin
        return lp
    }

    override fun onDestroy() {
        super.onDestroy()
        stopThinkingAnimation()
        gameController.destroy()
        audioManager.release()
        ttsSpeaker.release()
    }
}
