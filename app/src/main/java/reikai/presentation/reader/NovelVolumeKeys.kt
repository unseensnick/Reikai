package reikai.presentation.reader

import android.view.KeyEvent
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/** Whether a volume key is the reader's: both readers leave it to the system while the menu is up. */
fun volumeKeysActive(enabled: Boolean, menuVisible: Boolean): Boolean = enabled && !menuVisible

/** Whether volume key [keyCode] reads on, for both readers: down does, unless the keys are [inverted]. */
fun volumeKeyReadsOn(keyCode: Int, inverted: Boolean): Boolean = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != inverted

/** The volume-key rule both novel renderers read: how far a press scrolls, as a signed fraction of the
 *  screen, positive reading on. */
object NovelVolumeKeys {
    const val MIN_FRACTION = 0.1f

    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    /**
     * Both novel renderers' key handling: a volume press [move]s by [scrollFraction] on its press, other keys go
     * through [handleReaderKey], and a [held] page (the resume veil) takes every key without acting. A volume
     * key is consumed on release too, so the system volume UI never shows during a press.
     */
    fun handleKey(
        event: KeyEvent,
        rtl: Boolean,
        held: Boolean,
        active: () -> Boolean,
        settings: NovelReaderSettings?,
        perform: (ViewerNavigation.Action) -> Unit,
        move: (fraction: Float) -> Unit,
    ): Boolean {
        if (!isVolumeKey(event.keyCode)) return handleReaderKey(event, rtl, held, perform)
        if (!active() || settings == null) return false
        if (event.action == KeyEvent.ACTION_DOWN && !held) {
            move(scrollFraction(event.keyCode, settings.volumeButtonsInverted, settings.volumeButtonsFraction))
        }
        return true
    }

    fun scrollFraction(keyCode: Int, inverted: Boolean, fraction: Float): Float {
        val forward = volumeKeyReadsOn(keyCode, inverted)
        val step = fraction.coerceIn(MIN_FRACTION, 1f)
        return if (forward) step else -step
    }
}
