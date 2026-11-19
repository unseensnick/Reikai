package reikai.data.backup

import dev.zacsweers.metro.Inject
import reikai.domain.library.ContentType
import reikai.domain.library.LibraryHiddenRepository

/**
 * A backup's hidden entries of one type, written once every entry and every merge group of that type is
 * back. Never per entry: a later entry's re-add clears its whole group's hide (library_hidden.sq), so a
 * flag written earlier in the stream would be lost. Only ever adds, so an older backup without the flag
 * leaves the device's hides alone, and a re-added entry the restore puts in a device-hidden group stays hidden.
 */
@Inject
class RestoreHiddenEntries(
    private val repository: LibraryHiddenRepository,
) {

    suspend fun <R> restore(type: ContentType, refs: List<R>, resolve: suspend (R) -> Long?) {
        repository.hide(type, refs.mapNotNull { resolve(it) })
    }
}
