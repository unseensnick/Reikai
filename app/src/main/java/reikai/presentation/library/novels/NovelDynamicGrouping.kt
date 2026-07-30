package reikai.presentation.library.novels

import android.content.Context
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.data.novel.NovelStatusCode
import reikai.domain.library.LibrarySortFields
import reikai.domain.library.librarySortComparator
import reikai.domain.library.toSortMode
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelTrack
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.DynItem
import reikai.presentation.library.LibraryDynamicGrouping
import reikai.presentation.library.LibraryGroup
import reikai.presentation.library.LibraryTrackingStatusOrder
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
 * Bucket the novel library into synthetic dynamic categories, resolving the per-novel metadata (source,
 * language, status, tracking status) the shared [LibraryDynamicGrouping] kernel needs as id-keyed maps.
 * The twin of [buildMangaDynamicGrouping][reikai.presentation.library.buildMangaDynamicGrouping]: the two
 * are separate only because they resolve metadata off different source managers and track tables, so any
 * change to one is a question about the other.
 *
 * Operates on the merge-collapsed representatives, keyed by the item id so the result lines up with the
 * library's own row map. Tracking status uses each representative's unioned merge-group tracks.
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
    val dynItems = items.mapNotNull { item ->
        val novel = novelById[item.id]?.novel ?: return@mapNotNull null
        DynItem(item.id, novel.genre, novel.author, novel.artist)
    }

    val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            // The slug is the encoded disambiguator (sourceId() is never read); the name is the label.
            item.id to ((sourceManager.get(novel.source)?.name ?: novel.source) to novel.source)
        }.toMap()
    } else {
        emptyMap()
    }

    val languageCodes = if (groupType == LibraryGroup.BY_LANGUAGE) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            val lang = languageCodeOf(sourceManager.get(novel.source)?.lang.orEmpty()).takeUnless { it.isBlank() }
                ?: return@mapNotNull null
            item.id to lang
        }.toMap()
    } else {
        emptyMap()
    }

    val statusNames = if (groupType == LibraryGroup.BY_STATUS) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            item.id to context.stringResource(NovelStatusCode.toStringRes(novel.status))
        }.toMap()
    } else {
        emptyMap()
    }

    // Group by the first logged-in tracker's status on any grouped source (mirrors the manga library).
    val trackStatuses = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
        items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            val track = tracksByRep[novel.id].orEmpty()
                .firstOrNull { it.trackerId in inputs.loggedInTrackerIds } ?: return@mapNotNull null
            val statusRes = trackerManager.get(track.trackerId)?.getStatus(track.status) ?: return@mapNotNull null
            item.id to context.stringResource(statusRes)
        }.toMap()
    } else {
        emptyMap()
    }

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
        items = dynItems,
        groupType = groupType,
        inheritedSortFlag = inputs.inheritedSortFlag,
        collapsedDynamicCategories = inputs.collapsedDynamicCategories,
        collapsedDynamicAtBottom = inputs.collapsedDynamicAtBottom,
        unknownLabel = context.stringResource(MR.strings.unknown),
        notTrackedLabel = context.stringResource(MR.strings.not_tracked),
        ungroupedLabel = context.stringResource(MR.strings.group_ungrouped),
        categorySortOrder = inputs.categorySortOrder,
        sourceMeta = sourceMeta,
        languageCodes = languageCodes,
        // Render the group-by-language header as the full name ("English"), not the bare code,
        // matching the manga library; the cover badge still uses the short code separately.
        languageDisplay = { code -> Locale.forLanguageTag(code).displayName.ifBlank { code } },
        statusNames = statusNames,
        trackStatuses = trackStatuses,
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
        category to ids.mapNotNull { byId[it] }.sortedWith(comparator).map { it.id }
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
