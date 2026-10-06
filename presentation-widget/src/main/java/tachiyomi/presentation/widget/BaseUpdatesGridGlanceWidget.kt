package tachiyomi.presentation.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import mihon.core.metro.metroGraph
import mihon.presentation.widget.di.PresentationWidgetGraph
import reikai.presentation.widget.loadWidgetCover
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.presentation.widget.components.CoverHeight
import tachiyomi.presentation.widget.components.CoverWidth
import tachiyomi.presentation.widget.components.LockedWidget
import tachiyomi.presentation.widget.components.UpdatesWidget
import tachiyomi.presentation.widget.util.appWidgetBackgroundRadius
import tachiyomi.presentation.widget.util.calculateRowAndColumnCount
import kotlin.time.Clock
import kotlin.time.Instant

@HasMemberInjections
abstract class BaseUpdatesGridGlanceWidget : GlanceAppWidget() {

    @Inject private lateinit var getUpdates: GetUpdates

    @Inject private lateinit var preferences: SecurityPreferences

    override val sizeMode = SizeMode.Exact

    abstract val foreground: ColorProvider
    abstract val background: ImageProvider
    abstract val topPadding: Dp
    abstract val bottomPadding: Dp

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.metroGraph<PresentationWidgetGraph>().inject(this)
        val locked = preferences.useAuthenticator.get()
        val containerModifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .appWidgetBackground()
            .padding(top = topPadding, bottom = bottomPadding)
            .appWidgetBackgroundRadius()

        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(javaClass)
        val (rowCount, columnCount) = ids
            .flatMap { manager.getAppWidgetSizes(it) }
            .maxBy { it.height.value * it.width.value }
            .calculateRowAndColumnCount(topPadding, bottomPadding)

        provideContent {
            // If app lock enabled, don't do anything
            if (locked) {
                LockedWidget(
                    foreground = foreground,
                    modifier = containerModifier,
                )
                return@provideContent
            }

            val flow = remember {
                getUpdates
                    .subscribe(false, DateLimit.toEpochMilliseconds())
                    .map { rawData ->
                        rawData.prepareData(context, rowCount, columnCount)
                    }
            }
            val data by flow.collectAsState(initial = null)
            UpdatesWidget(
                data = data,
                contentColor = foreground,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
                modifier = containerModifier,
            )
        }
    }

    // RK --> the cover request, opt-in and corner radius included, moved to the shared loadWidgetCover
    private suspend fun List<UpdatesWithRelations>.prepareData(
        context: Context,
        rowCount: Int,
        columnCount: Int,
    ): List<Pair<Long, Bitmap?>> {
        // Resize to cover size
        val widthPx = CoverWidth.value.toInt().dpToPx
        val heightPx = CoverHeight.value.toInt().dpToPx
        return withIOContext {
            this@prepareData
                .distinctBy { it.mangaId }
                .take(rowCount * columnCount)
                .map { updatesView ->
                    val bitmap = loadWidgetCover(
                        context,
                        MangaCover(
                            mangaId = updatesView.mangaId,
                            sourceId = updatesView.sourceId,
                            isMangaFavorite = true,
                            url = updatesView.coverData.url,
                            lastModified = updatesView.coverData.lastModified,
                        ),
                        widthPx,
                        heightPx,
                    )
                    Pair(updatesView.mangaId, bitmap)
                }
        }
    }
    // RK <--

    companion object {
        val DateLimit: Instant
            get() = Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())
    }
}
