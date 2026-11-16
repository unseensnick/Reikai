package reikai.presentation.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import reikai.presentation.reader.text.NovelChapterSeamView
import reikai.presentation.reader.text.NovelSeam
import kotlin.math.roundToInt

/**
 * One reading column shown two pages at a time ([NovelSpreads]). The column is laid out half the spread wide and
 * [heightFactor] times its height, then drawn twice: the left page as it stands and the right page shifted up by
 * the gap between the two pages' tops, so neither renderer learns a second column. The masks are drawn here per
 * page, and a seam's page draws the transition card across the whole spread instead of the column.
 * Side margins sit outside the spread, and each page keeps [INNER_MARGIN_DP] beside the gap ([SpreadColumns]).
 * Right to left, the first page of a spread is on the right, as manga's dual page view puts it.
 */
class NovelSpreadHost(
    context: Context,
    private val column: View,
    private val heightFactor: Int,
    private val rtl: Boolean,
    @ColorInt private val background: () -> Int,
) : FrameLayout(context) {

    private var readingTop = 0f
    private var firstHeight = 0f

    /** How far below the first page's top the second page starts in the column, null with no second page. */
    var shift: Float? = null
        private set
    private var secondHeight = 0f

    /**
     * Run after this view takes a new size, the first included: a renderer measuring before layout could only
     * guess the column's width from the display.
     */
    var onResized: (() -> Unit)? = null

    /** Until a page is placed nothing is masked, so the column's first layout shows as it stands. */
    private var placed = false

    private val innerMargin = (INNER_MARGIN_DP * resources.displayMetrics.density).toInt()
    private var outerLeft = 0
    private var outerRight = 0

    /** The spread gap as a share of a page's text width. */
    private var gapFraction = 0f
    private val paint = Paint()
    private var card: NovelChapterSeamView? = null

    /** Where the gesture under way landed, in this view's own pixels, which the tap zones read. */
    private var downX = 0f
    private var downY = 0f
    private var touchDx = 0f
    private var touchDy = 0f

    /** A copy of the screen held over a move until the page under it has painted; null when nothing is held. */
    private var cover: Bitmap? = null
    private var spare: Bitmap? = null
    private val dropCover = Runnable {
        cover = null
        invalidate()
    }

    init {
        clipChildren = false
        setWillNotDraw(false)
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** [settings] for the column: the reader's side margins move to the spread's outside edges, and each page keeps
     *  [INNER_MARGIN_DP] on both sides. A book's own side margins are left to each renderer. */
    fun columnSettings(settings: NovelReaderSettings): NovelReaderSettings {
        val density = resources.displayMetrics.density
        val left = (settings.margins.left * density).toInt()
        val right = (settings.margins.right * density).toInt()
        val gap = settings.spreadGap / 100f
        if (left != outerLeft || right != outerRight || gap != gapFraction) {
            outerLeft = left
            outerRight = right
            gapFraction = gap
            requestLayout()
        }
        return settings.copy(margins = settings.margins.copy(left = INNER_MARGIN_DP, right = INNER_MARGIN_DP))
    }

    /**
     * The spread now on screen: the first page [firstHeight] tall at [readingTop], the second [shift] further down
     * the column and [secondHeight] tall, or none. A [card] is drawn across the spread in place of both, fitted to
     * a page [cardPage] tall, in [cardColor].
     */
    fun place(
        readingTop: Float,
        firstHeight: Float,
        shift: Float?,
        secondHeight: Float,
        card: NovelSeam?,
        @ColorInt cardColor: Int,
        cardPage: Int,
    ) {
        showCard(card, cardColor, cardPage)
        this.readingTop = readingTop
        this.firstHeight = firstHeight
        this.shift = shift
        this.secondHeight = secondHeight
        placed = true
        invalidate()
    }

    /** Whether the transition card is drawn in place of the column. */
    val showsCard: Boolean get() = card?.visibility == VISIBLE

    /** The bottom of what the spread shows of the column, in the column's own pixels. */
    val shownBottom: Float get() = readingTop + (shift?.let { it + secondHeight } ?: firstHeight)

    /** The gesture's landing point as a share of the spread, which is where its tap zones are. */
    fun tapPoint(): Pair<Float, Float>? =
        if (width > 0 && height > 0) downX / width to downY / height else null

    /** Whether the gesture landed where a mask covers the column, outside the pages shown. */
    fun landedUnderMask(): Boolean {
        if (!placed) return false
        val onSecond = (downX >= width / 2f) != rtl
        val pageHeight = if (onSecond) secondHeight.takeIf { shift != null } ?: return true else firstHeight
        return downY < readingTop || downY > readingTop + pageHeight
    }

    private fun showCard(seam: NovelSeam?, @ColorInt color: Int, page: Int) {
        val current = card
        if (seam == null) {
            current?.visibility = GONE
            return
        }
        if (current == null || current.seam != seam || current.textColor != color || !current.fits(page)) {
            current?.let(::removeView)
            card = NovelChapterSeamView(context, seam, color, page).also(::addView)
        }
        card?.visibility = VISIBLE
        requestLayout()
    }

    /** Where a spread [w] wide draws its column. */
    private fun columns(w: Int) = SpreadColumns.of(w, outerLeft, outerRight, innerMargin, gapFraction)

    /** How wide the column is laid out in a spread [w] wide, which is what the column measures before it is. */
    fun columnWidth(w: Int) = columns(w).width

    /** Where the column is drawn for the left and the right half of the spread. */
    private val leftX: Int get() = columns(width).leftX
    private val rightX: Int get() = columns(width).rightX
    private val firstX: Int get() = if (rtl) rightX else leftX
    private val secondX: Int get() = if (rtl) leftX else rightX

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        card?.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST),
        )
        column.measure(
            MeasureSpec.makeMeasureSpec(columns(w).width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h * heightFactor, MeasureSpec.EXACTLY),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Posted: the renderer redraws from here, which must not run inside this layout pass.
        (handler ?: Handler(Looper.getMainLooper())).post { onResized?.invoke() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        // At the reading top, where the column's own card starts, so it does not move between the two.
        card?.let { it.layout(0, readingTop.toInt(), it.measuredWidth, readingTop.toInt() + it.measuredHeight) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Each page draws within its own column, so the gap between the two shows the background.
        val columns = columns(width)
        val leftEnd = (columns.leftX + columns.width).toFloat()
        val rightStart = columns.rightX.toFloat()
        paint.color = background()
        canvas.drawRect(0f, 0f, w, h, paint)
        card?.takeIf { it.visibility == VISIBLE }?.let {
            drawChild(canvas, it, drawingTime)
            return
        }
        val (firstLeft, firstRight) = if (rtl) rightStart to w else 0f to leftEnd
        val (secondLeft, secondRight) = if (rtl) 0f to leftEnd else rightStart to w
        drawColumn(canvas, firstLeft, firstRight, firstX, 0f)
        val s = shift
        if (s != null || !placed) drawColumn(canvas, secondLeft, secondRight, secondX, s ?: 0f)
        if (!placed) return
        canvas.drawRect(firstLeft, 0f, firstRight, readingTop, paint)
        canvas.drawRect(firstLeft, readingTop + firstHeight, firstRight, h, paint)
        if (s == null) {
            canvas.drawRect(secondLeft, 0f, secondRight, h, paint)
        } else {
            canvas.drawRect(secondLeft, 0f, secondRight, readingTop, paint)
            canvas.drawRect(secondLeft, readingTop + secondHeight, secondRight, h, paint)
        }
    }

    private fun drawColumn(canvas: Canvas, left: Float, right: Float, x: Int, up: Float) {
        canvas.save()
        canvas.clipRect(left, 0f, right, height.toFloat())
        canvas.translate(x.toFloat(), -up)
        drawChild(canvas, column, drawingTime)
        canvas.restore()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        cover?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /** Each half's touches go to the column where that half shows it. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            val onSecond = (ev.x >= width / 2f) != rtl
            touchDx = -(if (onSecond) secondX else firstX).toFloat()
            // A half with no page shows none of the column, so its touches go below the first page, where the
            // column's mask takes them as the page's: off the column, a WebView never sees them.
            touchDy = if (onSecond) shift ?: (readingTop + firstHeight) else 0f
        }
        ev.offsetLocation(touchDx, touchDy)
        val handled = column.dispatchTouchEvent(ev)
        ev.offsetLocation(-touchDx, -touchDy)
        return handled
    }

    /**
     * Copies what the screen shows and holds it over this view, then runs [then], the move it hides, until
     * [release]. A WebView column paints a moved spread a few frames after the move, so without the copy the
     * reader would see a page from either side of it.
     */
    fun hold(then: () -> Unit) {
        // Already covered, the copy shows the last settled spread, which is what the reader should keep seeing.
        if (width == 0 || height == 0 || cover != null) return then()
        val copy = spare?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { spare = it }
        val asked = copyScreen(this, copy, handler ?: Handler(Looper.getMainLooper())) { copied ->
            if (copied) {
                cover = copy
                invalidate()
                removeCallbacks(dropCover)
                postDelayed(dropCover, HOLD_CAP_MS)
            }
            then()
        }
        if (!asked) then()
    }

    /** Drops the copy [hold] put up, once the spread's tiles have had time to raster ([afterTiles]). */
    fun release() {
        if (cover == null) return
        afterTiles {
            removeCallbacks(dropCover)
            dropCover.run()
        }
    }

    /** Runs [action] [TILE_FRAMES] frames from now, the time a WebView's tiles take to raster a moved spread. */
    fun afterTiles(action: () -> Unit) {
        var left = TILE_FRAMES
        postOnAnimation(
            object : Runnable {
                override fun run() {
                    if (--left > 0) postOnAnimation(this) else action()
                }
            },
        )
    }

    companion object {
        /** Each page's margin beside the gutter, so with no spread gap the two pages' text is twice this apart. */
        const val INNER_MARGIN_DP = 16

        /** Over 92 turns on a Fold, four frames after the paint never showed a page from before the move. */
        private const val TILE_FRAMES = 4

        /** A move whose paint never comes is uncovered after this. */
        private const val HOLD_CAP_MS = 1000L
    }
}

/**
 * Where a spread [SpreadColumns.of] draws its one column: [width] wide, at [leftX] for the left page and [rightX]
 * for the right. Each page's text keeps an inner margin beside the gap, so the text edges sit that far inside.
 */
internal data class SpreadColumns(val width: Int, val leftX: Int, val rightX: Int) {
    companion object {
        /**
         * In a spread [w] wide with the reader's side margins [outerLeft] and [outerRight] on its outside: the two
         * pages' text sits [gapFraction] of one page's text width apart beyond both [innerMargin]s, and flush with
         * the outer margins. The text width solves w = 2 text + gap text + both inner margins each + outer margins.
         */
        fun of(w: Int, outerLeft: Int, outerRight: Int, innerMargin: Int, gapFraction: Float): SpreadColumns {
            val text = (w - outerLeft - outerRight - 2 * innerMargin) / (2f + gapFraction)
            val gap = 2 * innerMargin + (gapFraction * text).roundToInt()
            val width = ((w - outerLeft - outerRight - gap) / 2 + 2 * innerMargin).coerceAtLeast(1)
            return SpreadColumns(width, outerLeft - innerMargin, w - outerRight + innerMargin - width)
        }
    }
}
