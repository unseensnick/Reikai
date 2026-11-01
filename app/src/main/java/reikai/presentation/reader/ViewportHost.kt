package reikai.presentation.reader

import android.content.Context
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.children
import eu.kanade.tachiyomi.ui.reader.viewer.LocalPageColorFilter

/** Mounts a viewport's [view]. The container blocks descendant focus until a text renderer lifts it. */
@Composable
fun ViewportHost(view: View, modifier: Modifier = Modifier) {
    // A novel's page is the whole renderer, so it takes on the view what a manga viewer applies per image
    val filter = LocalPageColorFilter.current
    val paint = remember(filter) { filter?.let { Paint().apply { colorFilter = it.asAndroidColorFilter() } } }
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
