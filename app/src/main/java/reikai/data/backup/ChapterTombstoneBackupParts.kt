package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupChapterTombstone
import eu.kanade.tachiyomi.data.backup.models.toBackup
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.library.ContentType
import kotlin.reflect.KMutableProperty1

/**
 * A chunk's removed chapters' kept states, both content types: the chapters part writes them and the history
 * part rewrites them whole with their history, from one chunk-wide read, so their history needs both options.
 * By its own rows, since a local series whose every chapter is gone has kept states and no chapter rows.
 */
class ChapterTombstoneBackupParts<E, B>(
    type: ContentType,
    tombstones: ChapterTombstoneRepository,
    private val id: (E) -> Long,
    private val field: KMutableProperty1<B, List<BackupChapterTombstone>>,
) {
    private val read = ChunkRead { tombstones.getByOwners(type, it) }

    // Called after the chapters are read, so one a refresh captures between the two reads lands in both, never in
    // neither (the restore's fold spends the duplicate).
    suspend fun chapters(batch: List<Pair<E, B>>) = write(batch, withHistory = false)

    suspend fun history(batch: List<Pair<E, B>>) {
        if (batch.any { field.get(it.second).isNotEmpty() }) write(batch, withHistory = true)
    }

    private suspend fun write(batch: List<Pair<E, B>>, withHistory: Boolean) =
        batch.writeRows(read(batch.map { id(it.first) }), id) { _, backup, kept ->
            field.set(backup, kept.map { it.toBackup(withHistory) })
        }
}
