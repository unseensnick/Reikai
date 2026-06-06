package yokai.presentation.reader

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
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import yokai.util.Screen

/**
 * Unified reader shell (Phase 1). Hosts a [ReaderScreenModel] and renders the novel WebView content
 * with tap-to-toggle immersive chrome: a single tap hides/shows the top + bottom bars *and* the
 * phone's system bars together (LNReader-style), so the reading area is unobstructed. Holds only
 * serializable args and routes back via [LocalBackPress]; no constructor lambdas (Voyager serializes
 * the screen into saved state).
 */
class ReaderScreen(
    private val sourceId: String,
    private val chapterId: Long,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val backPress = LocalBackPress.current
        val context = LocalContext.current
        val screenModel = rememberScreenModel { ReaderScreenModel(sourceId, chapterId) }
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
                    WindowInsetsControllerCompat(it, it.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        val title = when (val s = state) {
            is ReaderState.Loaded -> s.chapterTitle
            is ReaderState.Failed -> "Error"
            ReaderState.Loading -> "Loading…"
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                ReaderState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is ReaderState.Failed -> TextButton(
                    onClick = { screenModel.retry() },
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text("${s.message}\n\nTap to retry")
                }
                is ReaderState.Loaded -> NovelWebViewContent(
                    html = s.html,
                    baseUrl = s.baseUrl,
                    settings = settings,
                    chapterTitle = s.chapterTitle,
                    onToggleMenu = { menuVisible = !menuVisible },
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
                        IconButton(onClick = { backPress?.invoke() }) {
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
                // Placeholder bottom bar (Phase 1.2): holds the settings entry; chapter nav lands in 1.6.
                BottomAppBar(windowInsets = WindowInsets.navigationBars) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reader settings")
                    }
                }
            }
        }

        if (settingsOpen) {
            ReaderSettingsSheet(
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
