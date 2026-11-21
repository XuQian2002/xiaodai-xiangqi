package daidaixiangqi.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import daidaixiangqi.app.R
import daidaixiangqi.app.model.*
import java.util.Random

class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var board: Board = Board.createInitialBoard()
    private var selectedPosition: Position? = null
    private var legalMoves: List<Move> = emptyList()
    private var onMoveListener: ((Move) -> Unit)? = null
    private var onPieceSelectedListener: (() -> Unit)? = null
    private var onBlockedSelectionListener: (() -> Unit)? = null
    private var onIllegalTargetListener: ((Position, Position) -> Unit)? = null

    var moveLabelProvider: ((Move) -> String)? = null

    private var animatingMove: Move? = null
    private var animationProgress: Float = 0f
    private var moveAnimator: ValueAnimator? = null
    private var preMoveBoard: Board? = null

    companion object {
        private const val MOVE_ANIMATION_DURATION = 120L

        private const val MARGIN_X = 0.5f

        private const val MARGIN_Y = 0.8f
        const val ASPECT_HEIGHT_CELLS = 9f + 2 * MARGIN_Y
        private val TOP_FILES = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

        // 简体列标（一~九）字面偏瘦，相较繁体大写（壹~玖）整体上调一档，使两侧视觉大小接近。
        private const val SIMP_SIZE_SCALE = 1.20f

        /**
         * 坐标标注实际字号（纯函数，便于单测）。
         * @param isSimplified 是否为简体小字列标；@param baseSize 基准字号。
         */
        fun coordTextSize(isSimplified: Boolean, baseSize: Float): Float =
            if (isSimplified) baseSize * SIMP_SIZE_SCALE else baseSize

        /**
         * 棋子文字旋转角度。
         * [flipBySide] 为 false（人机）时一律 0f 正向；为 true（人人）时下方一方 0f、上方一方 180f。
         */
        fun pieceTextAngle(flipBySide: Boolean, pieceColor: PieceColor, bottomColor: PieceColor): Float =
            if (!flipBySide) 0f else if (pieceColor == bottomColor) 0f else 180f

        // 棋子盘面径向渐变三色（红/黑各一套），固定不变，提为常量避免每帧每子分配数组。
        private val RED_FACE_COLORS = intArrayOf(
            Color.rgb(255, 253, 241), Color.rgb(243, 238, 218), Color.rgb(212, 202, 173)
        )
        private val BLACK_FACE_COLORS = intArrayOf(
            Color.rgb(247, 250, 239), Color.rgb(224, 234, 211), Color.rgb(183, 202, 174)
        )

        // 径向渐变位置刻度，固定不变，避免每帧每子新建浮点数组。
        private val FACE_STOPS = floatArrayOf(0f, 0.65f, 1f)

        // 四角偏移方向（选中框/吃子角标/落点角标共用），避免每次绘制新建列表。
        private val CORNERS: List<Pair<Float, Float>> = listOf(
            -1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f
        )

        // 棋子侧缘/描边/内圈静态颜色（红、黑两套），固定不变，提为常量避免每帧每子 Color.rgb 分配。
        private val SIDE_START_RED = Color.rgb(229, 220, 191)
        private val SIDE_END_RED = Color.rgb(163, 146, 110)
        private val SIDE_START_BLACK = Color.rgb(191, 210, 180)
        private val SIDE_END_BLACK = Color.rgb(115, 143, 111)
        private val OUTLINE_RED = Color.rgb(179, 157, 115)
        private val OUTLINE_BLACK = Color.rgb(141, 162, 134)
        private val HIGHLIGHT = Color.rgb(255, 255, 245)
        private val INNER_RING_RED = Color.rgb(175, 153, 112)
        private val INNER_RING_BLACK = Color.rgb(136, 160, 126)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private val surfaceLight = color(R.color.board_surface_light)
    private val surfaceMid = color(R.color.board_surface_mid)
    private val surfaceDark = color(R.color.board_surface_dark)
    private val grainColor = color(R.color.board_grain)
    private val lineColor = color(R.color.board_line)
    private val frameColor = color(R.color.board_frame)
    private val frameLightColor = color(R.color.board_frame_light)
    private val riverColor = color(R.color.board_river)
    private val coordColor = color(R.color.board_coord)

    private val bottomFiles: Array<String> = resources.getStringArray(R.array.board_bottom_files)
    private val redInk = color(R.color.chess_piece_red_ink)
    private val blackInk = color(R.color.chess_piece_black_ink)
    private val labelFill = color(R.color.board_move_label)
    private val labelHalo = color(R.color.board_move_label_halo)

    // 纯净源码版不含自带楷体字体文件（.ttf 属二进制资源），这里退回系统 serif 字体。
    private val calligraphy: Typeface = Typeface.SERIF

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = frameColor
        style = Paint.Style.FILL
        setShadowLayer(7f, 0f, 3f, Color.argb(55, 48, 65, 42))
    }

    private val frameBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = frameLightColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
    }

    private val thickLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = riverColor
        textAlign = Paint.Align.CENTER
        typeface = calligraphy
    }

    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = coordColor
        alpha = 255
        textAlign = Paint.Align.CENTER
        typeface = calligraphy
    }

    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val redTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = redInk
        textAlign = Paint.Align.CENTER
        typeface = calligraphy
    }

    private val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blackInk
        textAlign = Paint.Align.CENTER
        typeface = calligraphy
    }

    private val moveLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = calligraphy
        isFakeBoldText = false
        style = Paint.Style.FILL
        strokeJoin = Paint.Join.ROUND
    }

    private val pieceFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 242, 230, 205)
        style = Paint.Style.FILL
    }

    private val pieceSidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val pieceOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 70, 40)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val pieceInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 100, 70, 40)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val pieceShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 55, 70, 43)
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }

    private val lastMoveFromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 196, 120, 20)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val lastMoveToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 196, 120, 20)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val checkRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 35, 35)
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
    }

    private val checkGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 230, 40, 40)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }

    private val legalMoveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_move_dot)
        style = Paint.Style.FILL
    }

    private val captureRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_capture_mark)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val captureCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_capture_mark)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_selection)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastMove: Move? = null

    private var surfaceBitmap: Bitmap? = null

    /** 被将方缓存：setBoard 时计算一次，onDraw 直接读取，避免每帧全局面生成合法着法。 */
    private var checkedColor: PieceColor? = null

    /** 棋子高光弧复用矩形，避免每帧每子新建 RectF。 */
    private val rimRect = RectF()

    var flipped = false
        set(value) {
            if (field == value) return
            field = value
            if (width > 0 && height > 0) {
                surfaceBitmap?.recycle()
                surfaceBitmap = buildSurface(width, height)
            }
            invalidate()
        }

    // 人人模式：双方文字各朝向自己座位（下方正向、上方 180°）；人机模式恒正向。
    var flipTextBySide = true
        set(value) {
            if (field == value) return
            field = value
            if (width > 0 && height > 0) {
                surfaceBitmap?.recycle()
                surfaceBitmap = buildSurface(width, height)
            }
            invalidate()
        }

    private fun cellX(pos: Position): Float = offsetX + BoardOrientation.toScreen(pos, flipped).col * cellSize
    private fun cellY(pos: Position): Float = offsetY + BoardOrientation.toScreen(pos, flipped).row * cellSize

    fun setBoard(newBoard: Board) {
        if (animatingMove != null) return
        board = newBoard
        selectedPosition = null
        legalMoves = emptyList()
        checkedColor = when {
            newBoard.isInCheck(PieceColor.RED) -> PieceColor.RED
            newBoard.isInCheck(PieceColor.BLACK) -> PieceColor.BLACK
            else -> null
        }
        invalidate()
    }

    fun setOnMoveListener(listener: (Move) -> Unit) {
        onMoveListener = listener
    }

    fun setOnPieceSelectedListener(listener: () -> Unit) {
        onPieceSelectedListener = listener
    }

    fun setOnBlockedSelectionListener(listener: () -> Unit) {
        onBlockedSelectionListener = listener
    }

    /** 选中棋子后点到非法落点时回调：起点棋位与落点，供上层判定具体原因。 */
    fun setOnIllegalTargetListener(listener: (Position, Position) -> Unit) {
        onIllegalTargetListener = listener
    }

    fun highlightMove(move: Move?) {
        lastMove = move
        invalidate()
    }

    fun animateMove(move: Move, preBoard: Board, onComplete: () -> Unit) {
        animatingMove = move
        preMoveBoard = preBoard
        animationProgress = 0f

        moveAnimator?.cancel()
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animatingMove = null
                    preMoveBoard = null
                    animationProgress = 0f
                    onComplete()
                }
            })
            start()
        }
    }

    private fun getDrawX(piece: Piece): Float {
        val anim = animatingMove
        if (anim != null && piece.position == anim.from && animationProgress < 1f) {
            val fromX = cellX(anim.from)
            val toX = cellX(anim.to)
            return fromX + (toX - fromX) * animationProgress
        }
        return cellX(piece.position)
    }

    private fun getDrawY(piece: Piece): Float {
        val anim = animatingMove
        if (anim != null && piece.position == anim.from && animationProgress < 1f) {
            val fromY = cellY(anim.from)
            val toY = cellY(anim.to)
            return fromY + (toY - fromY) * animationProgress
        }
        return cellY(piece.position)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val availableWidth = widthSize - paddingLeft - paddingRight
        val availableHeight = heightSize - paddingTop - paddingBottom

        if (availableWidth > 0 && availableHeight > 0) {
            val cell = minOf(availableWidth / 9f, availableHeight / ASPECT_HEIGHT_CELLS)
            val desiredWidth = (cell * 9f).toInt() + paddingLeft + paddingRight
            val desiredHeight = (cell * ASPECT_HEIGHT_CELLS).toInt() + paddingTop + paddingBottom
            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDimensions(w, h)
    }

    private fun updateDimensions(w: Int, h: Int) {
        val boardWidth = w - paddingLeft - paddingRight
        val boardHeight = h - paddingTop - paddingBottom
        if (boardWidth <= 0 || boardHeight <= 0) return

        cellSize = minOf(boardWidth / 9f, boardHeight / ASPECT_HEIGHT_CELLS)

        offsetX = paddingLeft + (boardWidth - cellSize * 8) / 2
        offsetY = paddingTop + (boardHeight - cellSize * 9) / 2

        linePaint.strokeWidth = (cellSize * 0.018f).coerceAtLeast(1.5f)
        thickLinePaint.strokeWidth = (cellSize * 0.03f).coerceAtLeast(3f)
        markerPaint.strokeWidth = (cellSize * 0.025f).coerceAtLeast(1.5f)
        riverTextPaint.textSize = cellSize * 0.56f
        riverTextPaint.letterSpacing = 0.55f
        coordPaint.textSize = cellSize * 0.25f
        redTextPaint.textSize = cellSize * 0.47f
        blackTextPaint.textSize = cellSize * 0.47f

        surfaceBitmap?.recycle()
        surfaceBitmap = buildSurface(w, h)
    }

    private fun boardRect() = RectF(
        offsetX - cellSize * MARGIN_X,
        offsetY - cellSize * MARGIN_Y,
        offsetX + cellSize * (8 + MARGIN_X),
        offsetY + cellSize * (9 + MARGIN_Y)
    )

    private fun buildSurface(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val frame = boardRect()
        val frameWidth = cellSize * 0.09f
        val outerRadius = cellSize * 0.14f

        c.drawRoundRect(frame, outerRadius, outerRadius, framePaint)

        val inner = RectF(frame).apply { inset(frameWidth, frameWidth) }
        val innerRadius = outerRadius * 0.5f
        val clip = Path().apply { addRoundRect(inner, innerRadius, innerRadius, Path.Direction.CW) }

        c.save()
        c.clipPath(clip)
        drawWood(c, inner)
        c.restore()

        c.drawRoundRect(
            RectF(frame).apply { inset(2f, 2f) }, outerRadius - 2f, outerRadius - 2f, frameBevelPaint
        )
        c.drawRoundRect(inner, innerRadius, innerRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 166, 139, 89)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        })

        drawCornerInlays(c, inner)
        drawGrid(c)
        drawPalaceLines(c)
        drawPositionMarks(c)
        drawRiverText(c)
        drawCoordinates(c)
        return bmp
    }

    private fun drawWood(c: Canvas, r: RectF) {
        val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(surfaceLight, surfaceMid, surfaceLight, surfaceDark), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(r, wash)
        val random = Random(42L)
        grainPaint.color = grainColor
        grainPaint.alpha = 12
        grainPaint.strokeWidth = cellSize * 0.016f
        repeat(25) {
            val x = r.left + random.nextFloat() * r.width()
            val y = r.top + random.nextFloat() * r.height()
            val vein = Path().apply {
                moveTo(x, y)
                cubicTo(x + cellSize, y - cellSize, x + cellSize * 2, y + cellSize,
                    x + cellSize * 3, y + cellSize * 0.3f)
            }
            c.drawPath(vein, grainPaint)
        }
    }

    private fun drawCornerInlays(c: Canvas, bounds: RectF) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = cellSize * 0.02f
            strokeJoin = Paint.Join.ROUND
        }
        for (corner in 0..3) {
            c.save()
            c.translate(if (corner % 2 == 0) bounds.left else bounds.right,
                if (corner < 2) bounds.top else bounds.bottom)
            c.scale(if (corner % 2 == 0) 1f else -1f, if (corner < 2) 1f else -1f)
            val u = cellSize * 0.10f
            val fret = Path().apply {
                moveTo(u, 5 * u); lineTo(u, u); lineTo(5 * u, u)
                moveTo(2 * u, 4 * u); lineTo(2 * u, 2 * u); lineTo(4 * u, 2 * u)
                lineTo(4 * u, 3 * u); lineTo(3 * u, 3 * u)
            }
            c.drawPath(fret, ink)
            c.restore()
        }
    }

    private fun drawGrid(c: Canvas) {
        for (row in 0..9) {
            val y = offsetY + row * cellSize
            c.drawLine(offsetX, y, offsetX + cellSize * 8, y, linePaint)
        }
        for (col in 0..8) {
            val x = offsetX + col * cellSize
            c.drawLine(x, offsetY, x, offsetY + cellSize * 4, linePaint)
            c.drawLine(x, offsetY + cellSize * 5, x, offsetY + cellSize * 9, linePaint)
        }

        c.drawRect(offsetX, offsetY, offsetX + cellSize * 8, offsetY + cellSize * 9, thickLinePaint)
        val gap = cellSize * 0.07f
        c.drawRect(
            offsetX - gap, offsetY - gap,
            offsetX + cellSize * 8 + gap, offsetY + cellSize * 9 + gap,
            linePaint
        )
    }

    private fun drawPalaceLines(c: Canvas) {

        c.drawLine(offsetX + cellSize * 3, offsetY, offsetX + cellSize * 5, offsetY + cellSize * 2, linePaint)
        c.drawLine(offsetX + cellSize * 5, offsetY, offsetX + cellSize * 3, offsetY + cellSize * 2, linePaint)

        c.drawLine(offsetX + cellSize * 3, offsetY + cellSize * 7, offsetX + cellSize * 5, offsetY + cellSize * 9, linePaint)
        c.drawLine(offsetX + cellSize * 5, offsetY + cellSize * 7, offsetX + cellSize * 3, offsetY + cellSize * 9, linePaint)
    }

    private fun drawPositionMarks(c: Canvas) {
        val positions = listOf(
            Position(2, 1), Position(2, 7),
            Position(7, 1), Position(7, 7),
            Position(3, 0), Position(3, 2), Position(3, 4), Position(3, 6), Position(3, 8),
            Position(6, 0), Position(6, 2), Position(6, 4), Position(6, 6), Position(6, 8)
        )
        for (pos in positions) drawPositionMark(c, pos)
    }

    private fun drawPositionMark(c: Canvas, pos: Position) {
        val x = offsetX + pos.col * cellSize
        val y = offsetY + pos.row * cellSize
        val arm = cellSize * 0.13f
        val gap = cellSize * 0.05f
        for ((dx, dy) in CORNERS) {
            if ((pos.col == 0 && dx < 0) || (pos.col == 8 && dx > 0)) continue
            val sx = x + dx * gap
            val sy = y + dy * gap
            c.drawLine(sx, sy, sx + dx * arm, sy, markerPaint)
            c.drawLine(sx, sy, sx, sy + dy * arm, markerPaint)
        }
    }

    private fun drawRiverText(c: Canvas) {
        val riverY = offsetY + cellSize * 4.5f
        val fm = riverTextPaint.fontMetrics
        val textY = riverY - (fm.ascent + fm.descent) / 2f
        riverTextPaint.alpha = 215
        c.drawText("小呆", offsetX + cellSize * 2f, textY, riverTextPaint)
        c.drawText("象棋", offsetX + cellSize * 6f, textY, riverTextPaint)
    }

    private fun drawCoordinates(c: Canvas) {
        val fm = coordPaint.fontMetrics
        val topY = offsetY - cellSize * 0.58f - (fm.ascent + fm.descent) / 2f
        val bottomY = offsetY + cellSize * 9.58f - (fm.ascent + fm.descent) / 2f
        val baseSize = cellSize * 0.25f
        // 人人翻转模式：上方对方一侧的坐标随该方棋子一起倒置，下方己方一侧保持正向。
        val topAngle = if (flipTextBySide) 180f else 0f

        for (col in 0..8) {
            val x = offsetX + col * cellSize
            val topLabel = if (flipped) bottomFiles[8 - col] else TOP_FILES[col]
            val bottomLabel = if (flipped) TOP_FILES[8 - col] else bottomFiles[col]
            drawCoordLabel(c, topLabel, x, topY, baseSize, topAngle, !flipped)
            drawCoordLabel(c, bottomLabel, x, bottomY, baseSize, 0f, flipped)
        }
        coordPaint.textSize = baseSize
    }

    private fun drawCoordLabel(c: Canvas, label: String, x: Float, y: Float, baseSize: Float, angle: Float, isSimplified: Boolean) {
        coordPaint.textSize = coordTextSize(isSimplified, baseSize)
        val fm = coordPaint.fontMetrics
        val centerOffset = (fm.ascent + fm.descent) / 2f
        if (angle == 180f) {
            c.save()
            c.translate(x, y)
            c.rotate(180f)
            c.drawText(label, 0f, -centerOffset, coordPaint)
            c.restore()
        } else {
            c.drawText(label, x, y, coordPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cellSize <= 0f) {
            updateDimensions(width, height)
            if (cellSize <= 0f) return
        }

        val surface = surfaceBitmap
        if (surface != null) {
            canvas.drawBitmap(surface, 0f, 0f, null)
        } else {
            val r = boardRect()
            canvas.drawRoundRect(r, 8f, 8f, framePaint)
            drawGrid(canvas)
        }
        drawLastMove(canvas)
        drawSelection(canvas)
        drawPieces(canvas)
        drawMoveLabels(canvas)
    }

    private fun drawLastMove(canvas: Canvas) {
        lastMove?.let { move ->
            drawBrackets(canvas, move.from, lastMoveFromPaint)
            drawBrackets(canvas, move.to, lastMoveToPaint)
        }
    }

    private fun drawBrackets(canvas: Canvas, pos: Position, paint: Paint) {
        val cx = cellX(pos)
        val cy = cellY(pos)
        val half = cellSize * 0.47f
        val arm = cellSize * 0.15f
        for ((dx, dy) in CORNERS) {
            val x0 = cx + dx * half
            val y0 = cy + dy * half
            canvas.drawLine(x0, y0, x0 - dx * arm, y0, paint)
            canvas.drawLine(x0, y0, x0, y0 - dy * arm, paint)
        }
    }

    private fun drawSelection(canvas: Canvas) {
        selectedPosition?.let { pos ->
            val x = cellX(pos)
            val y = cellY(pos)
            val radius = cellSize * 0.4f
            canvas.drawCircle(x, y, radius + 3f, selectionRingPaint)

            for (move in legalMoves) drawLegalMoveIndicator(canvas, move)
        }
    }

    private fun drawLegalMoveIndicator(canvas: Canvas, move: Move) {
        val x = cellX(move.to)
        val y = cellY(move.to)
        if (move.capturedPiece != null) {
            canvas.drawCircle(x, y, cellSize * 0.42f, captureRingPaint)
            drawCornerCaptureMark(canvas, x, y)
        } else {
            canvas.drawCircle(x, y, cellSize * 0.1f, legalMoveDotPaint)
        }
    }

    /** 记谱文字在棋子图层之后绘制，吃子落点的文字上移到被吃棋子上方，不被棋子盖住。 */
    private fun drawMoveLabels(canvas: Canvas) {
        selectedPosition ?: return
        if (legalMoves.isEmpty()) return
        val labels = legalMoves.mapNotNull { move ->
            moveLabelProvider?.invoke(move)?.takeIf { it.isNotEmpty() }
        }
        if (labels.isEmpty()) return

        // 全部落点标签共用同一字号，红方、黑方及数字/文字标签大小一致。
        var size = cellSize * 0.36f
        moveLabelPaint.textSize = size
        val maxWidth = cellSize * 0.98f
        val widest = labels.maxByOrNull { moveLabelPaint.measureText(it) } ?: return
        while (moveLabelPaint.measureText(widest) > maxWidth && size > cellSize * 0.32f) {
            size -= cellSize * 0.02f
            moveLabelPaint.textSize = size
        }

        for (move in legalMoves) {
            drawMoveLabel(canvas, move, cellX(move.to), cellY(move.to), move.capturedPiece != null, size)
        }
    }

    private fun drawMoveLabel(canvas: Canvas, move: Move, x: Float, y: Float, capture: Boolean, size: Float) {
        val label = moveLabelProvider?.invoke(move) ?: return
        if (label.isEmpty()) return

        moveLabelPaint.textSize = size
        val fm = moveLabelPaint.fontMetrics
        val labelY = if (capture) y - cellSize * 0.30f else y
        val centerOffset = (fm.ascent + fm.descent) / 2f

        // 落点棋谱与棋子同向：下方一方正向、上方一方 180°；flipTextBySide 关闭时恒正向。
        val bottomColor = if (flipped) PieceColor.BLACK else PieceColor.RED
        val angle = pieceTextAngle(flipTextBySide, move.piece.color, bottomColor)

        val paintLabel: (Float, Float) -> Unit = { dx, dy ->
            moveLabelPaint.style = Paint.Style.STROKE
            moveLabelPaint.color = labelHalo
            moveLabelPaint.strokeWidth = cellSize * 0.035f
            canvas.drawText(label, dx, dy, moveLabelPaint)
            moveLabelPaint.style = Paint.Style.FILL
            moveLabelPaint.color = labelFill
            canvas.drawText(label, dx, dy, moveLabelPaint)
        }

        if (angle == 180f) {
            canvas.save()
            canvas.translate(x, labelY)
            canvas.rotate(180f)
            paintLabel(0f, -centerOffset)
            canvas.restore()
        } else {
            paintLabel(x, labelY - centerOffset)
        }
    }

    private fun drawCornerCaptureMark(canvas: Canvas, cx: Float, cy: Float) {
        val size = cellSize * 0.42f
        val cornerLen = cellSize * 0.1f
        for ((dx, dy) in CORNERS) {
            val x0 = cx + dx * size
            val y0 = cy + dy * size
            canvas.drawLine(x0, y0, x0 - dx * cornerLen, y0, captureCornerPaint)
            canvas.drawLine(x0, y0, x0, y0 - dy * cornerLen, captureCornerPaint)
        }
    }

    private fun drawPieces(canvas: Canvas) {
        val drawBoard = if (animatingMove != null) preMoveBoard ?: board else board
        for (piece in drawBoard.pieceValues()) {
            val anim = animatingMove
            if (anim != null && anim.capturedPiece != null && piece.position == anim.to) {
                val alpha = ((1f - animationProgress) * 255).toInt().coerceIn(0, 255)
                drawPiece(canvas, piece, alpha)
                continue
            }
            drawPiece(canvas, piece, 255)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, alpha: Int = 255) {
        val x = getDrawX(piece)
        val y = getDrawY(piece)
        val radius = cellSize * 0.4f

        if (alpha > 128) {
            canvas.drawCircle(x + 3f, y + 5f, radius + 1f, pieceShadowPaint)
        }

        val jade = piece.color == PieceColor.BLACK
        val face = if (jade) BLACK_FACE_COLORS else RED_FACE_COLORS
        val thickness = cellSize * 0.055f
        pieceSidePaint.shader = LinearGradient(x, y, x, y + radius + thickness,
            if (jade) SIDE_START_BLACK else SIDE_START_RED,
            if (jade) SIDE_END_BLACK else SIDE_END_RED, Shader.TileMode.CLAMP)
        pieceSidePaint.alpha = alpha
        canvas.drawCircle(x, y + thickness, radius, pieceSidePaint)
        pieceFacePaint.shader = RadialGradient(x - radius * 0.28f, y - radius * 0.35f,
            radius * 1.6f, face, FACE_STOPS, Shader.TileMode.CLAMP)
        pieceFacePaint.alpha = alpha
        canvas.drawCircle(x, y, radius, pieceFacePaint)
        pieceFacePaint.shader = null
        pieceOutlinePaint.color = if (jade) OUTLINE_BLACK else OUTLINE_RED
        pieceOutlinePaint.strokeWidth = cellSize * 0.025f
        pieceOutlinePaint.alpha = alpha
        canvas.drawCircle(x, y, radius * 0.95f, pieceOutlinePaint)
        pieceHighlightPaint.color = HIGHLIGHT
        pieceHighlightPaint.alpha = alpha * 220 / 255
        pieceHighlightPaint.strokeWidth = cellSize * 0.026f
        rimRect.set(x - radius * 0.97f, y - radius * 0.97f, x + radius * 0.97f, y + radius * 0.97f)
        canvas.drawArc(rimRect, 195f, 145f, false, pieceHighlightPaint)
        pieceInnerRingPaint.color = if (jade) INNER_RING_BLACK else INNER_RING_RED
        pieceInnerRingPaint.strokeWidth = cellSize * 0.018f
        pieceInnerRingPaint.alpha = alpha * 150 / 255
        canvas.drawCircle(x, y, radius * 0.79f, pieceInnerRingPaint)

        if (piece.type == PieceType.GENERAL && animatingMove == null && checkedColor == piece.color) {
            canvas.drawCircle(x, y, radius + 4f, checkGlowPaint)
            canvas.drawCircle(x, y, radius + 3f, checkRingPaint)
        }

        val textPaint = if (piece.color == PieceColor.RED) redTextPaint else blackTextPaint
        val text = piece.type.getDisplayName(piece.color)
        val fm = textPaint.fontMetrics
        val centerOffset = (fm.ascent + fm.descent) / 2f

        val bottomColor = if (flipped) PieceColor.BLACK else PieceColor.RED
        val angle = pieceTextAngle(flipTextBySide, piece.color, bottomColor)
        val savedAlpha = textPaint.alpha
        textPaint.alpha = alpha
        if (angle == 180f) {
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(180f)
            canvas.drawText(text, 0f, -centerOffset, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(text, x, y - centerOffset, textPaint)
        }
        textPaint.alpha = savedAlpha
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (animatingMove != null) return true

        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchedPos = getTouchedPosition(event.x, event.y)
            touchedPos?.let { pos ->
                handleTouch(pos)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getTouchedPosition(x: Float, y: Float): Position? {
        val col = ((x - offsetX + cellSize / 2) / cellSize).toInt()
        val row = ((y - offsetY + cellSize / 2) / cellSize).toInt()
        if (!Position(row, col).isValid()) return null
        return BoardOrientation.fromScreen(row, col, flipped)
    }

    private fun handleTouch(pos: Position) {
        val piece = board.getPiece(pos)

        selectedPosition?.let {
            val move = legalMoves.find { it.to == pos }
            if (move != null) {
                onMoveListener?.invoke(move)
                selectedPosition = null
                legalMoves = emptyList()
                invalidate()
                return
            }
            // 已选中棋子：点到己方另一子则改选；点到空格或对方子属非法落点，提示并取消选中。
            if (piece == null || piece.color != board.currentPlayer) {
                onIllegalTargetListener?.invoke(it, pos)
                selectedPosition = null
                legalMoves = emptyList()
                invalidate()
                return
            }
        }

        if (piece != null && piece.color == board.currentPlayer) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectedPosition = pos
            onPieceSelectedListener?.invoke()
            legalMoves = piece.getLegalMoves(board).filter { move ->
                val testBoard = board.makeMove(move)
                !testBoard.isInCheck(piece.color)
            }
            if (legalMoves.isEmpty()) onBlockedSelectionListener?.invoke()
            invalidate()
        } else {
            selectedPosition = null
            legalMoves = emptyList()
            invalidate()
        }
    }

    fun clearSelection() {
        selectedPosition = null
        legalMoves = emptyList()
        invalidate()
    }
}
