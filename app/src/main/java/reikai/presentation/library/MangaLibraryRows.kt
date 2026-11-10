package reikai.presentation.library

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import exh.source.getMainSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.entryLanguage
import reikai.domain.library.localFilesLanguage
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.manga.chapterSettingsOf
import reikai.domain.manga.isOnDevice
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.downloadedUnitsByGroup
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

/**
 * Every manga in the library as the library's rows: badges, merge groups collapsed to one row, nothing
 * filtered or searched. The library filters these, and Statistics lists them as they are, so a badge or
 * a merged count reads the same on both. Twin of [reikai.presentation.library.novels.NovelLibraryRows], pinned by
 * MergedLibraryRowConformanceTest (each type's collapse and merged-row stamp, not these classes),
 * [LibraryBadgePrefs.badges] and [downloadedUnitsByGroup].
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
    private val mergedChapterProvider: MergedChapterProvider,
    private val mangaPreferences: MangaPreferences,
    private val basePreferences: BasePreferences,
    private val sourcePreferences: SourcePreferences,
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
            val metadataSourceNames = sources.mapValues { (_, source) ->
                source.getMainSource<MetadataSource<*, *>>()?.let { source.getNameForMangaInfo(sourcePreferences) }
            }
            val downloadCounts = downloadManager.getDownloadCounts(libraryManga.map { it.manga })
            val items = libraryManga.map { manga ->
                manga.toLibraryItem(
                    source = sources.getValue(manga.manga.source),
                    downloadCount = downloadCounts.getValue(manga.id),
                    badgePrefs = preferences,
                    sourceBadge = mangaSourceBadge(sourceManager.badgeSource(manga.manga.source)),
                    metadataSourceName = metadataSourceNames.getValue(manga.manga.source),
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
     * The chapter a row's continue-reading opens, over the whole merge group rather than the entry's own
     * source: the badge counts a chapter read when any source's copy is, so one source alone could reopen
     * something the badge considers finished. Each chapter keeps its own mangaId, so the reader opens the
     * right source; an entry that is not merged reads its own list.
     */
    suspend fun nextUnreadChapter(manga: Manga): Chapter? {
        val group = mergedChapterProvider.load(manga)
        // The filters and order are the group's shared chapter settings, the settings owner's.
        val settings = group.chapterSettingsOf(manga)
        val hidden = mangaPreferences.hiddenChapters().get()
        val downloadedOnly = basePreferences.downloadedOnly.get()
        return withContext(Dispatchers.Default) {
            group.chapters.getNextUnread(settings, downloadedOnly, downloadManager, group, hidden)
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
            holdsFiles = { it.isLocal || it.downloadCount > 0 },
            rowsFor = { mergedChapterUnitRepository.getDownloadUnits(ContentType.MANGA, it) },
        ) { item, row ->
            val owner = item.libraryManga.manga
            downloadManager.isOnDevice(
                row.chapterName,
                row.scanlator,
                row.chapterUrl,
                owner.title,
                owner.source,
            )
        }
    }
}

/** One manga's library row, read off its [source], before merge groups collapse. */
internal fun LibraryManga.toLibraryItem(
    source: Source,
    downloadCount: Int,
    badgePrefs: LibraryBadgePrefs,
    sourceBadge: SourceBadge,
    // Non-null only for gallery/metadata sources, which selects the tag-search path.
    metadataSourceName: String?,
): LibraryItem {
    val language = entryLanguage(manga.isLocal(), manga.localFilesLanguage, source.lang)
    return LibraryItem(
        libraryManga = this,
        downloadCount = downloadCount,
        unreadCount = unreadCount,
        isLocal = manga.isLocal(),
        sourceName = source.name.lowercase(),
        sourceLanguage = language,
        metadataSourceName = metadataSourceName,
        // The badge gates are libraryBadgePrefsFlow's, shared with the novel row builder
        badges = badgePrefs.badges(
            downloadCount = downloadCount,
            unreadCount = unreadCount,
            isLocal = manga.isLocal(),
            sourceLanguage = language,
            sourceBadge = sourceBadge,
        ),
    )
}
