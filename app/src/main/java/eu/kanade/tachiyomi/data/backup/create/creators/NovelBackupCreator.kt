package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.novel.models.Novel

class NovelBackupCreator(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
    private val trackRepository: NovelTrackRepository = Injekt.get(),
) {
    /**
     * Backs up every favorited novel and its dependents. Honors [options.chapters] and
     * [options.tracking] the same way [MangaBackupCreator] does for manga, so a user opting out
     * of either at the top level applies to both content types.
     */
    suspend operator fun invoke(options: BackupOptions): List<BackupNovel> {
        if (!options.novels) return emptyList()
        return novelRepository.getFavorites().map { backupNovel(it, options) }
    }

    private suspend fun backupNovel(novel: Novel, options: BackupOptions): BackupNovel {
        val entry = BackupNovel.copyFrom(novel)

        if (options.chapters) {
            val chapters = novel.id?.let { chapterRepository.getByNovelId(it) }.orEmpty()
            if (chapters.isNotEmpty()) {
                entry.chapters = chapters.map { BackupNovelChapter.copyFrom(it) }
            }
        }

        if (options.tracking) {
            val tracks = novel.id?.let { trackRepository.getByNovelId(it) }.orEmpty()
            if (tracks.isNotEmpty()) {
                entry.tracking = tracks.map { BackupNovelTracking.copyFrom(it) }
            }
        }

        return entry
    }
}
