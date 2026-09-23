package reikai.presentation.reader.text

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Html
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.UpdateAppearance
import android.util.Base64
import android.view.View
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.data.coil.NovelImage
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import coil3.size.Dimension as CoilDimension
import coil3.size.Size as CoilSize

/** Stands in for an image while it loads, so the span keeps its place and can be swapped in later. */
class DrawableWrapper : Drawable() {
    var innerDrawable: Drawable? = null

    override fun draw(canvas: Canvas) {
        innerDrawable?.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        innerDrawable?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        innerDrawable?.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSPARENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}

/**
 * Resolves the images in a chapter for `Html.fromHtml`.
 *
 * Ported from tsundoku (`textview/NovelImageGetter.kt`), with their host Activity replaced by a
 * [Context] and each picture fetched with its source's own client and headers ([NovelImage]). Their
 * page-loader scheme is not taken: it serves images out of a local archive, which our content never
 * produces (a downloaded chapter carries its images inline as data URLs).
 */
class NovelImageGetter(
    private val context: Context,
    private val scope: CoroutineScope,
    contentWidthPx: Int,
    private val sourceId: String?,
    /** The text size in pixels and colour, which a failed picture's box is drawn in. */
    private val textSizePx: Float,
    private val textColor: () -> Int,
    private val resolveView: (Drawable) -> TextView?,
    /**
     * Pictures arrived, in the [views] they sit in: re-measure those, running [swapIn] as their text is set.
     * [allLanded] once no load is outstanding. Re-measuring is the renderer's job, because only it knows
     * whether the text is precomputed, and a precomputed layout ignores a bounds change.
     */
    private val onImagesLanded: suspend (views: List<TextView>, swapIn: () -> Unit, allLanded: Boolean) -> Unit,
) : Html.ImageGetter {

    private val contentWidth: Int =
        contentWidthPx.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels

    private val density: Float = context.resources.displayMetrics.density

    private data class PendingLoad(val source: String, val wrapper: DrawableWrapper)

    private val pendingLoads = mutableListOf<PendingLoad>()
    private val outstandingLoads = AtomicInteger(0)

    // Main thread: the pictures arrived since the last re-measure, and the views they sit in.
    private val swaps = mutableListOf<() -> Unit>()
    private val dirtyViews = mutableSetOf<TextView>()
    private val arrivals = Channel<Unit>(Channel.CONFLATED)

    /** Main thread: the line each picture sits in, so redrawing it costs one span search per picture. */
    private val lines = mutableMapOf<DrawableWrapper, PulseLine>()

    /**
     * Called by the parser, off the main thread. A network image only gets queued here: starting it
     * now would race the views it has to measure against, so [startLoading] does that.
     */
    override fun getDrawable(source: String?): Drawable {
        val wrapper = DrawableWrapper()
        val density = context.resources.displayMetrics.density
        val placeholder = ImageLoadingDrawable(
            width = contentWidth,
            height = (PLACEHOLDER_HEIGHT_DP * density).toInt(),
            cornerPx = PLACEHOLDER_CORNER_DP * density,
            textColor = textColor,
        )
        wrapper.innerDrawable = placeholder
        wrapper.bounds = placeholder.bounds

        when {
            source.isNullOrBlank() -> showFailure(wrapper, retryable = false)
            source.startsWith("data:") -> decodeInlineImage(source, wrapper)
            source.startsWith("http://") || source.startsWith("https://") ->
                pendingLoads += PendingLoad(source, wrapper)
            source.startsWith("//") -> pendingLoads += PendingLoad("https:$source", wrapper)
            else -> {
                logcat(LogPriority.DEBUG) { "Skipping unsupported image source" }
                showFailure(wrapper, retryable = false)
            }
        }
        return wrapper
    }

    /** Main thread: the queued images load once the views they measure against exist. False when
     *  there were none, so [onImagesLanded] will not be called. */
    fun startLoading(): Boolean {
        val started = pendingLoads.isNotEmpty()
        outstandingLoads.set(pendingLoads.size)
        pendingLoads.forEach { (source, wrapper) -> loadFromNetwork(source, wrapper) }
        if (started) {
            pulse(pendingLoads.map { it.wrapper })
            landArrivals()
        }
        pendingLoads.clear()
        return started
    }

    /**
     * Main thread: puts the pictures in as they arrive, each batch in one re-measure, and the next batch only
     * once that re-measure has finished, so the pictures that arrive during one land in the following one.
     * Lives with this render's scope, which the chapter ends, so a retry after the last load still lands.
     */
    private fun landArrivals() {
        scope.launch(Dispatchers.Main) {
            while (true) {
                arrivals.receive()
                val batch = swaps.toList()
                swaps.clear()
                val views = dirtyViews.toList()
                dirtyViews.clear()
                onImagesLanded(views, { batch.forEach { it() } }, outstandingLoads.get() == 0)
            }
        }
    }

    /** Main thread, until the last picture lands; the renderer's scope ends it with the viewport. Still when
     *  the device's animations are off, as the rest of the app is. */
    private fun pulse(wrappers: List<DrawableWrapper>) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        scope.launch(Dispatchers.Main) {
            val start = SystemClock.uptimeMillis()
            try {
                while (outstandingLoads.get() > 0) {
                    val strength = imageLoadingPulse(SystemClock.uptimeMillis() - start)
                    wrappers.forEach { wrapper ->
                        val box = wrapper.innerDrawable as? ImageLoadingDrawable ?: return@forEach
                        box.pulse = strength
                        redraw(wrapper)
                    }
                    delay(PULSE_FRAME_MS)
                }
            } finally {
                lines.values.forEach(PulseLine::clear)
                lines.clear()
            }
        }
    }

    /** Redraws the line [wrapper] sits in, for a pulse or a slice landing. Found once: finding a picture's
     *  line searches every span of its view, too much for each frame of a pulse. */
    private fun redraw(wrapper: DrawableWrapper) {
        (lines[wrapper] ?: markLine(wrapper)?.also { lines[wrapper] = it })?.redraw()
    }

    private fun markLine(wrapper: DrawableWrapper): PulseLine? {
        val view = resolveView(wrapper) ?: return null
        val text = view.text as? Spannable ?: return null
        val image = text.getSpans(0, text.length, ImageSpan::class.java).firstOrNull { it.drawable === wrapper }
            ?: return null
        val mark = RedrawMark()
        text.setSpan(mark, text.getSpanStart(image), text.getSpanEnd(image), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return PulseLine(view, mark)
    }

    /**
     * A loading picture's line. A selectable view's editor keeps each block of text drawn and replays it on
     * an invalidate, so the line is redrawn by re-setting a span over it, which marks its block dirty. Read
     * off the view each time: a re-measure sets a copy of the text, which carries the mark across.
     */
    private class PulseLine(val view: TextView, val mark: RedrawMark) {
        fun redraw() {
            val text = view.text as? Spannable ?: return
            val start = text.getSpanStart(mark)
            // Gone once a re-render replaced the text.
            if (start < 0) return
            text.setSpan(mark, start, text.getSpanEnd(mark), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.invalidate()
        }

        fun clear() {
            (view.text as? Spannable)?.removeSpan(mark)
        }
    }

    /** Draws nothing: it exists to be re-set, a change a selectable view redraws the text under. */
    private class RedrawMark :
        CharacterStyle(),
        UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) = Unit
    }

    /** A downloaded chapter stores its images inline, so this is the offline path. Anything short of a
     *  picture drawn is the failure box, never the placeholder left standing. */
    private fun decodeInlineImage(source: String, wrapper: DrawableWrapper) {
        val picture = try {
            decodeInline(source)
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "Failed to decode an inline chapter image" }
            null
        }
        val box = picture?.let { pictureBox(it.sourceWidth, it.sourceHeight, contentWidth, density) }
        val drawn = picture?.let { fitted(it.bitmap.toDrawable(context.resources), box) }
        if (picture == null || drawn == null) {
            showFailure(wrapper, retryable = false)
            return
        }
        // Read in slices like a fetched one, so a stored strip is as sharp offline as it is online.
        val replacement = sliced(TileReader.of(picture.bytes), drawn, box, wrapper) ?: drawn
        wrapper.innerDrawable = replacement
        wrapper.bounds = replacement.bounds
    }

    /** A decoded picture beside its own bytes and the size it is at the source, which the decode samples down. */
    private class InlinePicture(
        val bitmap: Bitmap,
        val bytes: ByteArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    /** The picture a `data:` address carries, or null when it carries none. */
    private fun decodeInline(source: String): InlinePicture? {
        val commaIndex = source.indexOf(',')
        if (commaIndex <= 0) return null
        val bytes = Base64.decode(source.substring(commaIndex + 1), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = inlineSampleSize(bounds.outWidth, bounds.outHeight, contentWidth, MAX_DECODE_SIDE_PX)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return InlinePicture(bitmap, bytes, bounds.outWidth, bounds.outHeight)
    }

    private fun loadFromNetwork(imageUrl: String, wrapper: DrawableWrapper) {
        scope.launch {
            try {
                val landed = fetch(imageUrl)?.let { prepare(imageUrl, it) }
                try {
                    withContext(Dispatchers.Main) { arrive(imageUrl, wrapper, landed) }
                } catch (e: CancellationException) {
                    landed?.reader?.close()
                    throw e
                }
            } finally {
                withContext(Dispatchers.Main) { onLoadFinished() }
            }
        }
    }

    /** A fetched picture sized for its box, with the reader for its slices when it is worth reading in parts. */
    private class Landed(val picture: Drawable, val box: PictureBox?, val reader: TileReader?)

    /**
     * Off the main thread, since both read the cached file: the picture's size at the source, and the
     * reader for its slices, opened only for a picture the loader had to shrink. Null for nothing to draw.
     */
    private suspend fun prepare(imageUrl: String, drawable: Drawable): Landed? = withContext(Dispatchers.IO) {
        val bounds = sourceBoundsOf(imageUrl)
        val box = bounds?.let { (width, height) -> pictureBox(width, height, contentWidth, density) }
        val picture = fitted(drawable, box) ?: return@withContext null
        val slice = box != null && bounds != null && worthSlicing(bounds.first, picture.intrinsicWidth, box.width)
        Landed(picture, box, if (slice) readerFor(imageUrl) else null)
    }

    /**
     * Sizes the picture's span now, for the re-measure to read, and queues the picture itself for when the
     * re-measured text is set: drawn before, it spills over a line still the stand-in's height.
     */
    private fun arrive(imageUrl: String, wrapper: DrawableWrapper, landed: Landed?) {
        queueSwap(wrapper, landed?.let { drawnFrom(it, wrapper) } ?: failureBox(retryable = true))
        if (landed == null) offerRetry(imageUrl, wrapper)
    }

    /** The picture from its slices when it was worth reading in parts, else the decoded copy. */
    private fun drawnFrom(landed: Landed, wrapper: DrawableWrapper): Drawable {
        val reader = landed.reader ?: return landed.picture
        val box = landed.box ?: return landed.picture.also { reader.close() }
        return TiledPicture(reader, box, landed.picture, scope, onTileReady = { redraw(wrapper) })
    }

    /**
     * The picture drawn from slices, for one the loader had to shrink below the box it is drawn in, or null
     * when it decoded whole or its format cannot be read in parts. A slice landing redraws its line.
     */
    private fun sliced(
        reader: TileReader?,
        picture: Drawable,
        box: PictureBox?,
        wrapper: DrawableWrapper,
    ): TiledPicture? {
        if (box == null || reader == null) return null
        if (!worthSlicing(reader.sourceWidth, picture.intrinsicWidth, box.width)) {
            reader.close()
            return null
        }
        return TiledPicture(reader, box, picture, scope, onTileReady = { redraw(wrapper) })
    }

    /** Slices read from the file the fetch cached, which outlives the fetch itself. */
    private fun readerFor(imageUrl: String): TileReader? = runCatching {
        context.imageLoader.diskCache?.openSnapshot(imageUrl)?.use { it.data.toFile() }
    }.getOrNull()?.takeIf { it.exists() }?.let(TileReader::of)

    private fun queueSwap(wrapper: DrawableWrapper, replacement: Drawable) {
        wrapper.bounds = replacement.bounds
        swaps += { wrapper.innerDrawable = replacement }
        resolveView(wrapper)?.let(dirtyViews::add)
    }

    /**
     * The picture, or null when it could not be had. A [retry] reads no cache, as the manga reader's page
     * retry forces a download.
     */
    private suspend fun fetch(imageUrl: String, retry: Boolean = false): Drawable? = try {
        val cache = if (retry) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED
        val request = ImageRequest.Builder(context)
            .data(NovelImage(imageUrl, sourceId))
            .memoryCachePolicy(cache)
            .diskCachePolicy(cache)
            .size(CoilSize(CoilDimension.Pixels(contentWidth), CoilDimension.Undefined))
            // Only ever scaled down: an exact size enlarges a small picture to the column, while the
            // box it is drawn in comes from the picture's own size (pictureBox).
            .precision(Precision.INEXACT)
            .build()
        context.imageLoader.execute(request).image?.asDrawable(context.resources)
    } catch (e: CancellationException) {
        // A viewport teardown cancels every outstanding image, and reporting each as a
        // failure buried real ones. Rethrown so the coroutine still ends cancelled.
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.DEBUG, e) { "Failed to load a chapter image" }
        null
    }

    /**
     * In the picture's place, as the page draws one (reader.js). Its height differs from the stand-in's, so
     * a picture already laid out owes its view a re-measure, which the caller arranges.
     */
    private fun showFailure(wrapper: DrawableWrapper, retryable: Boolean) {
        val box = failureBox(retryable)
        wrapper.innerDrawable = box
        wrapper.bounds = box.bounds
    }

    private fun failureBox(retryable: Boolean) = ImageFailureDrawable(
        width = contentWidth,
        em = textSizePx,
        heading = context.stringResource(MR.strings.decode_image_error),
        retryLabel = if (retryable) context.stringResource(MR.strings.action_retry) else null,
        textColor = textColor,
    )

    /** Main thread, once the text holds the picture: a tap on its box asks for it again. */
    private fun offerRetry(imageUrl: String, wrapper: DrawableWrapper) {
        val view = resolveView(wrapper) ?: return
        val text = view.text as? Spannable ?: return
        val image = text.getSpans(0, text.length, ImageSpan::class.java).firstOrNull { it.drawable === wrapper }
            ?: return
        text.setSpan(
            RetryImageSpan(imageUrl, wrapper),
            text.getSpanStart(image),
            text.getSpanEnd(image),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /** A link over a failed picture, so LinkOnlyMovementMethod gives it the tap a link gets. */
    private inner class RetryImageSpan(val imageUrl: String, val wrapper: DrawableWrapper) : ClickableSpan() {
        override fun onClick(widget: View) {
            val box = wrapper.innerDrawable as? ImageFailureDrawable ?: return
            if (box.retrying) return
            box.retrying = true
            widget.invalidate()
            scope.launch {
                // Through the same path as a first load, so a tall strip loaded by Retry is sliced too.
                val landed = fetch(imageUrl, retry = true)?.let { prepare(imageUrl, it) }
                box.retrying = false
                if (landed == null) {
                    widget.invalidate()
                    return@launch
                }
                ((widget as TextView).text as? Spannable)?.removeSpan(this@RetryImageSpan)
                queueSwap(wrapper, drawnFrom(landed, wrapper))
                arrivals.trySend(Unit)
            }
        }

        /** No colour or underline: there is no text under it to mark. */
        override fun updateDrawState(ds: TextPaint) = Unit
    }

    // Signalled after the count drops, so the batch that sees it reach zero is the one told all have landed.
    private fun onLoadFinished() {
        outstandingLoads.decrementAndGet()
        arrivals.trySend(Unit)
    }

    /**
     * Drawn in the box the page gives it ([pictureBox]), measured from [source] when the picture's own size
     * is known and from the decoded copy when it is not. Null for a drawable with nothing to draw.
     */
    private fun fitted(drawable: Drawable, source: PictureBox?): Drawable? {
        val box = source
            ?: pictureBox(drawable.intrinsicWidth, drawable.intrinsicHeight, contentWidth, density)
            ?: return null
        drawable.setBounds(0, 0, box.width, box.height)
        return drawable
    }

    /**
     * The picture's width and height as the source has it, read from the bytes the fetch cached: Coil caps a
     * decode at 4096px a side, so a strip taller than that decodes narrower than the page draws it. Null when
     * the cache cannot answer, leaving the decoded copy's own size to stand in.
     */
    private fun sourceBoundsOf(imageUrl: String): Pair<Int, Int>? {
        val snapshot = runCatching { context.imageLoader.diskCache?.openSnapshot(imageUrl) }.getOrNull() ?: return null
        val bounds = snapshot.use {
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(it.data.toFile().path, this)
            }
        }
        return (bounds.outWidth to bounds.outHeight).takeIf { (width, height) -> width > 0 && height > 0 }
    }

    private companion object {
        const val PLACEHOLDER_HEIGHT_DP = 200
        const val PLACEHOLDER_CORNER_DP = 4
        const val PULSE_FRAME_MS = 32L

        /** What the loader caps a fetched picture at (coil3 `maxBitmapSize`), so a stored one matches. */
        const val MAX_DECODE_SIDE_PX = 4_096
    }
}
