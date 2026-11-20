package reikai.presentation.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.recents.RECENTS_FEED_LIMIT
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.presentation.widget.BaseUpdatesGridGlanceWidget

/** The recent updates the combined widget draws, one list per content type. */
internal data class UnifiedWidgetUpdates(
    val manga: List<UpdatesWithRelations>,
    val novel: List<NovelUpdateWithRelations>,
    val shown: ContentType = ContentType.ALL,
) {
    /** A section's share of the rows: a hidden type has no section, so the other takes them all. */
    fun sectionRows(rowCount: Int): Int = if (shown == ContentType.ALL) (rowCount / 2).coerceAtLeast(1) else rowCount
}

/**
 * The rows [UnifiedUpdatesGlanceWidget] draws and [UnifiedUpdatesWidgetManager] watches, built once so the
 * refresh driver can never watch a different set from the one on screen. Unread is asked in SQL on both
 * types: filtered after the row cap, reading a chapter left the watched set unchanged and nothing redrew.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun unifiedWidgetUpdates(
    getUpdates: GetUpdates,
    novelRepository: NovelRepository,
    shownContent: Flow<ContentType>,
    seriesMerging: Flow<Boolean>,
): Flow<UnifiedWidgetUpdates> {
    val after = BaseUpdatesGridGlanceWidget.DateLimit
    return combine(
        seriesMerging.flatMapLatest { getUpdates.subscribe(read = false, after = after, seriesMerging = it) },
        seriesMerging.flatMapLatest {
            novelRepository.getFilteredNovelUpdatesAsFlow(
                after = after,
                limit = RECENTS_FEED_LIMIT,
                unread = true,
                started = null,
                bookmarked = null,
                includedCategories = emptyList(),
                excludedCategories = emptyList(),
                seriesMerging = it,
            )
        },
        shownContent,
    ) { manga, novel, shown ->
        UnifiedWidgetUpdates(
            manga = manga.takeIf { shown.includes(ContentType.MANGA) }.orEmpty(),
            novel = novel.takeIf { shown.includes(ContentType.NOVELS) }.orEmpty(),
            shown = shown,
        )
    }
}
