package reikai.presentation.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleCoroutineScope
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.presentation.widget.BaseUpdatesGridGlanceWidget

/**
 * Refresh driver for [UnifiedUpdatesGlanceWidget]. Mihon's WidgetManager only watches manga updates
 * and lives in presentation-widget (which can't see novel flows), so the unified widget gets its own
 * driver here, refreshing when manga updates, novel updates, or the app-lock toggle change.
 */
class UnifiedUpdatesWidgetManager(
    private val getUpdates: GetUpdates,
    private val novelRepository: NovelRepository,
    private val securityPreferences: SecurityPreferences,
) {

    fun Context.init(scope: LifecycleCoroutineScope) {
        val after = BaseUpdatesGridGlanceWidget.DateLimit.toEpochMilli()
        combine(
            getUpdates.subscribe(read = false, after = after),
            novelRepository.getRecentNovelUpdatesAsFlow(after, ROW_LIMIT),
            securityPreferences.useAuthenticator.changes(),
        ) { manga, novel, locked ->
            Triple(
                manga.map { it.chapterId }.toSet(),
                novel.map { it.chapterId }.toSet(),
                locked,
            )
        }
            .distinctUntilChanged()
            .onEach {
                try {
                    UnifiedUpdatesGlanceWidget().updateAll(this)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to update unified updates widget" }
                }
            }
            .flowOn(Dispatchers.Default)
            .launchIn(scope)
    }

    companion object {
        private const val ROW_LIMIT = 500L
    }
}
