package reikai.presentation.reader.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.util.Base64
import android.view.View
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import coil3.asDrawable
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt
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
 * [Context] and the Referer taken from the chapter's own base URL, which the session already
 * resolved. Their page-loader scheme is not taken: it serves images out of a local archive, which
 * our content never produces (a downloaded chapter carries its images inline as data URLs).
 */
class NovelImageGetter(
    private val context: Context,
    private val scope: CoroutineScope,
    contentWidthPx: Int,
    /** Some hosts refuse an image without one, so the chapter's own site is sent. */
    private val refererUrl: String?,
    /** The text size in pixels and colour, which a failed picture's box is drawn in. */
    private val textSizePx: Float,
    private val textColor: () -> Int,
    private val resolveView: (Drawable) -> TextView?,
    /** Every load has finished, with the views whose images arrived, empty when none did. Re-measuring
     *  is the renderer's job, because only it knows whether the text is precomputed, and a precomputed
     *  layout ignores a bounds change. */
    private val onImagesReady: (List<TextView>) -> Unit,
) : Html.ImageGetter {

    private val contentWidth: Int =
        contentWidthPx.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels

    private data class PendingLoad(val source: String, val wrapper: DrawableWrapper)

    private val pendingLoads = mutableListOf<PendingLoad>()
    private val dirtyViews = ConcurrentHashMap.newKeySet<TextView>()
    private val outstandingLoads = AtomicInteger(0)

    /**
     * Called by the parser, off the main thread. A network image only gets queued here: starting it
     * now would race the views it has to measure against, so [startLoading] does that.
     */
    override fun getDrawable(source: String?): Drawable {
        val wrapper = DrawableWrapper()
        val placeholderHeight = (PLACEHOLDER_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        val placeholder = Color.LTGRAY.toDrawable()
        placeholder.setBounds(0, 0, contentWidth, placeholderHeight)
        wrapper.innerDrawable = placeholder
        wrapper.setBounds(0, 0, contentWidth, placeholderHeight)

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
     *  there were none, so [onImagesReady] will not be called. */
    fun startLoading(): Boolean {
        val started = pendingLoads.isNotEmpty()
        outstandingLoads.set(pendingLoads.size)
        pendingLoads.forEach { (source, wrapper) -> loadFromNetwork(source, wrapper) }
        pendingLoads.clear()
        return started
    }

    /** A downloaded chapter stores its images inline, so this is the offline path. Anything short of a
     *  picture drawn is the failure box, never the placeholder left standing. */
    private fun decodeInlineImage(source: String, wrapper: DrawableWrapper) {
        val bitmap = try {
            decodeInline(source)
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "Failed to decode an inline chapter image" }
            null
        }
        if (bitmap == null || !fitToWidth(bitmap.toDrawable(context.resources), wrapper)) {
            showFailure(wrapper, retryable = false)
        }
    }

    /** The picture a `data:` address carries, or null when it carries none. */
    private fun decodeInline(source: String): Bitmap? {
        val commaIndex = source.indexOf(',')
        if (commaIndex <= 0) return null
        val bytes = Base64.decode(source.substring(commaIndex + 1), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun loadFromNetwork(imageUrl: String, wrapper: DrawableWrapper) {
        scope.launch {
            try {
                val drawable = fetch(imageUrl)
                withContext(Dispatchers.Main) {
                    val loaded = drawable != null && fitToWidth(drawable, wrapper)
                    if (!loaded) showFailure(wrapper, retryable = true)
                    resolveView(wrapper)?.let { view ->
                        view.invalidate()
                        dirtyViews.add(view)
                    }
                    if (!loaded) offerRetry(imageUrl, wrapper)
                }
            } finally {
                withContext(Dispatchers.Main) { onLoadFinished() }
            }
        }
    }

    /**
     * The picture, or null when it could not be had. A [retry] reads no cache: the HTTP cache keeps the
     * failed answer, which the manga reader's page retry skips the same way by forcing a download.
     */
    private suspend fun fetch(imageUrl: String, retry: Boolean = false): Drawable? = try {
        val headers = NetworkHeaders.Builder().apply {
            set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            refererUrl?.let { set("Referer", it) }
            if (retry) set("Cache-Control", "no-cache")
        }.build()
        val cache = if (retry) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .httpHeaders(headers)
            .memoryCachePolicy(cache)
            .diskCachePolicy(cache)
            .size(CoilSize(CoilDimension.Pixels(contentWidth), CoilDimension.Undefined))
            // Only ever scaled down: an exact size enlarges a small picture to the column, and its
            // own width is what fitToWidth needs to draw it at the size a page does.
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
        val box = ImageFailureDrawable(
            width = contentWidth,
            em = textSizePx,
            heading = context.stringResource(MR.strings.decode_image_error),
            retryLabel = if (retryable) context.stringResource(MR.strings.action_retry) else null,
            textColor = textColor,
        )
        wrapper.innerDrawable = box
        wrapper.bounds = box.bounds
    }

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
                val drawable = fetch(imageUrl, retry = true)
                box.retrying = false
                if (drawable == null || !fitToWidth(drawable, wrapper)) {
                    widget.invalidate()
                    return@launch
                }
                val view = widget as TextView
                (view.text as? Spannable)?.removeSpan(this@RetryImageSpan)
                view.invalidate()
                onImagesReady(listOf(view))
            }
        }

        /** No colour or underline: there is no text under it to mark. */
        override fun updateDrawState(ds: TextPaint) = Unit
    }

    /** Re-measuring once at the end, rather than per image, so a chapter of pictures reflows once. */
    private fun onLoadFinished() {
        if (outstandingLoads.decrementAndGet() > 0) return
        val views = dirtyViews.toList()
        dirtyViews.clear()
        onImagesReady(views)
    }

    /** Its own width in density-independent pixels, as the page's `max-width: 100%` draws it, up to the column.
     *  False for a drawable with no size of its own, which has nothing to draw at. */
    private fun fitToWidth(drawable: Drawable, wrapper: DrawableWrapper): Boolean {
        val imgWidth = drawable.intrinsicWidth
        val imgHeight = drawable.intrinsicHeight
        if (imgWidth <= 0 || imgHeight <= 0) return false
        val width = min(contentWidth, (imgWidth * context.resources.displayMetrics.density).roundToInt())
            .coerceAtLeast(1)
        val height = (imgHeight * (width.toFloat() / imgWidth)).toInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, width, height)
        wrapper.innerDrawable = drawable
        wrapper.setBounds(0, 0, width, height)
        return true
    }

    /** Decodes no larger than the column it will be drawn in, which is what keeps a big scan cheap. */
    private fun sampleSizeFor(sourceWidth: Int): Int {
        if (sourceWidth <= 0 || contentWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= contentWidth) sample *= 2
        return sample
    }

    private companion object {
        const val PLACEHOLDER_HEIGHT_DP = 200
    }
}
