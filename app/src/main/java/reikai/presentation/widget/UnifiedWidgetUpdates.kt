package reikai.presentation.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
)

/**
 * The rows [UnifiedUpdatesGlanceWidget] draws and [UnifiedUpdatesWidgetManager] watches, built once so the
 * refresh driver can never watch a different set from the one on screen. Unread is asked in SQL on both
 * types: filtered after the row cap, reading a chapter left the watched set unchanged and nothing redrew.
 */
internal fun unifiedWidgetUpdates(
    getUpdates: GetUpdates,
    novelRepository: NovelRepository,
): Flow<UnifiedWidgetUpdates> {
    val after = BaseUpdatesGridGlanceWidget.DateLimit.toEpochMilliseconds()
    return combine(
        getUpdates.subscribe(read = false, after = after),
        novelRepository.getFilteredNovelUpdatesAsFlow(
            after = after,
            limit = RECENTS_FEED_LIMIT,
            unread = true,
            started = null,
            bookmarked = null,
            includedCategories = emptyList(),
            excludedCategories = emptyList(),
        ),
        ::UnifiedWidgetUpdates,
    )
}
