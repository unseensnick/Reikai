package reikai.presentation.local

import android.net.Uri
import androidx.navigation3.runtime.result.ResultEventBus
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.Stack

/** Files opened with or shared to the app while the import screen is open, which join its picks. */
data class ImportFilesEvent(val uris: List<Uri>)

/**
 * Shows the import screen with [files]: back to the one already open, which adds them to its own picks
 * rather than being replaced, else a new one over the library.
 */
fun Stack<Screen>.importFiles(files: List<Uri>, bus: ResultEventBus) {
    if (items.any { it is LocalImportRoute }) {
        popUntil { it is LocalImportRoute }
        bus.sendResult(ImportFilesEvent(files))
    } else {
        popAll()
        push(LocalImportRoute(files.map { it.toString() }))
    }
}
