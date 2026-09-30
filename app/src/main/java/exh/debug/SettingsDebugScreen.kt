package exh.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TrailingWidgetBuffer
import eu.kanade.presentation.util.Screen
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

/** Komikku's debug menu: its functions and its toggles, reached from Settings, Advanced. */
class SettingsDebugScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<SettingsDebugViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "DEBUG MENU",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            Box(Modifier.fillMaxSize()) {
                val functions = state.functions
                if (functions == null) {
                    LoadingScreen()
                } else {
                    FunctionList(
                        paddingValues = paddingValues,
                        functions = functions,
                        toggles = state.toggles,
                        onRun = viewModel::run,
                        onToggle = viewModel::toggle,
                    )
                }
                RunningOverlay(visible = state.running)
            }
            state.result?.let { ResultDialog(result = it, onDismissRequest = viewModel::dismissResult) }
        }
    }

    @Composable
    private fun FunctionList(
        paddingValues: PaddingValues,
        functions: List<SettingsDebugViewModel.Function>,
        toggles: List<SettingsDebugViewModel.Toggle>,
        onRun: (SettingsDebugViewModel.Function) -> Unit,
        onToggle: (SettingsDebugViewModel.Toggle) -> Unit,
    ) {
        ScrollbarLazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = paddingValues +
                WindowInsets.navigationBars.only(WindowInsetsSides.Vertical).asPaddingValues() +
                topSmallPaddingValues,
        ) {
            item { Header("Functions") }
            items(functions) { function ->
                TextPreferenceWidget(title = function.label, onPreferenceClick = { onRun(function) })
            }
            item { HorizontalDivider() }
            item { Header("Toggles") }
            items(toggles) { toggle ->
                TextPreferenceWidget(
                    title = toggle.label,
                    subtitle = "MODIFIED".takeIf { toggle.isModified },
                    widget = {
                        Switch(
                            checked = toggle.enabled,
                            onCheckedChange = null,
                            modifier = Modifier.padding(start = TrailingWidgetBuffer),
                        )
                    },
                    onPreferenceClick = { onToggle(toggle) },
                )
            }
        }
    }

    @Composable
    private fun Header(text: String) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }

    // Swallows taps while a function runs, so a second one cannot start underneath it.
    @Composable
    private fun RunningOverlay(visible: Boolean) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color = Color.White.copy(alpha = 0.3F))
                    .pointerInput(Unit) {
                        awaitEachGesture { waitForUpOrCancellation()?.consume() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }

    @Composable
    private fun ResultDialog(result: SettingsDebugViewModel.Result, onDismissRequest: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = result.label) },
            confirmButton = {},
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = result.text)
                }
            },
        )
    }
}
