package reikai.presentation.local

import android.net.Uri
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.ResultEventBus
import mihon.core.navigation.Navigator

/** Files opened with or shared to the app while the import screen is open, which join its picks. */
data class ImportFilesEvent(val uris: List<Uri>)

/**
 * Shows the import screen with [files]: back to the one already open, which adds them to its own picks
 * rather than being replaced, else a new one over the library.
 */
fun Navigator.importFiles(files: List<Uri>, bus: ResultEventBus) {
    if (routes.any { it is LocalImportRoute }) {
        popTo { it is LocalImportRoute }
        bus.sendResult(ImportFilesEvent(files))
    } else {
        popToRoot()
        push(LocalImportRoute(files.map { it.toString() }))
    }
}
