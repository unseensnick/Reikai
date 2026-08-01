package reikai.presentation.library

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import java.util.Locale

/**
 * Resolve the manga library's per-item metadata (source, language, status, tracking status) into a
 * [DynamicGroupingFeed] for the shared [LibraryDynamicGrouping] kernel, keyed by [EntryId]. The novel
 * library has its own twin, since the two resolve metadata off different source managers and track
 * tables; the manga model's own builder below and the engine's mixed assembly both consume this, so the
 * resolution rules cannot fork.
 */
@Suppress("LongParameterList")
fun mangaDynamicGroupingFeed(
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
            EntryId.Manga(lm.manga.id) as EntryId to context.stringResource(mapMangaStatus(lm.manga.status))
        }
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
            EntryId.Manga(mangaId) as EntryId to context.stringResource(statusRes)
        }.toMap()
    } else {
        emptyMap()
    }

    return DynamicGroupingFeed(
        items = library.map {
            DynItem<EntryId>(EntryId.Manga(it.manga.id), it.manga.genre, it.manga.author, it.manga.artist)
        },
        sourceMeta = sourceMeta,
        languageCodes = languageCodes,
        statusNames = statusNames,
        trackStatuses = trackStatuses,
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

/**
 * Render a group-by-language header as the full name ("English") rather than the bare code; the cover
 * badge still shows the short code separately. Shared with the novel builder and the engine's mixed
 * assembly so one language can never split into two differently-labelled buckets.
 */
internal fun displayLanguage(code: String): String =
    Locale.forLanguageTag(code).displayName.ifBlank { code }
