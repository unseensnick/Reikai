package reikai.presentation.reader

import android.view.KeyEvent
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action

/**
 * The keyboard map every viewport reads for the keys other than volume (upstream's, from Mihon's
 * ReaderActivity): the arrows run the way the pages do, so right goes back in an [rtl] book, and with
 * Ctrl they read on or back whichever way the pages run. A key acts on its release, so a held key moves
 * once; its press is taken too, so focus never moves under the reader. A [held] page (a novel's resume
 * veil) takes the key and does nothing, as it does a touch. False for a key it leaves alone.
 */
fun handleReaderKey(event: KeyEvent, rtl: Boolean, held: Boolean, perform: (Action) -> Unit): Boolean {
    val ctrl = event.metaState and KeyEvent.META_CTRL_ON != 0
    val action = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl && !ctrl) Action.BACKWARD else Action.FORWARD
        KeyEvent.KEYCODE_DPAD_LEFT -> if (rtl && !ctrl) Action.FORWARD else Action.BACKWARD
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Action.FORWARD
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> Action.BACKWARD
        KeyEvent.KEYCODE_MENU -> Action.MENU
        else -> return false
    }
    if (event.action == KeyEvent.ACTION_UP && !held) perform(action)
    return true
}
