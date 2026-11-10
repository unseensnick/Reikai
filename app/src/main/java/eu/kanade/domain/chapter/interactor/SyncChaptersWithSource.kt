package eu.kanade.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import reikai.data.manga.toSourceChapters
import reikai.domain.chapter.ArrivingChapter
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.chapter.StoredChapter
import reikai.domain.chapter.appliedTo
import reikai.domain.chapter.chapterArrivals
import reikai.domain.chapter.foundRelease
import reikai.domain.library.ContentType
import reikai.domain.library.ReleaseInterval
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import kotlin.time.Clock
import kotlin.time.Instant

@Inject
class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
    private val chapterNumberOverrides: ChapterNumberOverrideRepository, // RK
    private val chapterCopyMarks: ChapterCopyMarkRepository, // RK
) {

    /**
     * Method to synchronize db chapters with source ones
     *
     * @param rawSourceChapters the chapters from the source.
     * @param manga the manga the chapters belong to.
     * @param source the source the manga belongs to.
     * @return Newly added chapters
     */
    suspend fun await(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: ClosedRange<Instant>? = null,
    ): List<Chapter> {
        if (rawSourceChapters.isEmpty() && !source.isLocal()) {
            throw NoChaptersException()
        }

        val timeZone = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(timeZone)
        val nowInstant = now.toInstant(timeZone)

        // RK --> the prepare hook, both url dedupes and number recognition run in toSourceChapters, the
        // list as a sync stores it, which the migration count peek reads too. A number the user corrected
        // replaces the source's, by the rule the novel sync applies too.
        val overridden = chapterNumberOverrides.getByOwner(ContentType.MANGA, manga.id).appliedTo(
            rawSourceChapters.toSourceChapters(manga, source),
            urlOf = { it.url },
            numberOf = { it.chapterNumber },
            withNumber = { chapter, number -> chapter.copy(chapterNumber = number) },
        )
        chapterNumberOverrides.updateSourceNumbers(ContentType.MANGA, manga.id, overridden.moved)
        val sourceChapters = overridden.chapters
        val sourceUrls = sourceChapters.mapTo(mutableSetOf()) { it.url }
        val sourceNumbers = sourceChapters.associate { it.url to it.chapterNumber }
        // RK <--

        val dbChapters = chapterRepository.getChapterByMangaId(manga.id)
        val dbChaptersByUrl = dbChapters.associateBy { it.url }

        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<ChapterRemoteUpdate>()

        for (chapter in sourceChapters) { // RK: already prepared and recognized
            val dbChapter = dbChaptersByUrl[chapter.url]

            if (dbChapter == null) {
                newChapters.add(chapter) // RK: chapterArrivals fills a missing upload date
            } else {
                if (shouldUpdateDbChapter.await(dbChapter, chapter)) {
                    val shouldRenameChapter = downloadProvider.isChapterDirNameChanged(dbChapter, chapter) &&
                        downloadManager.isChapterDownloaded(
                            dbChapter.name,
                            dbChapter.scanlator,
                            dbChapter.url,
                            manga.title,
                            manga.source,
                        )

                    if (shouldRenameChapter) {
                        downloadManager.renameChapter(source, manga, dbChapter, chapter)
                    }

                    updatedChapters.add(
                        ChapterRemoteUpdate(
                            id = dbChapter.id,
                            name = chapter.name,
                            chapterNumber = chapter.chapterNumber,
                            scanlator = chapter.scanlator,
                            sourceOrder = chapter.sourceOrder,
                            dateUpload = chapter.dateUpload,
                            memo = chapter.memo,
                        ),
                    )
                }
            }
        }

        val removedChapters = dbChapters.filterNot { it.url in sourceUrls }

        // The source no longer lists these, so their queued downloads could only fail
        removedChapters.mapNotNull { downloadManager.getQueuedDownloadOrNull(it.id) }
            .takeIf { it.isNotEmpty() }
            ?.let { downloadManager.cancelQueuedDownloads(it) }

        // Return if there's nothing to add, delete, or update to avoid unnecessary db transactions.
        if (newChapters.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty()) {
            // RK: the re-predict rule is ReleaseInterval's, which the novel sync calls too
            if (ReleaseInterval.needsPrediction(manualFetch, manga.fetchInterval, manga.nextUpdate, fetchWindow)) {
                updateManga.awaitUpdateFetchInterval(
                    manga,
                    timeZone,
                    now,
                    fetchWindow,
                )
            }
            return emptyList()
        }

        // RK --> the arrival rules are a kernel the novel sync calls too
        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)
        // The user's copy marks bend the rule as they do in the reader; read only when the rule runs.
        val marks = if (markDuplicateAsRead) {
            chapterCopyMarks.loadedMarks(ContentType.MANGA)[manga.id].orEmpty()
        } else {
            emptyMap()
        }
        // Sources MUST return the chapters from most to less recent, which the fetch dates rely on.
        val arrivals = chapterArrivals(
            added = newChapters.map {
                ArrivingChapter(
                    it.chapterNumber,
                    it.read,
                    it.bookmark,
                    it.dateUpload,
                    it.name,
                    it.scanlator,
                    marks[it.url],
                )
            },
            stored = dbChapters.map {
                StoredChapter(
                    sourceNumbers[it.url] ?: it.chapterNumber,
                    it.read,
                    name = it.name,
                    scanlator = it.scanlator,
                    mark = marks[it.url],
                )
            },
            removed = removedChapters.map { StoredChapter(it.chapterNumber, it.read, it.bookmark, it.dateFetch) },
            markDuplicateAsRead = markDuplicateAsRead,
            now = nowInstant,
        )
        val changedOrDuplicateReadUrls = newChapters.zip(arrivals)
            .filter { (_, arrival) -> arrival.isChangedOrDuplicate }
            .mapTo(mutableSetOf()) { (chapter, _) -> chapter.url }
        val toAdd = newChapters.zip(arrivals) { chapter, arrival ->
            chapter.copy(
                dateFetch = arrival.dateFetch,
                dateUpload = arrival.dateUpload,
                read = arrival.read,
                bookmark = arrival.bookmark,
            )
        }
        // RK <--

        val added = chapterRepository.updateFromRemote(
            removedIds = removedChapters.map { it.id },
            added = toAdd,
            updated = updatedChapters,
        )

        val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()

        // RK: the found-release rule is ChapterArrivals' foundRelease, which the novel sync calls too
        val foundRelease = foundRelease(
            hadChapters = dbChapters.isNotEmpty(),
            arrivals = newChapters.zip(arrivals).filter { (chapter, _) -> chapter.scanlator !in excludedScanlators }
                .map { (_, arrival) -> arrival },
        )

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        updateManga.awaitUpdateFetchIntervalAndLastUpdate(manga, timeZone, now, fetchWindow, foundRelease)

        return added.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
    }
}
