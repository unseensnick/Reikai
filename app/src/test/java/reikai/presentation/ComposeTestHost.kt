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
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.Navigator
import mihon.core.navigation.rememberNavigator
import kotlin.reflect.KClass

/**
 * Hosts a composable as the app's setComposeContent does, without the app graph: the preview theme
 * reads no preferences, [viewModels] stands in for the graph's view-model factory, so a screen
 * that calls metroViewModel gets the model the test built, [assistedFactories] does the same for
 * assistedMetroViewModel, [resultEventBus] stands in for the activity's result bus, and [navigator] for its
 * navigator, which a screen drawn straight into the host reads.
 */
@Composable
fun ComposeTestHost(
    viewModels: Map<KClass<out ViewModel>, () -> ViewModel> = emptyMap(),
    assistedFactories: Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory> =
        emptyMap(),
    resultEventBus: ResultEventBus = remember { ResultEventBus() },
    navigator: Navigator = rememberNavigator(TestRootRoute),
    content: @Composable () -> Unit,
) {
    TachiyomiPreviewTheme {
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodySmall,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalMetroViewModelFactory provides TestViewModelFactory(viewModels, assistedFactories),
            LocalResultEventBus provides resultEventBus,
            LocalNavigator provides navigator,
            content = content,
        )
    }
}

private class TestViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val manualAssistedFactoryProviders:
    Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
