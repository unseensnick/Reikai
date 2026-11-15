package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupChapterTombstone
import eu.kanade.tachiyomi.data.backup.models.toBackup
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.library.ContentType
import kotlin.reflect.KMutableProperty1

/**
 * A chunk's removed chapters' kept states, both content types: the chapters part writes them and the history
 * part rewrites them whole with their history, so their history needs both options. By their own rows, since
 * a local series whose every chapter is gone has kept states and no chapter rows.
 */
class ChapterTombstoneBackupParts<E, B>(
    private val type: ContentType,
    private val tombstones: ChapterTombstoneRepository,
    private val id: (E) -> Long,
    private val field: KMutableProperty1<B, List<BackupChapterTombstone>>,
) {
    // The chunk the chapters part last wrote, since kept states ride under Chapters.
    private var withChapters: List<Long>? = null

    // Called after the chapters are read, so one a refresh captures between the two reads lands in both, never in
    // neither (the restore's fold spends the duplicate).
    suspend fun chapters(batch: List<Pair<E, B>>) {
        write(batch, withHistory = false)
        withChapters = batch.map { id(it.first) }
    }

    // Called after the history is read, and read again rather than reused: a chapter captured since the chapters
    // part is in the chapter list with no history row left, so only this read holds its history.
    suspend fun history(batch: List<Pair<E, B>>) {
        if (withChapters == batch.map { id(it.first) }) write(batch, withHistory = true)
    }

    private suspend fun write(batch: List<Pair<E, B>>, withHistory: Boolean) =
        batch.writeRows(tombstones.getByOwners(type, batch.map { id(it.first) }), id) { _, backup, kept ->
            field.set(backup, kept.map { it.toBackup(withHistory) })
        }
}
