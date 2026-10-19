package reikai.presentation.library

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.source.getMainSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.downloadedUnitsByGroup
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

/**
 * Every manga in the library as the library's rows: badges, merge groups collapsed to one row, nothing
 * filtered or searched. The library filters these, and Statistics lists them as they are, so a badge or
 * a merged count reads the same on both. The novel twin is [reikai.presentation.library.novels.NovelLibraryRows],
 * pinned by MergedLibraryRowConformanceTest.
 */
@Inject
class MangaLibraryRows(
    private val getLibraryManga: GetLibraryManga,
    private val libraryPreferences: LibraryPreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val mergeGroupRepository: MergeGroupRepository,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
) {

    fun rows(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            libraryBadgePrefsFlow(libraryPreferences, reikaiLibraryPreferences),
            downloadCache.changes.pausedWhileUnsubscribed(),
            // Re-collapse when the merge inputs change. The recognized chapter counts are the ones the
            // stitch ranks its trunk on, so the row and the details list lead on one source.
            mergeCollapseInputsFlow(
                ContentType.MANGA,
                reikaiLibraryPreferences.preferredMangaSources.changes(),
                reikaiLibraryPreferences,
                mergeGroupRepository,
                mergedChapterUnitRepository,
            ),
            mergedChapterUnitRepository.getRecognizedChapterCountsAsFlow(),
        ) { libraryManga, preferences, _, mergePrefs, recognizedChapterCounts ->
            val sources = libraryManga
                .mapTo(mutableSetOf()) { it.manga.source }
                .associateWith { sourceManager.getOrStub(it) }
            val downloadCounts = downloadManager.getDownloadCounts(libraryManga.map { it.manga })
            val items = libraryManga.map { manga ->
                val source = sources.getValue(manga.manga.source)
                val downloadCount = downloadCounts.getValue(manga.id)
                LibraryItem(
                    libraryManga = manga,
                    downloadCount = downloadCount,
                    unreadCount = manga.unreadCount,
                    isLocal = manga.manga.isLocal(),
                    sourceName = source.name.lowercase(),
                    sourceLanguage = source.lang,
                    // Non-null only for gallery/metadata sources, which selects the tag-search path.
                    metadataSourceName = source.getMainSource<MetadataSource<*, *>>()
                        ?.let { source.getNameForMangaInfo() },
                    // The badge gates are libraryBadgePrefsFlow's, shared with the novel row builder
                    badges = preferences.badges(
                        downloadCount = downloadCount,
                        unreadCount = manga.unreadCount,
                        isLocal = manga.manga.isLocal(),
                        sourceLanguage = source.lang,
                        sourceBadge = SourceBadge.Manga(sourceManager.badgeSource(manga.manga.source)),
                    ),
                )
            }
            // Collapse persisted merge groups into one entry per group. Returns the RAW items: search,
            // filter and sort all read these, so the display-only custom-info overlay is applied later.
            MangaMergeCollapse.collapse(
                items = items,
                membership = mergePrefs.membership,
                mergingEnabled = mergePrefs.mergingEnabled,
                showMergeSourceIcons = mergePrefs.showSourceIcons,
                resolveSource = sourceManager::badgeSource,
                mergedCountsByGroup = if (mergePrefs.mergingEnabled) mergePrefs.mergedCounts else emptyMap(),
                // The same treatment for downloads, which summing the members double-counted for every
                // chapter two of them hold.
                mergedDownloadsByGroup = if (mergePrefs.mergingEnabled) {
                    mergedDownloadCounts(items, mergePrefs.membership)
                } else {
                    emptyMap()
                },
                badgePrefs = preferences,
                overrideRankings = mergePrefs.overrideRankings,
                preferredSourceIds = mergePrefs.preferredSources,
                recognizedChapterCounts = recognizedChapterCounts,
            )
        }
    }

    /**
     * Merged chapters with a copy on disk, per group. Only grouped members that have downloaded something
     * are read and probed, so a library whose merged entries hold no downloads pays nothing.
     */
    private suspend fun mergedDownloadCounts(
        items: List<LibraryItem>,
        membership: Map<Long, Long>,
    ): Map<Long, Int> {
        return downloadedUnitsByGroup(
            entries = items,
            membership = membership,
            idOf = { it.libraryManga.manga.id },
            downloadCountOf = { it.downloadCount.toLong() },
            rowsFor = { mergedChapterUnitRepository.getDownloadUnits(ContentType.MANGA, it) },
        ) { item, row ->
            val owner = item.libraryManga.manga
            downloadManager.isChapterDownloaded(
                row.chapterName,
                row.scanlator,
                row.chapterUrl,
                owner.title,
                owner.source,
            )
        }
    }
}
