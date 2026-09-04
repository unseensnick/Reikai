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
 * Resolve the novel library's per-item metadata (source, language, status, tracking status) into a
 * [DynamicGroupingFeed] for the shared [LibraryDynamicGrouping] kernel, keyed by [EntryId]. The twin of
 * [mangaDynamicGroupingFeed][reikai.presentation.library.mangaDynamicGroupingFeed], separate only
 * because the two resolve metadata off different source managers and track tables, so any change to one
 * is a question about the other. Operates on the merge-collapsed representatives, whose tracking status
 * uses the unioned merge-group tracks.
 */
@Suppress("LongParameterList")
suspend fun novelDynamicGroupingFeed(
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
