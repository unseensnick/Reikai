package reikai.presentation.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleCoroutineScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.updates.interactor.GetUpdates

/**
 * Refresh driver for [UnifiedUpdatesGlanceWidget]. Mihon's WidgetManager only watches manga updates
 * and lives in presentation-widget (which can't see novel flows), so the unified widget gets its own
 * driver here, refreshing when manga updates, novel updates, or the app-lock toggle change while a
 * combined widget is placed.
 */
@Inject
@SingleIn(AppScope::class)
class UnifiedUpdatesWidgetManager(
    private val getUpdates: GetUpdates,
    private val novelRepository: NovelRepository,
    private val securityPreferences: SecurityPreferences,
) {

    // Seeded at process start, then kept by the receiver's enabled and disabled broadcasts.
    private val placed = MutableStateFlow(false)

    fun setPlaced(placed: Boolean) {
        this.placed.value = placed
    }

    context(context: Context)
    fun init(scope: LifecycleCoroutineScope) {
        val receiver = ComponentName(context, UnifiedUpdatesGlanceReceiver::class.java)
        // Null where the device has no widget host at all.
        placed.value = AppWidgetManager.getInstance(context)?.getAppWidgetIds(receiver)?.isNotEmpty() == true
        unifiedWidgetRefreshes(
            placed = placed,
            updates = unifiedWidgetUpdates(getUpdates, novelRepository),
            locked = securityPreferences.useAuthenticator.changes(),
        )
            .onEach {
                try {
                    UnifiedUpdatesGlanceWidget().updateAll(context)
                } catch (e: Exception) {
                    this.logcat(LogPriority.ERROR, e) { "Failed to update unified updates widget" }
                }
            }
            .flowOn(Dispatchers.Default)
            .launchIn(scope)
    }
}

/** What the driver redraws on: nothing while no combined widget is placed, so the queries stay closed. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun unifiedWidgetRefreshes(
    placed: Flow<Boolean>,
    updates: Flow<UnifiedWidgetUpdates>,
    locked: Flow<Boolean>,
): Flow<Triple<Set<Long>, Set<Long>, Boolean>> = placed.flatMapLatest { isPlaced ->
    if (!isPlaced) return@flatMapLatest emptyFlow()
    combine(updates, locked) { updates, locked ->
        Triple(
            updates.manga.map { it.chapterId }.toSet(),
            updates.novel.map { it.chapterId }.toSet(),
            locked,
        )
    }
        .distinctUntilChanged()
}
