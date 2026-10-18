package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTitle
import eu.kanade.tachiyomi.data.backup.models.customInfo
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import eu.kanade.tachiyomi.data.backup.models.toBackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.toBackupReadingSession
import eu.kanade.tachiyomi.data.backup.models.toBackupTracking
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.first
import mihon.core.common.extensions.toByteArray
import mihon.core.common.extensions.toEpochMillisOrZero
import reikai.data.backup.BackupEntryParts
import reikai.data.backup.writeRows
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.readinglog.ReadingLogRepository
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository

@Inject
class MangaBackupCreator(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val historyRepository: HistoryRepository,
    private val trackRepository: TrackRepository,
    // RK: source of captured adult/EXH gallery metadata for the backup.
    private val mangaMetadataRepository: MangaMetadataRepository,
    // RK: source of the user's custom info, written on the entry itself.
    private val customMangaInfoRepository: CustomMangaInfoRepository,
    // RK: which manga are backed up, read here since the shared driver asks each type for its own.
    private val getFavorites: GetFavorites,
    private val getManga: GetManga,
    // RK: merge group members outside the library are backed up so a restored group keeps them.
    private val mergeGroupRepository: MergeGroupRepository,
    // RK: chapter numbers the user corrected, carried beside the source's own.
    private val chapterNumberOverrides: ChapterNumberOverrideRepository,
    // RK: the reading log, carried with history.
    private val readingLog: ReadingLogRepository,
) : BackupEntryParts<Manga, BackupManga> { // RK

    // RK --> the option gates, the choice of which manga to back up and the chunking live in the driver
    // shared with novels (reikai.data.backup.backupEntries), so this class answers one part at a time for
    // a whole chunk, reading each table once, as upstream's backupMangas does.
    override suspend fun favorites(): List<Manga> = getFavorites.await()

    override suspend fun readNotInLibrary(): List<Manga> = mangaRepository.getReadMangaNotInLibrary()

    // A membership whose row has gone is skipped rather than failing the backup.
    override suspend fun groupMembersOutsideLibrary(): List<Manga> =
        mergeGroupRepository.getAllMemberships(ContentType.MANGA).keys
            .mapNotNull { getManga.await(it) }
            .filterNot { it.favorite }

    override suspend fun base(entries: List<Manga>): List<BackupManga> {
        val excludedScanlatorsByMangaId = mangaRepository.getExcludedScanlatorsByMangaIds(entries.map { it.id })
        return entries.map { entry ->
            // Entry for this manga
            val mangaObject = entry.toBackupManga()

            mangaObject.excludedScanlators = excludedScanlatorsByMangaId[entry.id].orEmpty()

            // Carry captured adult/EXH gallery metadata so a restore brings the tags back.
            mangaMetadataRepository.getMetadataById(entry.id)?.let { meta ->
                mangaObject.searchMetadata = BackupSearchMetadata(
                    uploader = meta.uploader,
                    extra = meta.extra,
                    indexedExtra = meta.indexedExtra,
                    extraVersion = meta.extraVersion,
                    tags = mangaMetadataRepository.getTagsById(entry.id)
                        .map { BackupSearchTag(it.namespace, it.name, it.type) },
                    titles = mangaMetadataRepository.getTitlesById(entry.id)
                        .map { BackupSearchTitle(it.title, it.type) },
                )
            }

            mangaObject
        }
    }

    override suspend fun chapters(batch: List<Pair<Manga, BackupManga>>) {
        val mangaIds = batch.map { it.first.id }
        val overridesByMangaId = chapterNumberOverrides.getByOwners(ContentType.MANGA, mangaIds)
        batch.writeRows(chapterRepository.getChaptersByMangaIds(mangaIds), Manga::id) { entry, backup, chapters ->
            val overrides = overridesByMangaId[entry.id].orEmpty()
            backup.chapters = chapters.map { chapter ->
                chapter.toBackupChapter().apply { sourceChapterNumber = overrides[chapter.url]?.sourceNumber }
            }
        }
    }

    override suspend fun categories(batch: List<Pair<Manga, BackupManga>>) {
        val categoriesByMangaId = categoryRepository.getCategoriesByMangaIds(batch.map { it.first.id })
        batch.writeRows(categoriesByMangaId, Manga::id) { _, backup, categories ->
            backup.categories = categories.map { it.order }
        }
    }

    override suspend fun tracking(batch: List<Pair<Manga, BackupManga>>) {
        batch.writeRows(
            trackRepository.getTracksByMangaIds(
                batch.map {
                    it.first.id
                },
            ),
            Manga::id,
        ) { _, backup, tracks ->
            backup.tracking = tracks.map { it.toBackupTracking() }
        }
    }

    override suspend fun history(batch: List<Pair<Manga, BackupManga>>) {
        val mangaIds = batch.map { it.first.id }
        val historyByMangaId = historyRepository.getHistoryByMangaIds(mangaIds)
        // History is backed up by chapter URL, so it needs the chapters too
        val chaptersByMangaId = if (historyByMangaId.isEmpty()) {
            emptyMap()
        } else {
            chapterRepository.getChaptersByMangaIds(
                mangaIds,
            )
        }
        val sessionsByMangaId = readingLog.sessionsByUrl(ContentType.MANGA, mangaIds)
        batch.forEach { (entry, backup) ->
            val chapterUrlsById = chaptersByMangaId[entry.id].orEmpty().associate { it.id to it.url }
            val history = historyByMangaId[entry.id].orEmpty().mapNotNull { history ->
                // A chapter removed since its history was read takes that history with it
                val url = chapterUrlsById[history.chapterId] ?: return@mapNotNull null
                BackupHistory(url, history.readAt.toEpochMillisOrZero(), history.readDuration)
            }
            if (history.isNotEmpty()) {
                backup.history = history
            }
            // Sessions ride the history option, keyed by url as history is
            backup.readingSessions = sessionsByMangaId[entry.id].orEmpty().map { it.toBackupReadingSession() }
        }
    }

    override suspend fun customInfo(batch: List<Pair<Manga, BackupManga>>) {
        batch.forEach { (entry, backup) ->
            customMangaInfoRepository.getByMangaIdAsFlow(entry.id).first()
                ?.let { backup.customInfo = it.toBackupCustomInfo() }
        }
    }
    // RK <--
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
        dateAdded = this.favoriteAt.toEpochMillisOrZero(),
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        notes = this.notes,
        initialized = this.initialized,
        memo = this.memo.toByteArray(),
        nextUpdate = this.nextUpdate.toEpochMillisOrZero(),
    )
