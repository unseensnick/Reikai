package reikai.presentation.reader

import android.content.Context
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.children

/** The session's grayscale and invert, or null for neither. Provided by the host, read by [ViewportHost]. */
val LocalViewportPaint = compositionLocalOf<Paint?> { null }

/** Mounts a viewport's [view]. The container blocks descendant focus until a text renderer lifts it. */
@Composable
fun ViewportHost(view: View, modifier: Modifier = Modifier) {
    val paint = LocalViewportPaint.current
    AndroidView(
        factory = { context ->
            ViewportContainer(context).apply {
                (view.parent as? ViewGroup)?.removeView(view)
                addView(view)
            }
        },
        modifier = modifier,
        update = { it.setLayerType(View.LAYER_TYPE_HARDWARE, paint) },
        onRelease = { it.removeAllViews() },
    )
}

// Keeps requestDisallowInterceptTouchEvent from Compose, whose interop filter cancels the gesture when
// a child re-allows intercepting mid-drag (mihon 00335b2a7). Also lays out a child before any pre-draw
// reader: Compose measures a held view after pre-draw, and the novel renderer's page turn measures there.
private class ViewportContainer(context: Context) : FrameLayout(context) {
    private val layoutBeforeDraw = ViewTreeObserver.OnPreDrawListener {
        if (isLayoutRequested || children.any { it.isLayoutRequested }) {
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            layout(left, top, right, bottom)
        }
        true
    }

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(layoutBeforeDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(layoutBeforeDraw)
        super.onDetachedFromWindow()
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
}
