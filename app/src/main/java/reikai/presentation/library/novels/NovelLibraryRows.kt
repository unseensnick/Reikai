package reikai.presentation.library.novels

import androidx.annotation.VisibleForTesting
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import mihon.core.metro.AppCoroutineScope
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.downloadedUnitsByGroup
import reikai.domain.merge.holdsChapterFiles
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.isOnDevice
import reikai.domain.novel.local.LocalNovelLanguageRepository
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.LibraryQuerySource
import reikai.presentation.library.MergeCollapseInputs
import reikai.presentation.library.SourceBadge
import reikai.presentation.library.installedIconsBySite
import reikai.presentation.library.libraryBadgePrefsFlow
import reikai.presentation.library.mergeCollapseInputsFlow
import reikai.presentation.library.novelSourceBadge
import reikai.presentation.library.sharedLibraryRows
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Every novel in the library as the library's rows: badges, merge groups collapsed to one row, nothing
 * filtered or searched. The novel library, Statistics and Hidden entries all read the one shared [rows], which
 * carries the collapsed groups beside the rows for the novel library's tracks and metadata. Twin of
 * [reikai.presentation.library.MangaLibraryRows], pinned by MergedLibraryRowConformanceTest (each type's
 * collapse and merged-row stamp, not these classes), [LibraryBadgePrefs.badges] and [downloadedUnitsByGroup].
 */
@Inject
@SingleIn(AppScope::class)
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
    private val localLanguages: LocalNovelLanguageRepository,
    @AppCoroutineScope scope: CoroutineScope,
) {

    data class Rows(val groups: List<NovelMergeCollapse.CollapsedNovel>, val items: List<LibraryItem>)

    /** The library's novels, with each local series' own language by series path. */
    private class Library(val novels: List<LibraryNovel>, val localLanguages: Map<String, String>)

    /** The library collapsed into its groups, the costly part of a build, keyed by identity. */
    private class Collapsed(
        val library: Library,
        val merge: MergeCollapseInputs<String>,
        val groups: List<NovelMergeCollapse.CollapsedNovel>,
        val sourceIds: Set<String>,
    )

    /**
     * All the rows read of the registry: each library source's badge and whether it is registered (its
     * name), so a plugin outside the library, or one whose badge stays put, rebuilds nothing.
     */
    private data class RowSources(val badges: Map<String, SourceBadge>, val registered: Set<String>)

    /**
     * The library's novels, re-emitted when a download or delete changes the disk index, which the download
     * badge reads.
     */
    private fun library(): Flow<Library> =
        novelRepository.getLibraryNovelAsFlow()
            .combine(novelDownloadCache.changes) { novels, _ -> novels }
            // A local novel's language is read when its files are, so a refresh re-labels its row.
            .combine(localLanguages.subscribeAll(), ::Library)

    private fun mergeInputs(): Flow<MergeCollapseInputs<String>> = mergeCollapseInputsFlow(
        ContentType.NOVELS,
        reikaiLibraryPreferences.preferredNovelSources.changes(),
        reikaiLibraryPreferences,
        mergeGroupRepository,
        mergedChapterUnitRepository,
    )

    val rows: Flow<Rows> = buildRows().sharedLibraryRows(scope)

    /**
     * The rows unshared, in three stages so each input reruns only what reads it: the collapse, then the
     * registry's answer per library source, then the row stamp. The rows draw from the sources registered
     * so far rather than awaiting the plugins, and redraw as one the library holds registers.
     */
    @VisibleForTesting
    internal fun buildRows(): Flow<Rows> =
        combine(library(), mergeInputs(), ::collapse)
            .combine(combine(sourceManager.sources, sourceManager.firstLoadDone, ::Pair)) { collapsed, registry ->
                collapsed to rowSources(collapsed.sourceIds, registry.first, registry.second)
            }
            .distinctUntilChanged()
            .combine(libraryBadgePrefsFlow(libraryPreferences, reikaiLibraryPreferences)) {
                    (collapsed, sources),
                    badges,
                ->
                stamp(collapsed, sources, badges)
            }

    private suspend fun collapse(library: Library, merge: MergeCollapseInputs<String>): Collapsed {
        // Downloaded state is disk-derived (NovelDownloadCache), not a DB column, so fill each novel's
        // download count from the cache before it feeds the filter, sort, collapse, and badge.
        val withCounts = library.novels.map {
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
        return Collapsed(library, merge, groups, library.novels.mapTo(HashSet()) { it.novel.source })
    }

    // Before the first load a source not registered yet may still be loading, so it is not shown as missing.
    private fun rowSources(sourceIds: Set<String>, sources: List<NovelSource>, loaded: Boolean): RowSources {
        val byId = sources.associateBy { it.id }
        val iconsBySite = installedIconsBySite(sources)
        return RowSources(
            badges = sourceIds.associateWith { id ->
                val source = byId[id]
                if (source == null && !loaded) SourceBadge.Generic else novelSourceBadge(source, iconsBySite)
            },
            registered = sourceIds.filterTo(HashSet()) { it in byId },
        )
    }

    private suspend fun stamp(collapsed: Collapsed, sources: RowSources, badges: LibraryBadgePrefs): Rows {
        // Resolved once per source: a library holds far more novels than sources.
        val querySources = HashMap<String, LibraryQuerySource>()
        val items = collapsed.groups.map { group ->
            // An uninstalled source still gets a badge, as a manga group keeps its stub member.
            group.toLibraryRow(
                badges,
                collapsed.merge.showSourceIcons,
                { source -> querySources.getOrPut(source) { querySource(source) } },
                collapsed.library.localLanguages,
            ) { sources.badges.getValue(it) }
        }
        return Rows(collapsed.groups, items)
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
            holdsFiles = { holdsChapterFiles(LocalNovelSource.isLocal(it.novel.source), it.downloadCount) },
            rowsFor = { mergedChapterUnitRepository.getDownloadUnits(ContentType.NOVELS, it) },
        ) { item, row ->
            val owner = item.novel
            novelDownloadCache.isOnDevice(owner.source, owner.title, row.chapterName, row.chapterUrl)
        }
    }

    // One novel source as the search terms read it, for the row's own source and every merged member's, so
    // the two cannot resolve a name or language differently. One identity read for both, by the rule
    // NovelSourceManager.nameOf and langOf keep. toLibraryRow puts a local series' own language over it.
    private suspend fun querySource(source: String): LibraryQuerySource {
        val identity = sourceManager.identityOf(source)
        return LibraryQuerySource(
            key = source,
            name = identity.name.lowercase(),
            language = identity.lang.orEmpty(),
            isLocal = LocalNovelSource.isLocal(source),
        )
    }
}
