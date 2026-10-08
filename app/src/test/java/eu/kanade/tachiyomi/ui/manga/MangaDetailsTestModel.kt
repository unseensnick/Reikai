package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.track.EntryTrackPort
import reikai.domain.track.EntryTrackPorts
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * A real [MangaViewModel] opened on [mangaId] over [mangas] and [chapters], grouped with [group] in that
 * order, every other dependency inert. A merged list renders [stitch]; empty, it is every member's rows.
 */
internal fun mangaDetailsModel(
    mangaId: Long,
    mangas: MangaRepository,
    chapters: ChapterRepository,
    group: LongArray,
    downloadManager: DownloadManager = detailsDownloadManager(),
    sourceManager: SourceManager = mockk(relaxed = true),
    stitch: List<ChapterUnit> = emptyList(),
): MangaViewModel {
    val prefs = InMemoryPreferenceStore()
    return MangaViewModel(
        context = mockk<Context>(relaxed = true),
        mangaId = mangaId,
        isFromSource = false,
        libraryPreferences = LibraryPreferences(prefs),
        trackPreferences = TrackPreferences(prefs),
        readerPreferences = ReaderPreferences(prefs),
        trackerManager = mockk<TrackerManager>(relaxed = true) {
            every { loggedInTrackersFlow() } returns flowOf(emptyList())
        },
        trackChapter = mockk(relaxed = true),
        refreshTracks = mockk(relaxed = true),
        downloadManager = downloadManager,
        downloadCache = mockk<DownloadCache>(relaxed = true) { every { changes } returns MutableSharedFlow() },
        getMangaAndChapters = GetMangaWithChapters(mangas, chapters),
        getAvailableScanlators = GetAvailableScanlators(chapters),
        getExcludedScanlators = GetExcludedScanlators(mangas),
        setExcludedScanlators = mockk(relaxed = true),
        setMangaChapterFlags = mockk(relaxed = true),
        setMangaDefaultChapterFlags = mockk(relaxed = true),
        setReadStatus = mockk(relaxed = true),
        updateChapter = mockk(relaxed = true),
        updateManga = mockk(relaxed = true),
        resetEntryInfo = mockk(relaxed = true),
        getTracksInGroup = mockk(relaxed = true),
        filterChaptersForDownload = mockk(relaxed = true),
        updateMangaFromRemote = mockk(relaxed = true),
        mergeManager = mockk<MangaMergeManager> {
            coEvery { computeRelatedIds(any()) } returns group
            every { relatedIdsChanges() } returns flowOf(Unit)
        },
        mangaLibraryAdder = mockk(relaxed = true),
        removeMangaFromLibrary = mockk(relaxed = true),
        mergedChapterProvider = mockk<MergedChapterProvider> {
            coEvery { stitchOf(any()) } returns stitch
            every { merged(any(), any()) } answers { renderStoredStitch(firstArg(), secondArg()) { it.id } }
        },
        mangaPreferences = MangaPreferences(prefs),
        relatedMangasLoader = mockk(relaxed = true),
        recommendationPreferences = ReikaiRecommendationPreferences(prefs),
        relatedMangaCache = mockk(relaxed = true),
        refreshTrackerLibrary = mockk(relaxed = true),
        prepareRecommendationAssembly = mockk(relaxed = true),
        networkToLocalManga = mockk(relaxed = true),
        uiPreferences = UiPreferences(prefs),
        getFlatMetadataById = mockk<GetFlatMetadataById> {
            every { subscribe(any()) } returns flowOf(null)
            coEvery { await(any()) } returns null
        },
        getPagePreviews = mockk(relaxed = true),
        getCustomMangaInfo = mockk<GetCustomMangaInfo> { every { subscribe(any()) } returns flowOf(null) },
        setCustomMangaInfo = mockk(relaxed = true),
        sourceManager = sourceManager,
        exhPreferences = mockk(relaxed = true),
        updateHelper = mockk(relaxed = true),
        trackPorts = mockk<EntryTrackPorts> {
            every { of(any()) } returns mockk<EntryTrackPort>(relaxed = true) {
                every { tracks() } returns flowOf(emptyList())
            }
        },
        autoBindTrackers = mockk(relaxed = true),
        remoteFirstRemoval = mockk(relaxed = true),
        editChapterNumber = mockk(relaxed = true),
    )
}

/** The download manager a details model reads: [queue] queued, [progress] ticking, nothing on disk. */
internal fun detailsDownloadManager(
    queue: List<Download> = emptyList(),
    progress: Flow<Download> = emptyFlow(),
): DownloadManager = mockk<DownloadManager>(relaxed = true) {
    every { queueState } returns MutableStateFlow(queue)
    every { statusFlow() } returns emptyFlow()
    every { progressFlow() } returns progress
    every { getQueuedDownloadOrNull(any()) } returns null
    every { getQueuedDownloadsByChapterId() } returns emptyMap()
    every { getDownloadedChapterIds(any(), any()) } returns emptySet()
    every { isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
    every { getDownloadCount(any()) } returns 0
}

/** Stores a library manga [mangaId] on [sourceId], next due at [nextUpdate], with one unread chapter [chapterId]. */
internal suspend fun JdbcSqliteDriver.seedManga(
    mangaId: Long,
    chapterId: Long,
    sourceId: Long = 1L,
    nextUpdate: Long = 0L,
) {
    execute(
        null,
        "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
            "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
            "remote_update_strategy, state_chapter_next_update, state_chapter_fetch_interval, user_notes, " +
            "remote_memo) VALUES ($mangaId, $sourceId, '/manga$mangaId', 'Title', 0, 1, 0, 0, 0, 1, 0, " +
            "$nextUpdate, 0, '', '{}')",
        0,
    ).await()
    execute(
        null,
        "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
            "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
            "remote_date_upload, remote_memo) VALUES ($chapterId, $mangaId, '/$chapterId', '1', NULL, 0, " +
            "0, 0, 1.0, 1, 0, 0, '{}')",
        0,
    ).await()
}
