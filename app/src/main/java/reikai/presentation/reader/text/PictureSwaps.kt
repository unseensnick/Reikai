package reikai.presentation.reader.text

import android.graphics.drawable.Drawable
import java.io.Closeable

/**
 * Main thread: the pictures that arrived since the last re-measure, each put in place when its batch sets the
 * text. Only a picture in the text is collected to be closed with its chapter, so one holding its source open
 * (a [TiledPicture]) that its render drops first is closed by [closeUnswapped] instead.
 */
internal class PictureSwaps {

    private val queued = mutableListOf<() -> Unit>()
    private val unswapped = mutableSetOf<Closeable>()

    fun queue(wrapper: DrawableWrapper, replacement: Drawable) {
        (replacement as? Closeable)?.let(unswapped::add)
        queued += {
            wrapper.innerDrawable = replacement
            (replacement as? Closeable)?.let(unswapped::remove)
        }
    }

    /** Everything queued so far, to swap in as one batch; what is queued after joins the next. */
    fun take(): () -> Unit {
        val batch = queued.toList()
        queued.clear()
        return { batch.forEach { it() } }
    }

    fun closeUnswapped() {
        unswapped.forEach(Closeable::close)
        unswapped.clear()
    }
}
