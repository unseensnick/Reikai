package reikai.presentation.reader

import android.view.KeyEvent

/** The volume-key rule both novel renderers read: how far a press scrolls, as a signed fraction of the
 *  screen, positive reading on. */
object NovelVolumeKeys {
    const val MIN_FRACTION = 0.1f

    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    fun scrollFraction(keyCode: Int, inverted: Boolean, fraction: Float): Float {
        val forward = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != inverted
        val step = fraction.coerceIn(MIN_FRACTION, 1f)
        return if (forward) step else -step
    }
}
