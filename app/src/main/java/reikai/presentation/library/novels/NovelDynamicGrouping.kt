package reikai.presentation.library.novels

import android.content.Context
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.data.novel.NovelStatusCode
import reikai.domain.entry.EntryId
import reikai.domain.library.LibrarySortFields
import reikai.domain.library.librarySortComparator
import reikai.domain.library.toSortMode
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelTrack
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.DynItem
import reikai.presentation.library.DynamicGroupingFeed
import reikai.presentation.library.LibraryDynamicGrouping
import reikai.presentation.library.LibraryGroup
import reikai.presentation.library.LibraryTrackingStatusOrder
import reikai.presentation.library.displayLanguage
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The grouping inputs the novel library reads out of its settings, so the builder below takes one
 * argument instead of six loose ones. The novel twin of
 * [MangaGroupingInputs][reikai.presentation.library.MangaGroupingInputs].
 */
data class NovelGroupingInputs(
    val groupLibraryBy: Int,
    val categorySortOrder: Int,
    val collapsedDynamicCategories: Set<String>,
    val collapsedDynamicAtBottom: Boolean,
    val loggedInTrackerIds: Set<Long>,
    val inheritedSortFlag: Long,
    val randomSeed: Long,
)

/**
 * Resolve the novel library's per-item metadata (source, language, status, tracking status) into a
 * [DynamicGroupingFeed] for the shared [LibraryDynamicGrouping] kernel, keyed by [EntryId]. The twin of
 * [mangaDynamicGroupingFeed][reikai.presentation.library.mangaDynamicGroupingFeed]: the two are separate
 * only because they resolve metadata off different source managers and track tables, so any change to one
 * is a question about the other. The novel model's own builder below and the engine's mixed assembly both
 * consume this, so the resolution rules cannot fork.
 *
 * Operates on the merge-collapsed representatives. Tracking status uses each representative's unioned
 * merge-group tracks.
 */
@Suppress("LongParameterList")
fun novelDynamicGroupingFeed(
    items: List<LibraryItem>,
    novelById: Map<Long, LibraryNovel>,
    tracksByRep: Map<Long, List<NovelTrack>>,
    loggedInTrackerIds: Set<Long>,
    groupType: Int,
    sourceManager: NovelSourceManager,
    trackerManager: TrackerManager,
    context: Context,
): DynamicGroupingFeed {
    val dynItems = items.mapNotNull { item ->
        val novel = novelById[item.id]?.novel ?: return@mapNotNull null
        DynItem<EntryId>(EntryId.Novel(item.id), novel.genre, novel.author, novel.artist)
    }

    val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            // The slug is the encoded disambiguator (sourceId() is never read); the name is the label.
            EntryId.Novel(item.id) as EntryId to
                ((sourceManager.get(novel.source)?.name ?: novel.source) to novel.source)
        }.toMap()
    } else {
        emptyMap()
    }

    val languageCodes = if (groupType == LibraryGroup.BY_LANGUAGE) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            val lang = languageCodeOf(sourceManager.get(novel.source)?.lang.orEmpty()).takeUnless { it.isBlank() }
                ?: return@mapNotNull null
            EntryId.Novel(item.id) as EntryId to lang
        }.toMap()
    } else {
        emptyMap()
    }

    val statusNames = if (groupType == LibraryGroup.BY_STATUS) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            EntryId.Novel(item.id) as EntryId to context.stringResource(NovelStatusCode.toStringRes(novel.status))
        }.toMap()
    } else {
        emptyMap()
    }

    // Group by the first logged-in tracker's status on any grouped source (mirrors the manga library).
    val trackStatuses = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            val track = tracksByRep[novel.id].orEmpty()
                .firstOrNull { it.trackerId in loggedInTrackerIds } ?: return@mapNotNull null
            val statusRes = trackerManager.get(track.trackerId)?.getStatus(track.status) ?: return@mapNotNull null
            EntryId.Novel(item.id) as EntryId to context.stringResource(statusRes)
        }.toMap()
    } else {
        emptyMap()
    }

    return DynamicGroupingFeed(
        items = dynItems,
        sourceMeta = sourceMeta,
        languageCodes = languageCodes,
        statusNames = statusNames,
        trackStatuses = trackStatuses,
    )
}

/**
 * Bucket the novel library into synthetic dynamic categories through the shared feed above; the novel
 * model's own dynamic path, returning its Long-keyed, per-bucket-sorted shape.
 */
@Suppress("LongParameterList")
fun buildNovelDynamicGrouping(
    items: List<LibraryItem>,
    byId: Map<Long, LibraryItem>,
    novelById: Map<Long, LibraryNovel>,
    tracksByRep: Map<Long, List<NovelTrack>>,
    inputs: NovelGroupingInputs,
    defaultSort: LibrarySort,
    sortFields: LibrarySortFields<LibraryItem>,
    sourceManager: NovelSourceManager,
    trackerManager: TrackerManager,
    context: Context,
): List<Pair<Category, List<Long>>> {
    val groupType = inputs.groupLibraryBy
    val feed = novelDynamicGroupingFeed(
        items,
        novelById,
        tracksByRep,
        inputs.loggedInTrackerIds,
        groupType,
        sourceManager,
        trackerManager,
        context,
    )

    // Order the track-status buckets by each tracker's own status list (Reading first, Dropped last)
    // instead of alphabetically, sharing the manga library's helper; identity for other groupings.
    val trackingStatusOrder: (String) -> String = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
        LibraryTrackingStatusOrder.build(
            inputs.loggedInTrackerIds.mapNotNull { trackerManager.get(it) },
        ) { context.stringResource(it) }
    } else {
        { it }
    }

    val groups = LibraryDynamicGrouping.build(
        items = feed.items,
        groupType = groupType,
        inheritedSortFlag = inputs.inheritedSortFlag,
        collapsedDynamicCategories = inputs.collapsedDynamicCategories,
        collapsedDynamicAtBottom = inputs.collapsedDynamicAtBottom,
        unknownLabel = context.stringResource(MR.strings.unknown),
        notTrackedLabel = context.stringResource(MR.strings.not_tracked),
        ungroupedLabel = context.stringResource(MR.strings.group_ungrouped),
        categorySortOrder = inputs.categorySortOrder,
        sourceMeta = feed.sourceMeta,
        languageCodes = feed.languageCodes,
        languageDisplay = ::displayLanguage,
        statusNames = feed.statusNames,
        trackStatuses = feed.trackStatuses,
        trackingStatusOrder = trackingStatusOrder,
    )

    // Dynamic groups have no per-category sort, so they all use the library default sort.
    val comparator = librarySortComparator(
        defaultSort.type.toSortMode(),
        defaultSort.isAscending,
        inputs.randomSeed,
        sortFields,
    )
    return groups.map { (category, ids) ->
        category to ids.mapNotNull { byId[it.rawId] }.sortedWith(comparator).map { it.id }
    }
}

/**
 * A plugin reports its language as an English display name ("English") where the shared code expects an
 * ISO code ("en"), so reverse-map it.
 *
 * Memoized: the name to code mapping is static, but this runs for every novel on every library rebuild
 * (including each selection tap), and the reverse-map scans ~180 ISO languages per uncached name.
 */
internal fun languageCodeOf(value: String): String {
    if (value.isBlank() || value.length <= 3) return value
    return languageCodeCache.getOrPut(value) {
        Locale.getISOLanguages().firstOrNull {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).equals(value, ignoreCase = true)
        } ?: value.take(2)
    }
}

private val languageCodeCache = ConcurrentHashMap<String, String>()
