package reikai.presentation.library

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import java.util.Locale

/**
 * The grouping inputs the manga library reads out of its display state, so the builder below takes one
 * argument instead of five loose ones.
 */
data class MangaGroupingInputs(
    val groupLibraryBy: Int,
    val categorySortOrder: Int,
    val collapsedDynamicCategories: Set<String>,
    val collapsedDynamicAtBottom: Boolean,
    val showHiddenCategories: Boolean,
)

fun ReikaiLibraryState.groupingInputs() = MangaGroupingInputs(
    groupLibraryBy = groupLibraryBy,
    categorySortOrder = categorySortOrder,
    collapsedDynamicCategories = collapsedDynamicCategories,
    collapsedDynamicAtBottom = collapsedDynamicAtBottom,
    showHiddenCategories = showHiddenCategories,
)

/** Order the category buckets (0 = manual/DB order, 1 = A->Z, 2 = Z->A; system pinned on top). */
fun Map<Category, List<Long>>.reorderReikaiCategories(categorySortOrder: Int): Map<Category, List<Long>> {
    if (categorySortOrder == 0 || isEmpty()) return this
    return reikaiSortCategories(keys.toList(), categorySortOrder).associateWith { getValue(it) }
}

/**
 * Bucket the manga library into synthetic dynamic categories, resolving the per-manga metadata (source,
 * language, status, tracking status) the shared [LibraryDynamicGrouping] kernel needs as id-keyed maps.
 * The novel library has its own twin of this, since the two resolve metadata off different source
 * managers and track tables.
 */
@Suppress("LongParameterList")
fun buildMangaDynamicGrouping(
    favorites: List<LibraryItem>,
    tracksMap: Map<Long, List<Track>>,
    loggedInTrackerIds: Set<Long>,
    inputs: MangaGroupingInputs,
    inheritedSortFlag: Long,
    sourceManager: SourceManager,
    trackerManager: TrackerManager,
    context: Context,
): Map<Category, List<Long>> {
    val groupType = inputs.groupLibraryBy
    val library = favorites.map { it.libraryManga }

    val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
        library.associate { lm ->
            val source = sourceManager.getOrStub(lm.manga.source)
            lm.manga.id to (source.name to source.id.toString())
        }
    } else {
        emptyMap()
    }

    val languageCodes = if (groupType == LibraryGroup.BY_LANGUAGE) {
        library.mapNotNull { lm ->
            val lang = sourceManager.getOrStub(lm.manga.source).lang.takeUnless { it.isBlank() }
                ?: return@mapNotNull null
            lm.manga.id to lang
        }.toMap()
    } else {
        emptyMap()
    }

    val statusNames = if (groupType == LibraryGroup.BY_STATUS) {
        library.associate { lm -> lm.manga.id to context.stringResource(mapMangaStatus(lm.manga.status)) }
    } else {
        emptyMap()
    }

    val trackStatuses = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
        favorites.mapNotNull { item ->
            val mangaId = item.libraryManga.manga.id
            // Union tracks across the merged group (relatedMangaIds), so a status bound on any grouped
            // source groups the row, matching the tracker filter/sort and the novel library.
            val groupIds = item.relatedMangaIds.ifEmpty { listOf(mangaId) }
            val track = groupIds.flatMap { tracksMap[it].orEmpty() }
                .firstOrNull { it.trackerId in loggedInTrackerIds }
                ?: return@mapNotNull null
            val statusRes = trackerManager.get(track.trackerId)?.getStatus(track.status)
                ?: return@mapNotNull null
            mangaId to context.stringResource(statusRes)
        }.toMap()
    } else {
        emptyMap()
    }

    // Order the track-status buckets by each tracker's own status list (Reading first, Dropped last)
    // instead of alphabetically; identity for other groupings, which the kernel ignores anyway.
    val trackingStatusOrder: (String) -> String = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
        LibraryTrackingStatusOrder.build(
            loggedInTrackerIds.mapNotNull { trackerManager.get(it) },
        ) { context.stringResource(it) }
    } else {
        { it }
    }

    return LibraryDynamicGrouping.build(
        items = library.map { DynItem(it.manga.id, it.manga.genre, it.manga.author, it.manga.artist) },
        groupType = groupType,
        inheritedSortFlag = inheritedSortFlag,
        collapsedDynamicCategories = inputs.collapsedDynamicCategories,
        collapsedDynamicAtBottom = inputs.collapsedDynamicAtBottom,
        unknownLabel = context.stringResource(MR.strings.unknown),
        notTrackedLabel = context.stringResource(MR.strings.not_tracked),
        ungroupedLabel = context.stringResource(MR.strings.group_ungrouped),
        categorySortOrder = inputs.categorySortOrder,
        sourceMeta = sourceMeta,
        trackStatuses = trackStatuses,
        languageCodes = languageCodes,
        statusNames = statusNames,
        languageDisplay = { code -> displayLanguage(code) },
        trackingStatusOrder = trackingStatusOrder,
    )
}

private fun mapMangaStatus(status: Long): StringResource = when (status.toInt()) {
    SManga.ONGOING -> MR.strings.ongoing
    SManga.COMPLETED -> MR.strings.completed
    SManga.LICENSED -> MR.strings.licensed
    SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
    SManga.CANCELLED -> MR.strings.cancelled
    SManga.ON_HIATUS -> MR.strings.on_hiatus
    else -> MR.strings.unknown
}

private fun displayLanguage(code: String): String =
    Locale.forLanguageTag(code).displayName.ifBlank { code }
