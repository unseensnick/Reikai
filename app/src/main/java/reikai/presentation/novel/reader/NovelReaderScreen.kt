package reikai.presentation.novel.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen

/**
 * Light-novel reader: a WebView text canvas with Compose chrome. A single tap hides/shows the top +
 * bottom bars *and* the system bars together (immersive), so the reading area is unobstructed. The
 * reader theme follows the app by default ("Auto"); sepia/dark/etc. apply when chosen. Holds only
 * serializable args (Voyager serializes the screen into saved state).
 */
class NovelReaderScreen(
    private val novelId: Long,
    private val initialChapterId: Long,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { NovelReaderScreenModel(novelId, initialChapterId) }
        val state by screenModel.state.collectAsState()
        val rawSettings by screenModel.settings.collectAsState()

        // Resolve "Auto" into the effective light/dark preset; otherwise use the chosen preset as-is.
        val systemDark = isSystemInDarkTheme()
        val settings = remember(rawSettings, systemDark) {
            if (rawSettings.followSystemTheme) {
                val preset = if (systemDark) readerDarkPreset else readerLightPreset
                rawSettings.copy(backgroundColor = preset.background, textColor = preset.textColor)
            } else {
                rawSettings
            }
        }

        var menuVisible by rememberSaveable { mutableStateOf(true) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }

        // Immersive: hide the system bars while reading, reveal them with the chrome on tap. Restore
        // them when leaving the reader so the rest of the app is unaffected.
        DisposableEffect(menuVisible) {
            val window = (context as? Activity)?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (menuVisible) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {}
        }
        DisposableEffect(Unit) {
            onDispose {
                (context as? Activity)?.window?.let {
                    WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        val title = when (val s = state) {
            is NovelReaderState.Loaded -> s.chapterTitle
            is NovelReaderState.Failed -> "Error"
            NovelReaderState.Loading -> "Loading…"
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                NovelReaderState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is NovelReaderState.Failed -> TextButton(
                    onClick = { screenModel.retry() },
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text("${s.message}\n\nTap to retry")
                }
                is NovelReaderState.Loaded -> NovelReaderWebView(
                    html = s.html,
                    baseUrl = s.baseUrl,
                    settings = settings,
                    chapterTitle = s.chapterTitle,
                    initialProgressPercent = s.initialProgressPercent,
                    hasPrev = s.hasPrev,
                    hasNext = s.hasNext,
                    onToggleMenu = { menuVisible = !menuVisible },
                    onSaveProgress = screenModel::saveProgress,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
            ) {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                )
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                val loaded = state as? NovelReaderState.Loaded
                BottomAppBar(windowInsets = WindowInsets.navigationBars) {
                    IconButton(onClick = { screenModel.prev() }, enabled = loaded?.hasPrev == true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous chapter")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reader settings")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { screenModel.next() }, enabled = loaded?.hasNext == true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next chapter")
                    }
                }
            }
        }

        if (settingsOpen) {
            NovelReaderSettingsSheet(
                settings = rawSettings,
                onFontSize = screenModel::setFontSize,
                onLineHeight = screenModel::setLineHeight,
                onTextAlign = screenModel::setTextAlign,
                onPadding = screenModel::setPadding,
                onFontFamily = screenModel::setFontFamily,
                onFollowSystem = screenModel::setFollowSystemTheme,
                onPreset = screenModel::setThemePreset,
                onDismiss = { settingsOpen = false },
            )
        }
    }
}
