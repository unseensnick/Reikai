package reikai.presentation

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import kotlin.reflect.KClass

/**
 * Hosts a composable as the app's setComposeContent does, without the app graph: the preview theme
 * reads no preferences, [viewModels] stands in for the graph's view-model factory, so a screen
 * that calls metroViewModel gets the model the test built, and [resultEventBus] stands in for the
 * activity's result bus.
 */
@Composable
fun ComposeTestHost(
    viewModels: Map<KClass<out ViewModel>, () -> ViewModel> = emptyMap(),
    resultEventBus: ResultEventBus = remember { ResultEventBus() },
    content: @Composable () -> Unit,
) {
    TachiyomiPreviewTheme {
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodySmall,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalMetroViewModelFactory provides TestViewModelFactory(viewModels),
            LocalResultEventBus provides resultEventBus,
            content = content,
        )
    }
}

private class TestViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
) : MetroViewModelFactory()
