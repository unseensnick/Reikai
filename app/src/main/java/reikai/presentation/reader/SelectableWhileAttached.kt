package reikai.presentation.reader

import android.view.View
import android.view.ViewGroup

/**
 * `viewer_container` blocks descendant focus (`reader_activity.xml`, upstream's, so the image viewers
 * keep it), and a view that cannot take focus never starts text selection: a TextView never builds
 * the Editor that draws the handles, and a WebView never opens its selection mode. Lifted only while
 * the viewport is attached. The parent is held rather than re-read, because a detach can arrive
 * after it is gone. Both novel viewports install this, so the two cannot disagree on when it holds.
 */
class SelectableWhileAttached : View.OnAttachStateChangeListener {

    private var host: ViewGroup? = null
    private var blocked = ViewGroup.FOCUS_BLOCK_DESCENDANTS

    override fun onViewAttachedToWindow(v: View) {
        val parent = v.parent as? ViewGroup ?: return
        host = parent
        blocked = parent.descendantFocusability
        parent.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    override fun onViewDetachedFromWindow(v: View) {
        host?.descendantFocusability = blocked
        host = null
    }
}
