package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.models.BackupSearchTitle
import eu.kanade.tachiyomi.data.backup.models.customInfo
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import eu.kanade.tachiyomi.data.backup.models.toBackupTracking
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.first
import mihon.core.common.extensions.toByteArray
import reikai.data.backup.BackupEntryParts
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
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
    private val trackRepository: TrackRepository,
    private val getCategories: GetCategories,
    private val getHistory: GetHistory,
    // RK: source of captured adult/EXH gallery metadata for the backup.
    private val mangaMetadataRepository: MangaMetadataRepository,
    // RK: source of the user's custom info, written on the entry itself.
    private val customMangaInfoRepository: CustomMangaInfoRepository,
    // RK: which manga are backed up, read here since the shared driver asks each type for its own.
    private val getFavorites: GetFavorites,
    private val getManga: GetManga,
    // RK: merge group members outside the library are backed up so a restored group keeps them.
    private val mergeGroupRepository: MergeGroupRepository,
) : BackupEntryParts<Manga, BackupManga> { // RK

    // RK --> the option gates and the choice of which manga to back up live in the driver shared with
    // novels (reikai.data.backup.backupEntries), so this class answers one part at a time. Each part
    // keeps upstream's body; only the manga and its backup object arrive as parameters.
    override suspend fun favorites(): List<Manga> = getFavorites.await()

    override suspend fun readNotInLibrary(): List<Manga> = mangaRepository.getReadMangaNotInLibrary()

    // A membership whose row has gone is skipped rather than failing the backup.
    override suspend fun groupMembersOutsideLibrary(): List<Manga> =
        mergeGroupRepository.getAllMemberships(ContentType.MANGA).keys
            .mapNotNull { getManga.await(it) }
            .filterNot { it.favorite }

    override suspend fun base(entry: Manga): BackupManga {
        // Entry for this manga
        val mangaObject = entry.toBackupManga()

        mangaObject.excludedScanlators = mangaRepository.getExcludedScanlators(entry.id).toList()

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

        return mangaObject
    }

    override suspend fun chapters(entry: Manga, backup: BackupManga) {
        // Backup all the chapters
        chapterRepository.getChapterByMangaId(entry.id, applyScanlatorFilter = false)
            .map { it.toBackupChapter() }
            .takeUnless { it.isEmpty() }
            ?.let { backup.chapters = it }
    }

    override suspend fun categories(entry: Manga, backup: BackupManga) {
        // Backup categories for this manga
        val categoriesForManga = getCategories.await(entry.id)
        if (categoriesForManga.isNotEmpty()) {
            backup.categories = categoriesForManga.map { it.order }
        }
    }

    override suspend fun tracking(entry: Manga, backup: BackupManga) {
        val tracks = trackRepository.getTracksByMangaId(entry.id).map { it.toBackupTracking() }
        if (tracks.isNotEmpty()) {
            backup.tracking = tracks
        }
    }

    override suspend fun history(entry: Manga, backup: BackupManga) {
        val historyByMangaId = getHistory.await(entry.id)
        if (historyByMangaId.isNotEmpty()) {
            val history = historyByMangaId.map { history ->
                val chapter = checkNotNull(chapterRepository.getChapterById(history.chapterId))
                BackupHistory(chapter.url, history.readAt?.time ?: 0L, history.readDuration)
            }
            if (history.isNotEmpty()) {
                backup.history = history
            }
        }
    }

    override suspend fun customInfo(entry: Manga, backup: BackupManga) {
        customMangaInfoRepository.getByMangaIdAsFlow(entry.id).first()?.let { info ->
            backup.customInfo = BackupCustomInfo(
                title = info.title,
                author = info.author,
                artist = info.artist,
                description = info.description,
                genre = info.genre,
                status = info.status,
                thumbnailUrl = info.thumbnailUrl,
            )
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
        dateAdded = this.favoriteAt ?: 0L,
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        notes = this.notes,
        initialized = this.initialized,
        memo = this.memo.toByteArray(),
    )
