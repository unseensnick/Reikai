package reikai.presentation.novel.reader

import android.view.KeyEvent

/**
 * Host-window hook for the novel reader's hardware volume-key scrolling. MainActivity implements this
 * in a `// RK` island; [NovelReaderScreen] registers a handler while on screen and clears it on
 * dispose, so volume keys scroll the chapter there and behave normally everywhere else. The handler
 * receives every volume [KeyEvent] and returns true to consume it, swallowing the system volume UI.
 */
interface NovelVolumeKeyHost {
    var novelVolumeKeyHandler: ((KeyEvent) -> Boolean)?
}
