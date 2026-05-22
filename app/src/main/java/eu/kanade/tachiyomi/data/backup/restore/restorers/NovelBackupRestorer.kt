package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.novel.models.Novel

class NovelBackupRestorer(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
    private val trackRepository: NovelTrackRepository = Injekt.get(),
) {
    /**
     * Restores a single [BackupNovel]. Idempotent on `(source, url)` so re-running a restore over
     * an already-populated DB merges in place rather than duplicating rows. Per-chapter writes
     * preserve `last_text_progress`, `read`, and `bookmark`; per-track writes piggyback on
     * `novel_tracks`' `UNIQUE(novel_id, sync_id) ON CONFLICT REPLACE`.
     */
    suspend fun restoreNovel(
        backupNovel: BackupNovel,
        onComplete: (BackupNovel) -> Unit,
        onError: (BackupNovel, Throwable) -> Unit,
    ) {
        try {
            val novelId = upsertNovel(backupNovel)
            restoreChapters(novelId, backupNovel.chapters)
            restoreTracks(novelId, backupNovel.tracking)
            onComplete(backupNovel)
        } catch (e: Throwable) {
            onError(backupNovel, e)
        }
    }

    private suspend fun upsertNovel(backupNovel: BackupNovel): Long {
        val existing = novelRepository.getByUrlAndSource(backupNovel.url, backupNovel.source)
        return if (existing != null) {
            // Merge: keep existing id + initialized state, but adopt the favorite flag and any
            // metadata fields the backup carries (title etc. may be more recent on the producer).
            val merged = mergedNovel(existing, backupNovel)
            novelRepository.update(merged)
            existing.id ?: error("existing novel row had null id (source=${existing.source}, url=${existing.url})")
        } else {
            novelRepository.insert(backupNovel.toNovel())
                ?: error("insert returned null id for ${backupNovel.source}/${backupNovel.url}")
        }
    }

    private fun mergedNovel(existing: Novel, backup: BackupNovel): Novel = existing.copy(
        title = backup.title.ifBlank { existing.title },
        author = backup.author ?: existing.author,
        artist = backup.artist ?: existing.artist,
        description = backup.description ?: existing.description,
        genres = backup.genre.takeIf { it.isNotEmpty() } ?: existing.genres,
        status = backup.status.takeIf { it != 0 } ?: existing.status,
        thumbnailUrl = backup.thumbnailUrl ?: existing.thumbnailUrl,
        favorite = backup.favorite || existing.favorite,
        chapterFlags = backup.chapterFlags.takeIf { it != 0 } ?: existing.chapterFlags,
        dateAdded = if (existing.dateAdded > 0L) existing.dateAdded else backup.dateAdded,
        updateStrategy = backup.updateStrategy.takeIf { it != 0 } ?: existing.updateStrategy,
    )

    private suspend fun restoreChapters(novelId: Long, chapters: List<BackupNovelChapter>) {
        if (chapters.isEmpty()) return
        chapters.forEach { backupChapter ->
            val existing = chapterRepository.getByUrlAndNovelId(backupChapter.url, novelId)
            if (existing != null) {
                // Preserve the per-device existing progress unless the backup has more progress.
                // Read / bookmark flags fold via OR so neither side regresses.
                chapterRepository.update(
                    backupChapter.toNovelChapter(novelId).copy(
                        id = existing.id,
                        read = existing.read || backupChapter.read,
                        bookmark = existing.bookmark || backupChapter.bookmark,
                        lastTextProgress = maxOf(existing.lastTextProgress, backupChapter.lastTextProgress),
                    ),
                )
            } else {
                chapterRepository.insert(backupChapter.toNovelChapter(novelId))
            }
        }
    }

    private suspend fun restoreTracks(novelId: Long, tracks: List<BackupNovelTracking>) {
        if (tracks.isEmpty()) return
        tracks.forEach { track ->
            // `novel_tracks` has UNIQUE(novel_id, sync_id) ON CONFLICT REPLACE, so upsert is the
            // insert call itself; no explicit existence check needed.
            trackRepository.upsert(track.toNovelTrack(novelId))
        }
    }
}
