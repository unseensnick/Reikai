package eu.kanade.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import reikai.domain.chapter.ArrivingChapter
import reikai.domain.chapter.StoredChapter
import reikai.domain.chapter.chapterArrivals
import reikai.domain.chapter.remoteUploadDate
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import kotlin.time.Clock

@Inject
class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
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
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): List<Chapter> {
        if (rawSourceChapters.isEmpty() && !source.isLocal()) {
            throw NoChaptersException()
        }

        val timeZone = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(timeZone)
        val nowMillis = now.toInstant(timeZone).toEpochMilliseconds()

        val sourceChapters = rawSourceChapters
            .distinctBy { it.url }
            .mapIndexed { i, sChapter ->
                Chapter.create()
                    .copyFromSChapter(sChapter)
                    .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                    .copy(mangaId = manga.id, sourceOrder = i.toLong())
            }

        val dbChapters = chapterRepository.getChapterByMangaId(manga.id)
        val dbChaptersByUrl = dbChapters.associateBy { it.url }

        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<ChapterRemoteUpdate>()
        val sourceUrls = mutableSetOf<String>()

        for (sourceChapter in sourceChapters) {
            var chapter = sourceChapter

            // Update metadata from source if necessary.
            if (source is HttpSource) {
                val sChapter = chapter.toSChapter()
                @Suppress("DEPRECATION")
                source.prepareNewChapter(sChapter, manga.toSManga())
                chapter = chapter.copyFromSChapter(sChapter)
            }

            if (!sourceUrls.add(chapter.url)) continue

            // Recognize chapter number for the chapter.
            val chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, chapter.name, chapter.chapterNumber)
            chapter = chapter.copy(chapterNumber = chapterNumber)

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
                            dateUpload = remoteUploadDate(chapter.dateUpload), // RK
                            memo = chapter.memo,
                        ),
                    )
                }
            }
        }

        val removedChapters = dbChapters.filterNot { it.url in sourceUrls }

        // Return if there's nothing to add, delete, or update to avoid unnecessary db transactions.
        if (newChapters.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty()) {
            if (manualFetch || manga.fetchInterval == 0 || manga.nextUpdate < fetchWindow.first) {
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
        // Sources MUST return the chapters from most to less recent, which the fetch dates rely on.
        val arrivals = chapterArrivals(
            added = newChapters.map { ArrivingChapter(it.chapterNumber, it.read, it.bookmark, it.dateUpload) },
            stored = dbChapters.map { StoredChapter(it.chapterNumber, it.read) },
            removed = removedChapters.map { StoredChapter(it.chapterNumber, it.read, it.bookmark, it.dateFetch) },
            markDuplicateAsRead = markDuplicateAsRead,
            now = nowMillis,
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
        updateManga.awaitUpdateFetchInterval(manga, timeZone, now, fetchWindow)

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        updateManga.awaitUpdateLastUpdate(manga.id)

        val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()

        return added.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
    }
}
