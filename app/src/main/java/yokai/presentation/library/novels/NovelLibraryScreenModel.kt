package yokai.presentation.library.novels

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.mapStatus
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.i18n.MR
import yokai.novel.source.NovelSourceManager
import yokai.presentation.library.novels.state.NovelLibraryTabState
import yokai.util.lang.getString

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryScreenModel]. Collects
 * categories, library novels, the [NovelLibraryUpdater] running flag, and the
 * `lastUsedNovelCategory` preference; chains on merge / sort / grouping prefs; folds the result
 * through [NovelLibrarySectioner] (C17), [NovelLibraryGrouping] (C21), and [NovelLibrarySort]
 * (C20) for BY_DEFAULT, or through [NovelLibraryDynamicGrouping] (C22) for dynamic groupings.
 *
 * **C25** wires the full pipeline. C26+ adds selection / actions on top.
 *
 * Diverges from the manga template in three places (all locked by Phase 7 decisions):
 *
 * - **4-arg first combine, not 5** — no `downloadCache.changes` because novel downloads are
 *   stubbed (Decision #4).
 * - **BY_LANGUAGE is rejected** — [NovelLibraryDynamicGrouping] (C22) returns empty for it
 *   because Novel has no language field. Treat as BY_DEFAULT here so the user lands somewhere
 *   sensible if they had BY_LANGUAGE selected on the manga side.
 * - **No `defaultMangaOrder`-equivalent pref** — novels rely on per-category sort on the
 *   novel_categories rows (Decision #6); the synthetic Default category just inherits the
 *   library-wide sort.
 *
 * Koin DI throughout (`KoinComponent` + `by inject()`) — every novel-side dep is Koin-only.
 * Same lesson as C14d.
 */
class NovelLibraryScreenModel :
    StateScreenModel<NovelLibraryTabState>(NovelLibraryTabState.Loading), KoinComponent {

    private val context: Application by inject()
    private val preferences: PreferencesHelper by inject()
    private val novelPreferences: NovelPreferences by inject()
    private val getNovelCategories: GetNovelCategories by inject()
    private val novelRepository: NovelRepository by inject()
    private val novelChapterRepository: NovelChapterRepository by inject()
    private val novelTrackRepository: NovelTrackRepository by inject()
    private val novelSourceManager: NovelSourceManager by inject()
    private val novelLibraryUpdater: NovelLibraryUpdater by inject()

    init {
        screenModelScope.launchIO {
            combine(
                getNovelCategories.subscribe(),
                novelRepository.getLibraryNovelAsFlow(),
                novelLibraryUpdater.isRunningFlow(),
                novelPreferences.lastUsedNovelCategory().changes(),
            ) { categories, libraryNovel, isRunning, currentCategoryOrder ->
                // Placeholders overwritten by the chained .combine() calls below before the
                // snapshot reaches collectLatest (combine waits on every upstream flow).
                Snapshot(
                    categories = categories,
                    libraryNovel = libraryNovel,
                    isRunning = isRunning,
                    currentCategoryOrder = currentCategoryOrder,
                    manualMerges = emptySet(),
                    manualUnmerges = emptySet(),
                    autoMergeSameTitle = true,
                    sortPrefs = SortPrefs.DEFAULT,
                    categorySortOrder = 0,
                    groupingPrefs = GroupingPrefs.DEFAULT,
                )
            }
                .combine(novelPreferences.novelManualMerges().changes()) { snap, merges ->
                    snap.copy(manualMerges = merges)
                }
                .combine(novelPreferences.novelManualUnmerges().changes()) { snap, unmerges ->
                    snap.copy(manualUnmerges = unmerges)
                }
                .combine(novelPreferences.autoMergeSameTitle().changes()) { snap, auto ->
                    snap.copy(autoMergeSameTitle = auto)
                }
                .combine(sortPrefsFlow()) { snap, sortPrefs ->
                    snap.copy(sortPrefs = sortPrefs)
                }
                .combine(novelPreferences.categorySortOrder().changes()) { snap, cso ->
                    snap.copy(categorySortOrder = cso)
                }
                .combine(groupingPrefsFlow()) { snap, gp ->
                    snap.copy(groupingPrefs = gp)
                }
                .collectLatest { snap ->
                    val library: Map<NovelCategory, List<LibraryItem.Novel>> =
                        if (snap.groupingPrefs.groupLibraryBy == LibraryGroup.BY_DEFAULT ||
                            snap.groupingPrefs.groupLibraryBy == LibraryGroup.BY_LANGUAGE
                        ) {
                            // BY_LANGUAGE falls through to BY_DEFAULT because
                            // NovelLibraryDynamicGrouping (C22) drops that mode entirely (Novel
                            // has no language field). Without this fallback the user would see
                            // an empty library after switching tabs if the manga side had
                            // BY_LANGUAGE set.
                            buildDefaultGrouping(snap)
                        } else {
                            buildDynamicGrouping(snap)
                        }

                    val inQueue = if (snap.isRunning) {
                        library.keys.mapNotNullTo(HashSet()) { cat ->
                            cat.id?.takeIf { novelLibraryUpdater.isCategoryInQueue(it) }
                        }
                    } else {
                        emptySet()
                    }

                    mutableState.update { current ->
                        // Preserve selection + sortEpoch across reload emissions (Phase 6 lesson:
                        // a reactive re-emit must not wipe in-progress multi-select state).
                        val loaded = current as? NovelLibraryTabState.Loaded
                        NovelLibraryTabState.Loaded(
                            library = library,
                            totalItemCount = library.values.sumOf { it.size },
                            isRunning = snap.isRunning,
                            inQueueCategoryIds = inQueue,
                            currentCategoryOrder = snap.currentCategoryOrder,
                            selection = loaded?.selection.orEmpty(),
                            sortEpoch = loaded?.sortEpoch ?: 0,
                            categorySortOrder = snap.categorySortOrder,
                            collapsedDynamicCategories = snap.groupingPrefs.collapsedDynamicCategories,
                            collapsedDynamicAtBottom = snap.groupingPrefs.collapsedDynamicAtBottom,
                            libraryNovelForResolve = snap.libraryNovel,
                        )
                    }
                }
        }

        // updateFlow's null emission signals job completion: clear inQueueCategoryIds so headers
        // stop spinning the moment WorkManager flips isRunning -> false. Mid-update category
        // drops are handled by the main combine's isRunning ticks (each tick re-walks
        // categoryInQueue). Mirrors manga's secondary collector at MangaLibraryScreenModel.kt:307.
        screenModelScope.launchIO {
            novelLibraryUpdater.updateFlow.collectLatest { novelId ->
                if (novelId == null) {
                    mutableState.update { current ->
                        if (current is NovelLibraryTabState.Loaded) {
                            current.copy(inQueueCategoryIds = emptySet(), isRunning = false)
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    private fun buildDefaultGrouping(snap: Snapshot): Map<NovelCategory, List<LibraryItem.Novel>> {
        // Re-create per emission so a locale change between subscriptions picks up the
        // re-translated "Default" string, matching the manga side's behavior.
        val defaultCategory = NovelCategory.createDefault(context).apply { order = -1 }
        val sectioned = NovelLibrarySectioner.section(
            libraryNovel = snap.libraryNovel,
            userCategories = snap.categories,
            defaultCategory = defaultCategory,
            categorySortOrder = snap.categorySortOrder,
        )
        val grouped = NovelLibraryGrouping.collapse(
            library = sectioned,
            manualMerges = snap.manualMerges,
            manualUnmerges = snap.manualUnmerges,
            autoMergeSameTitle = snap.autoMergeSameTitle,
        )
        return NovelLibrarySort.sort(
            library = grouped,
            libraryDefaultMode = snap.sortPrefs.mode,
            libraryDefaultAscending = snap.sortPrefs.ascending,
            randomSeed = snap.sortPrefs.randomSeed,
            removeArticles = snap.sortPrefs.removeArticles,
        )
    }

    private suspend fun buildDynamicGrouping(snap: Snapshot): Map<NovelCategory, List<LibraryItem.Novel>> {
        val groupType = snap.groupingPrefs.groupLibraryBy
        val unknownLabel = context.getString(MR.strings.unknown)
        val notTrackedLabel = context.getString(MR.strings.not_tracked)

        // Pre-resolve the metadata maps only for the active group type so the common
        // BY_SOURCE / BY_TAG / BY_AUTHOR / BY_STATUS paths stay synchronous.
        val sourceMeta: Map<Long, Pair<String, String>> = if (groupType == LibraryGroup.BY_SOURCE) {
            snap.libraryNovel.mapNotNull { ln ->
                val id = ln.novel.id ?: return@mapNotNull null
                val source = novelSourceManager.get(ln.novel.source) ?: return@mapNotNull null
                id to (source.name to source.id)
            }.toMap()
        } else {
            emptyMap()
        }

        val statusNames: Map<Long, String> = if (groupType == LibraryGroup.BY_STATUS) {
            snap.libraryNovel.mapNotNull { ln ->
                val id = ln.novel.id ?: return@mapNotNull null
                // NovelStatusCode constants share the same int values as SManga.* so the
                // existing Context.mapStatus extension (manga side) renders novel statuses too.
                id to context.mapStatus(ln.novel.status)
            }.toMap()
        } else {
            emptyMap()
        }

        val trackStatuses: Map<Long, String> = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
            snap.libraryNovel.mapNotNull { ln ->
                val novelId = ln.novel.id ?: return@mapNotNull null
                val tracks = novelTrackRepository.getByNovelId(novelId)
                // Phase 7 has no novel TrackManager / logged-services map (Decision #5 defers
                // tracker reconciliation). Bucket by the raw status int rendered as a string;
                // the future Compose tracker UI can swap this for a service-aware lookup once
                // the novel tracker manager ships.
                tracks.firstOrNull()?.let { novelId to it.status.toString() }
            }.toMap()
        } else {
            emptyMap()
        }

        val dynamic = NovelLibraryDynamicGrouping.build(
            libraryNovel = snap.libraryNovel,
            groupType = groupType,
            librarySortingMode = snap.sortPrefs.mode.mainValue,
            librarySortingAscending = snap.sortPrefs.ascending,
            collapsedDynamicCategories = snap.groupingPrefs.collapsedDynamicCategories,
            collapsedDynamicAtBottom = snap.groupingPrefs.collapsedDynamicAtBottom,
            unknownLabel = unknownLabel,
            notTrackedLabel = notTrackedLabel,
            sourceMeta = sourceMeta,
            trackStatuses = trackStatuses,
            statusNames = statusNames,
            trackingStatusOrder = ::mapTrackingOrder,
        )

        return NovelLibrarySort.sort(
            library = dynamic,
            libraryDefaultMode = snap.sortPrefs.mode,
            libraryDefaultAscending = snap.sortPrefs.ascending,
            randomSeed = snap.sortPrefs.randomSeed,
            removeArticles = snap.sortPrefs.removeArticles,
        )
    }

    /**
     * Bundles the sort-related prefs so the main combine chain stays shallow. Drops manga's
     * `defaultMangaOrder` (novels rely on per-category sort on the novel_categories rows per
     * Decision #6) and `randomSortSeed` (deferred to C28 when `setSort()` lands; until then
     * Random mode produces an identical shuffle each emission, which is unreachable from the
     * UI until 7E ships).
     */
    private fun sortPrefsFlow() = combine(
        novelPreferences.librarySortingMode().changes(),
        novelPreferences.librarySortingAscending().changes(),
        preferences.removeArticles().changes(),
    ) { modeInt, ascending, removeArticles ->
        SortPrefs(
            mode = LibrarySort.valueOf(modeInt) ?: LibrarySort.Title,
            ascending = ascending,
            randomSeed = 0L,
            removeArticles = removeArticles,
        )
    }

    /**
     * Bundles the three dynamic-grouping prefs so the main combine chain stays shallow.
     */
    private fun groupingPrefsFlow() = combine(
        novelPreferences.groupLibraryBy().changes(),
        novelPreferences.collapsedDynamicCategories().changes(),
        novelPreferences.collapsedDynamicAtBottom().changes(),
    ) { groupBy, collapsed, atBottom -> GroupingPrefs(groupBy, collapsed, atBottom) }

    /**
     * Maps a tracker status name to a sort prefix so BY_TRACK_STATUS orders by intent
     * (Reading first, Dropped last) rather than alphabetically. Mirrors manga's
     * `MangaLibraryScreenModel.mapTrackingOrder` (MR string lookups). When novel-side tracker
     * statuses materialise (Decision #5 deferral), replace the raw `Int.toString()` path in
     * [buildDynamicGrouping] with a status-name lookup and let this map order them.
     */
    private fun mapTrackingOrder(status: String): String = with(context) {
        when (status) {
            getString(MR.strings.reading), getString(MR.strings.currently_reading) -> "1"
            getString(MR.strings.rereading) -> "2"
            getString(MR.strings.plan_to_read), getString(MR.strings.want_to_read) -> "3"
            getString(MR.strings.on_hold), getString(MR.strings.paused) -> "4"
            getString(MR.strings.completed) -> "5"
            getString(MR.strings.dropped) -> "6"
            else -> "7"
        }
    }

    private data class GroupingPrefs(
        val groupLibraryBy: Int,
        val collapsedDynamicCategories: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
    ) {
        companion object {
            val DEFAULT = GroupingPrefs(
                groupLibraryBy = LibraryGroup.BY_DEFAULT,
                collapsedDynamicCategories = emptySet(),
                collapsedDynamicAtBottom = false,
            )
        }
    }

    private data class SortPrefs(
        val mode: LibrarySort,
        val ascending: Boolean,
        val randomSeed: Long,
        val removeArticles: Boolean,
    ) {
        companion object {
            val DEFAULT = SortPrefs(
                mode = LibrarySort.Title,
                ascending = true,
                randomSeed = 0L,
                removeArticles = false,
            )
        }
    }

    private data class Snapshot(
        val categories: List<NovelCategory>,
        val libraryNovel: List<LibraryNovel>,
        val isRunning: Boolean,
        val currentCategoryOrder: Int,
        val manualMerges: Set<String>,
        val manualUnmerges: Set<String>,
        val autoMergeSameTitle: Boolean,
        val sortPrefs: SortPrefs,
        val categorySortOrder: Int,
        val groupingPrefs: GroupingPrefs,
    )
}
