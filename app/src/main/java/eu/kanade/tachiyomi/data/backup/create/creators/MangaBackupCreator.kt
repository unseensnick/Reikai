package eu.kanade.tachiyomi.data.backup.create.creators

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTitle
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaBackupCreator(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    // RK: source of captured adult/EXH gallery metadata for the backup.
    private val mangaMetadataRepository: MangaMetadataRepository = Injekt.get(),
) {

    suspend operator fun invoke(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        return mangas.map {
            backupManga(it, options)
        }
    }

    // RK: emit one BackupManga at a time so the caller can encode + write each to the backup stream
    // and let it be collected, instead of holding every manga (and all its chapters) in memory. This
    // is what keeps a large-library backup from OOMing on the chapters payload.
    fun backupMangaStream(mangas: List<Manga>, options: BackupOptions): Flow<BackupManga> = flow {
        for (manga in mangas) {
            emit(backupManga(manga, options))
            yield()
        }
    }

    private suspend fun backupManga(manga: Manga, options: BackupOptions): BackupManga {
        // Entry for this manga
        val mangaObject = manga.toBackupManga()

        mangaObject.excludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()

        if (options.chapters) {
            // Backup all the chapters
            database.chaptersQueries
                .getChaptersByMangaId(
                    mangaId = manga.id,
                    applyScanlatorFilter = 0, // false
                    mapper = backupChapterMapper,
                )
                .awaitAsList()
                .takeUnless(List<BackupChapter>::isEmpty)
                ?.let { mangaObject.chapters = it }
        }

        if (options.categories) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = database.manga_syncQueries
                .getTracksByMangaId(manga.id, backupTrackMapper)
                .awaitAsList()
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByMangaId = getHistory.await(manga.id)
            if (historyByMangaId.isNotEmpty()) {
                val history = historyByMangaId.map { history ->
                    val chapter = database.chaptersQueries
                        .getChapterById(history.chapterId)
                        .awaitAsOne()
                    BackupHistory(chapter.url, history.readAt?.time ?: 0L, history.readDuration)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        // RK: carry captured adult/EXH gallery metadata so a restore brings the tags back.
        mangaMetadataRepository.getMetadataById(manga.id)?.let { meta ->
            mangaObject.searchMetadata = BackupSearchMetadata(
                uploader = meta.uploader,
                extra = meta.extra,
                indexedExtra = meta.indexedExtra,
                extraVersion = meta.extraVersion,
                tags = mangaMetadataRepository.getTagsById(manga.id)
                    .map { BackupSearchTag(it.namespace, it.name, it.type) },
                titles = mangaMetadataRepository.getTitlesById(manga.id)
                    .map { BackupSearchTitle(it.title, it.type) },
            )
        }

        return mangaObject
    }
}

private fun Manga.toBackupManga() =
    BackupManga(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.dateAdded,
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        lastModifiedAt = this.lastModifiedAt,
        favoriteModifiedAt = this.favoriteModifiedAt,
        version = this.version,
        notes = this.notes,
        initialized = this.initialized,
        memo = MemoColumnAdapter.encode(this.memo),
    )
