package reikai.presentation.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import mihon.app.di.appGraph

class UnifiedUpdatesGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = UnifiedUpdatesGlanceWidget()

    // Sent for the first widget placed and the last one removed, not for each one.
    override fun onEnabled(context: Context) {
        context.appGraph.unifiedUpdatesWidgetManager.setPlaced(true)
    }

    override fun onDisabled(context: Context) {
        context.appGraph.unifiedUpdatesWidgetManager.setPlaced(false)
    }
}
