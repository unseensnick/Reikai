package reikai.presentation.library.novels

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.downloadedUnitsByGroup
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.LibraryQuerySource
import reikai.presentation.library.MergeCollapseInputs
import reikai.presentation.library.installedIconsBySite
import reikai.presentation.library.libraryBadgePrefsFlow
import reikai.presentation.library.mergeCollapseInputsFlow
import reikai.presentation.library.novelSourceBadge
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Every novel in the library as the library's rows: badges, merge groups collapsed to one row, nothing
 * filtered or searched. The novel library builds from [library] and [mergeInputs] through [build], since it
 * also needs the collapsed groups; Statistics takes [rows]. Twin of
 * [reikai.presentation.library.MangaLibraryRows], pinned by MergedLibraryRowConformanceTest.
 */
@Inject
class NovelLibraryRows(
    private val novelRepository: NovelRepository,
    private val novelDownloadCache: NovelDownloadCache,
    private val libraryPreferences: LibraryPreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val sourceManager: NovelSourceManager,
    private val mergeGroupRepository: MergeGroupRepository,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
    private val getNextNovelChapter: GetNextNovelChapter,
    private val basePreferences: BasePreferences,
) {

    class Rows(val groups: List<NovelMergeCollapse.CollapsedNovel>, val items: List<LibraryItem>)

    /**
     * The library's novels, re-emitted when sources (un)register, so a source resolves once loaded, and when
     * a download or delete changes the disk index, which the download badge reads.
     */
    fun library(): Flow<List<LibraryNovel>> =
        novelRepository.getLibraryNovelAsFlow()
            .combine(sourceManager.sources) { library, _ -> library }
            .combine(novelDownloadCache.changes.pausedWhileUnsubscribed()) { library, _ -> library }

    fun mergeInputs(): Flow<MergeCollapseInputs<String>> = mergeCollapseInputsFlow(
        ContentType.NOVELS,
        reikaiLibraryPreferences.preferredNovelSources.changes(),
        reikaiLibraryPreferences,
        mergeGroupRepository,
        mergedChapterUnitRepository,
    )

    fun rows(): Flow<List<LibraryItem>> =
        combine(library(), libraryBadgePrefsFlow(libraryPreferences, reikaiLibraryPreferences), mergeInputs()) {
                library,
                badges,
                merge,
            ->
            build(library, badges, merge).items
        }

    suspend fun build(
        library: List<LibraryNovel>,
        badges: LibraryBadgePrefs,
        merge: MergeCollapseInputs<String>,
    ): Rows {
        // Downloaded state is disk-derived (NovelDownloadCache), not a DB column, so fill each novel's
        // download count from the cache before it feeds the filter, sort, collapse, and badge.
        val withCounts = library.map {
            it.copy(downloadCount = novelDownloadCache.getDownloadCount(it.novel).toLong())
        }
        // Collapse merged groups into one representative entry (the top-ranked source) BEFORE filtering,
        // matching the manga library: filtering first would test each source separately, and pick the
        // representative from whichever members happened to pass. Each group's unread and downloads are
        // the deduplicated cross-source counts, which summing the members double-counted.
        val groups = NovelMergeCollapse.collapse(
            withCounts,
            merge.membership,
            merge.mergingEnabled,
            merge.overrideRankings,
            merge.preferredSources,
            mergedCountsByGroup = merge.mergedCounts,
            mergedDownloadsByGroup = if (merge.mergingEnabled) {
                mergedDownloadCounts(withCounts, merge.membership)
            } else {
                emptyMap()
            },
        )
        val iconsBySite = installedIconsBySite(sourceManager.getAll())
        // Resolved once per source: a library holds far more novels than sources.
        val querySources = HashMap<String, LibraryQuerySource>()
        val items = groups.map { group ->
            // An uninstalled source still gets a badge, as a manga group keeps its stub member.
            group.toLibraryRow(
                badges,
                merge.showSourceIcons,
                { source -> querySources.getOrPut(source) { querySource(source) } },
            ) {
                novelSourceBadge(sourceManager.get(it), iconsBySite)
            }
        }
        return Rows(groups, items)
    }

    /** The chapter a row's continue-reading opens, the first unread across the novel's merge group. */
    suspend fun resumeChapter(repNovelId: Long): NovelChapter? =
        getNextNovelChapter.awaitFirstUnreadInGroup(
            repNovelId,
            downloadedOnly = basePreferences.downloadedOnly.get(),
            downloadedIds = novelDownloadCache::downloadedChapterIds,
        )

    /**
     * Merged chapters with a copy on disk, per group. Only grouped members that have downloaded something
     * are read and probed, so a library whose merged novels hold no downloads pays nothing. Twin of the
     * manga library's, pinned by [downloadedUnitsByGroup].
     */
    private suspend fun mergedDownloadCounts(
        library: List<LibraryNovel>,
        membership: Map<Long, Long>,
    ): Map<Long, Int> {
        return downloadedUnitsByGroup(
            entries = library,
            membership = membership,
            idOf = { it.novel.id },
            downloadCountOf = { it.downloadCount },
            rowsFor = { mergedChapterUnitRepository.getDownloadUnits(ContentType.NOVELS, it) },
        ) { item, row ->
            val owner = item.novel
            novelDownloadCache.isChapterDownloaded(owner.source, owner.title, row.chapterName, row.chapterUrl)
        }
    }

    // One novel source as the search terms read it, for the row's own source and every merged member's, so
    // the two cannot resolve a name or language differently. One identity read for both, by the rule
    // NovelSourceManager.nameOf and langOf keep.
    private suspend fun querySource(source: String): LibraryQuerySource {
        val identity = sourceManager.identityOf(source)
        return LibraryQuerySource(
            key = source,
            name = identity.name.lowercase(),
            language = identity.lang.orEmpty(),
            isLocal = false,
        )
    }
}
