package reikai.presentation.library

import android.content.Context
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import reikai.presentation.components.entryStatusRes
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import java.util.Locale

/**
 * Resolve the manga library's per-item metadata (source, language, status, tracking status) into a
 * [DynamicGroupingFeed] for the shared [LibraryDynamicGrouping] kernel, keyed by [EntryId]. The novel
 * library has its own builder, since the two resolve metadata off different source managers and track
 * tables; both label a status through [entryStatusRes].
 */
@Suppress("LongParameterList")
suspend fun mangaDynamicGroupingFeed(
    favorites: List<LibraryItem>,
    tracksMap: Map<Long, List<Track>>,
    loggedInTrackerIds: Set<Long>,
    groupType: Int,
    sourceManager: SourceManager,
    trackerManager: TrackerManager,
    context: Context,
): DynamicGroupingFeed {
    val library = favorites.map { it.libraryManga }

    val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
        library.associate { lm ->
            val source = sourceManager.getOrStub(lm.manga.source)
            EntryId.Manga(lm.manga.id) as EntryId to (source.name to source.id.toString())
        }
    } else {
        emptyMap()
    }

    val languageCodes = if (groupType == LibraryGroup.BY_LANGUAGE) {
        library.mapNotNull { lm ->
            val lang = sourceManager.getOrStub(lm.manga.source).lang.takeUnless { it.isBlank() }
                ?: return@mapNotNull null
            EntryId.Manga(lm.manga.id) as EntryId to lang
        }.toMap()
    } else {
        emptyMap()
    }

    val statusNames = if (groupType == LibraryGroup.BY_STATUS) {
        library.associate { lm ->
            EntryId.Manga(lm.manga.id) as EntryId to context.stringResource(entryStatusRes(lm.manga.status))
        }
    } else {
        emptyMap()
    }

    val trackStatuses = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
        favorites.mapNotNull { item ->
            val groupTracks = mergedGroupTracks(item.memberIds(), tracksMap)
            val statusRes = groupTrackStatus(groupTracks, loggedInTrackerIds, trackerManager)
                ?: return@mapNotNull null
            EntryId.Manga(item.id) as EntryId to context.stringResource(statusRes)
        }.toMap()
    } else {
        emptyMap()
    }

    return DynamicGroupingFeed(
        items = library.map {
            DynItem(EntryId.Manga(it.manga.id), it.manga.genre, it.manga.author, it.manga.artist)
        },
        sourceMeta = sourceMeta,
        languageCodes = languageCodes,
        statusNames = statusNames,
        trackStatuses = trackStatuses,
    )
}

/**
 * Render a group-by-language header as the full name ("English") rather than the bare code; the cover
 * badge still shows the short code separately. The engine applies it to both content types' codes
 * (LibraryEngine), so one language can never split into two differently-labelled buckets.
 */
internal fun displayLanguage(code: String): String =
    Locale.forLanguageTag(code).displayName.ifBlank { code }
