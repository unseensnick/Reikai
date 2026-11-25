package reikai.presentation.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelCover
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaCover
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
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val mangaPerSeries: MangaUpdatesPerSeries,
    private val novelMergeManager: NovelMergeManager,
    private val getEntryCustomInfo: GetEntryCustomInfo,
) {

    // Seeded at process start, then kept by the receiver's enabled and disabled broadcasts.
    private val placed = MutableStateFlow(false)

    fun setPlaced(placed: Boolean) {
        this.placed.value = placed
    }

    context(context: Context)
    fun init(scope: CoroutineScope) {
        // A call into the system server, so off the first frame. It only raises the flag, so an enable
        // broadcast that lands first is not undone.
        scope.launch(Dispatchers.Default) {
            val receiver = ComponentName(context, UnifiedUpdatesGlanceReceiver::class.java)
            // Null where the device has no widget host at all.
            val seen = AppWidgetManager.getInstance(context)?.getAppWidgetIds(receiver)?.isNotEmpty() == true
            if (seen) placed.value = true
        }
        unifiedWidgetRefreshes(
            placed = placed,
            updates = unifiedWidgetUpdates(
                getUpdates,
                novelRepository,
                reikaiLibraryPreferences.shownContent.changes(),
                reikaiLibraryPreferences.seriesMergingEnabled.changes(),
                mangaPerSeries,
                novelMergeManager,
                getEntryCustomInfo.subscribeAll(),
            ),
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

/** What a widget's face depends on, so a change to anything here redraws it. */
internal data class UnifiedWidgetDrawn(
    val mangaCovers: List<MangaCover>,
    val novelCovers: List<NovelCover>,
    val shown: ContentType,
    val locked: Boolean,
)

/** What the driver redraws on: nothing while no combined widget is placed, so the queries stay closed. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun unifiedWidgetRefreshes(
    placed: Flow<Boolean>,
    updates: Flow<UnifiedWidgetUpdates>,
    locked: Flow<Boolean>,
): Flow<UnifiedWidgetDrawn> = placed.flatMapLatest { isPlaced ->
    if (!isPlaced) return@flatMapLatest emptyFlow()
    combine(updates, locked) { updates, locked ->
        UnifiedWidgetDrawn(
            updates.manga.map { it.coverData },
            updates.novel.map { it.coverData },
            updates.shown,
            locked,
        )
    }
        .distinctUntilChanged()
}
