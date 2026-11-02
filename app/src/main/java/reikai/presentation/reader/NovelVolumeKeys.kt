package reikai.presentation.reader

import android.view.KeyEvent
import kotlin.math.abs

/** Whether a volume key is the reader's: both readers leave it to the system while the menu is up. */
fun volumeKeysActive(enabled: Boolean, menuVisible: Boolean): Boolean = enabled && !menuVisible

/** Whether volume key [keyCode] reads on, for both readers: down does, unless the keys are [inverted]. */
fun volumeKeyReadsOn(keyCode: Int, inverted: Boolean): Boolean = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != inverted

/** Whether a scroll of [dy] moved far enough to hide the menu. */
internal fun scrollHidesMenu(dy: Float, threshold: Int): Boolean = abs(dy) > threshold

/** The volume-key rule both novel renderers read: how far a press scrolls, as a signed fraction of the
 *  screen, positive reading on. */
object NovelVolumeKeys {
    const val MIN_FRACTION = 0.1f

    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    fun scrollFraction(keyCode: Int, inverted: Boolean, fraction: Float): Float {
        val forward = volumeKeyReadsOn(keyCode, inverted)
        val step = fraction.coerceIn(MIN_FRACTION, 1f)
        return if (forward) step else -step
    }
}
