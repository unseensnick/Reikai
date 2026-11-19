package reikai.data.backup

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import reikai.domain.library.ContentType
import reikai.domain.library.LibraryHiddenRepository

/**
 * A backup's hidden entries of one type, written once every entry and every merge group of that type is
 * back. Never per entry: a re-add clears its whole group's hide (library_hidden.sq), which would lose a
 * flag written earlier in the stream and unhide a group the device hid. So the device's own hides are read
 * before the type's entries restore ([deviceHidden]) and written back with the backup's. Only ever adds,
 * so an older backup without the flag leaves the device's hides alone.
 */
@Inject
class RestoreHiddenEntries(
    private val repository: LibraryHiddenRepository,
) {

    suspend fun deviceHidden(type: ContentType): Set<Long> = repository.subscribe(type).first()

    suspend fun <R> restore(type: ContentType, deviceHidden: Set<Long>, refs: List<R>, resolve: suspend (R) -> Long?) {
        repository.hide(type, (deviceHidden + refs.mapNotNull { resolve(it) }).toList())
    }
}
