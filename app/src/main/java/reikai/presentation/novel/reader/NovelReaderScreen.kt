package reikai.presentation.novel.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.tts.TtsPlayback
import tachiyomi.core.common.util.lang.launchNonCancellable

/**
 * Light-novel reader: a WebView text canvas with Compose chrome. A single tap hides/shows the top +
 * bottom bars *and* the system bars together (immersive), so the reading area is unobstructed. The
 * reader theme follows the app by default ("Auto"); sepia/dark/etc. apply when chosen. Holds only
 * serializable args (Voyager serializes the screen into saved state).
 */
class NovelReaderScreen(
    private val novelId: Long,
    private val initialChapterId: Long,
    private val orderedChapterIds: LongArray = longArrayOf(),
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { NovelReaderScreenModel(novelId, initialChapterId, orderedChapterIds) }
        val state by screenModel.state.collectAsState()
        val rawSettings by screenModel.settings.collectAsState()
        val overlay by screenModel.overlaySettings.collectAsState()

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
        var chaptersOpen by rememberSaveable { mutableStateOf(false) }
        var orientationOpen by rememberSaveable { mutableStateOf(false) }

        // Translucent chrome background matching the manga reader's toolbars (and the seekbar pill).
        val chromeColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

        // Reading progress (whole percent) for the vertical seekbar, and a handle the WebView registers
        // so the seekbar can scrub. Auto-scroll runs only while reading (chrome hidden).
        var progressPercent by remember { mutableStateOf(0) }
        var scrollToPercent by remember { mutableStateOf<((Int) -> Unit)?>(null) }
        val autoScrollActive = settings.autoScroll && !menuVisible

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

        // Hold the screen awake while reading when the pref is on; always clear the flag on leave so
        // the rest of the app keeps its normal timeout (the manga reader does this in ReaderActivity).
        DisposableEffect(settings.keepScreenOn) {
            val window = (context as? Activity)?.window
            if (settings.keepScreenOn) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        // Custom reader brightness: positive sets the window brightness, negative dims below the system
        // minimum via the overlay (handled by ReaderContentOverlay), 0 / off follows the system. Restore
        // system brightness on leave (the manga reader does this in ReaderActivity).
        DisposableEffect(overlay.customBrightness, overlay.customBrightnessValue) {
            (context as? Activity)?.window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = when {
                        !overlay.customBrightness || overlay.customBrightnessValue == 0 ->
                            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        overlay.customBrightnessValue > 0 -> overlay.customBrightnessValue / 100f
                        else -> 0.01f
                    }
                }
            }
            onDispose {
                (context as? Activity)?.window?.let { w ->
                    w.attributes = w.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }

        // Lock the screen to this novel's resolved orientation while reading: apply on every change,
        // and restore free rotation once on leave via a separate DisposableEffect(Unit) (the same
        // idiom as the system-bars restore above). MainActivity has no fixed orientation, so
        // UNSPECIFIED is the correct restore. NOTE: Android 16 (targetSdk 36) ignores app orientation
        // requests on large screens (foldables unfolded, tablets), so this is a no-op there by OS
        // policy; it takes effect on phones and folded covers.
        LaunchedEffect(settings.resolvedOrientation) {
            (context as? Activity)?.requestedOrientation =
                ReaderOrientation.fromPreference(settings.resolvedOrientation).flag
        }
        DisposableEffect(Unit) {
            onDispose { (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }

        // Record reading history when the reader is backgrounded (ON_PAUSE) or left (screen pop): the
        // novel twin of ReaderActivity.onPause -> updateHistory. The host Activity's lifecycleScope is
        // used (not this screen's, which is cancelled on pop) and the write is non-cancellable so it
        // survives teardown. Chapter switches are already recorded in the ScreenModel's goTo.
        val activity = context as? ComponentActivity
        DisposableEffect(activity) {
            val observer = object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    activity?.lifecycleScope?.launchNonCancellable { screenModel.updateHistory() }
                }
            }
            activity?.lifecycle?.addObserver(observer)
            onDispose {
                activity?.lifecycle?.removeObserver(observer)
                activity?.lifecycleScope?.launchNonCancellable { screenModel.updateHistory() }
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
                is NovelReaderState.Loaded -> {
                    // Seed the seekbar from the chapter's resume position on (re)load.
                    LaunchedEffect(s.chapterTitle, s.initialProgressPercent) {
                        progressPercent = s.initialProgressPercent
                    }
                    NovelReaderWebView(
                        html = s.html,
                        baseUrl = s.baseUrl,
                        settings = settings,
                        chapterTitle = s.chapterTitle,
                        initialProgressPercent = s.initialProgressPercent,
                        hasPrev = s.hasPrev,
                        hasNext = s.hasNext,
                        onToggleMenu = { menuVisible = !menuVisible },
                        onSaveProgress = { pct ->
                            progressPercent = pct
                            screenModel.saveProgress(pct)
                        },
                        onNavigate = { forward -> if (forward) screenModel.next() else screenModel.prev() },
                        autoScrollActive = autoScrollActive,
                        autoScrollSpeed = settings.autoScrollSpeed,
                        onScrollHandleReady = { scrollToPercent = it },
                        ttsController = screenModel.ttsController,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Brightness dim + colour-filter tint, above the WebView but below the chrome so the
            // toolbars stay crisp. Draw-only (no pointer input), so taps/scroll pass to the WebView.
            ReaderContentOverlay(
                brightness = if (overlay.customBrightness) overlay.customBrightnessValue else 0,
                color = overlay.colorFilterValue.takeIf { overlay.colorFilter },
                colorBlendMode = ColorFilterMode.getOrNull(overlay.colorFilterMode)?.second,
            )

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
                    actions = {
                        val loaded = state as? NovelReaderState.Loaded
                        val webUrl = loaded?.webUrl
                        if (webUrl != null) {
                            val webTitle = loaded?.chapterTitle
                            IconButton(onClick = {
                                navigator.push(WebViewScreen(url = webUrl, initialTitle = webTitle, sourceId = null))
                            }) {
                                Icon(Icons.Outlined.Public, contentDescription = "Open chapter in WebView")
                            }
                        }
                        val bookmarked = loaded?.bookmarked
                        if (bookmarked != null) {
                            IconButton(onClick = { screenModel.toggleBookmark() }) {
                                Icon(
                                    if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = chromeColor),
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
                BottomAppBar(
                    containerColor = chromeColor,
                    windowInsets = WindowInsets.navigationBars,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { screenModel.prev() }, enabled = loaded?.hasPrev == true) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous chapter")
                        }
                        IconButton(onClick = { chaptersOpen = true }) {
                            Icon(Icons.Outlined.FormatListNumbered, contentDescription = "Chapters")
                        }
                        IconButton(onClick = { orientationOpen = true }) {
                            Icon(
                                ReaderOrientation.fromPreference(settings.orientation).icon,
                                contentDescription = "Rotation",
                            )
                        }
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Reader settings")
                        }
                        IconButton(onClick = { screenModel.next() }, enabled = loaded?.hasNext == true) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next chapter")
                        }
                    }
                }
            }

            // Vertical progress seekbar on the right edge, shown with the chrome when enabled.
            AnimatedVisibility(
                visible = menuVisible && settings.verticalSeekbar,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                VerticalSeekbar(
                    value = progressPercent.toFloat(),
                    onValueChange = { v ->
                        progressPercent = v.roundToInt()
                        scrollToPercent?.invoke(v.roundToInt())
                    },
                )
            }

            // Floating read-aloud puck (shown only when TTS is on). Drawn last so it overlays the
            // text + chrome; empty areas of its container pass taps through to the WebView.
            if (settings.ttsEnabled) {
                val ttsPlayback by screenModel.ttsController.playback.collectAsState()
                val initialPos = remember { screenModel.ttsButtonPosition() }
                NovelTtsFloatingButton(
                    playing = ttsPlayback == TtsPlayback.Playing,
                    initialX = initialPos.first,
                    initialY = initialPos.second,
                    onToggle = { screenModel.ttsController.toggle() },
                    onStop = { screenModel.ttsController.stop() },
                    onMoved = { x, y -> screenModel.setTtsButtonPosition(x, y) },
                )
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
                onKeepScreenOn = screenModel::setKeepScreenOn,
                onOrientation = screenModel::setOrientation,
                onTtsEnabled = screenModel::setTtsEnabled,
                onTtsRate = screenModel::setTtsRate,
                onTtsPitch = screenModel::setTtsPitch,
                onTtsAutoPageAdvance = screenModel::setTtsAutoPageAdvance,
                onTtsScrollToTop = screenModel::setTtsScrollToTop,
                onTtsEngine = screenModel::setTtsEngine,
                onTtsVoice = screenModel::setTtsVoice,
                onTtsLanguages = screenModel::setTtsLanguages,
                ttsEngines = { screenModel.ttsController.availableEngines() },
                ttsVoices = { screenModel.ttsController.availableVoices() },
                currentTtsEngine = screenModel.ttsEnginePackage(),
                currentTtsVoice = screenModel.ttsVoiceName(),
                currentTtsLanguages = screenModel.ttsLanguages(),
                onBionicReading = screenModel::setBionicReading,
                onRemoveExtraSpacing = screenModel::setRemoveExtraSpacing,
                onTapToScroll = screenModel::setTapToScroll,
                onSwipeGestures = screenModel::setSwipeGestures,
                onAutoScroll = screenModel::setAutoScroll,
                onAutoScrollSpeed = screenModel::setAutoScrollSpeed,
                onVerticalSeekbar = screenModel::setVerticalSeekbar,
                overlay = overlay,
                onCustomBrightness = screenModel::setCustomBrightness,
                onCustomBrightnessValue = screenModel::setCustomBrightnessValue,
                onColorFilter = screenModel::setColorFilter,
                onColorFilterValue = screenModel::setColorFilterValue,
                onColorFilterMode = screenModel::setColorFilterMode,
                onDismiss = { settingsOpen = false },
            )
        }

        if (chaptersOpen) {
            var chapters by remember { mutableStateOf<List<NovelChapter>?>(null) }
            var sourceNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
            LaunchedEffect(Unit) {
                val list = screenModel.chapterList()
                sourceNames = screenModel.chapterSourceNames(list)
                chapters = list
            }
            val downloadQueue by screenModel.downloadQueue.collectAsState()
            NovelReaderChapterListDialog(
                onDismissRequest = { chaptersOpen = false },
                chapters = chapters,
                sourceNames = sourceNames,
                currentChapterId = screenModel.currentChapterId(),
                downloadQueue = downloadQueue,
                onClickChapter = { ch ->
                    chaptersOpen = false
                    screenModel.goToChapter(ch.id)
                },
                onBookmark = { ch -> screenModel.setChapterBookmark(ch.id, !ch.bookmark) },
                onDownloadAction = { ch, action -> screenModel.onChapterDownloadAction(ch, action) },
            )
        }

        if (orientationOpen) {
            NovelReaderOrientationDialog(
                currentOrientation = settings.orientation,
                onChange = screenModel::setOrientation,
                onDismiss = { orientationOpen = false },
            )
        }
    }
}


/** The reader's right-edge progress seekbar, matching the manga ChapterNavigator's long-strip slider:
 *  the same Material3 [VerticalSlider] in the same rounded translucent pill, theme-colored, 0% at the
 *  top with the fill growing downward as you read. Just the seekbar: no chapter-skip buttons or
 *  page labels. */
@Composable
private fun VerticalSeekbar(value: Float, onValueChange: (Float) -> Unit) {
    // Rest the thumb just shy of the bottom so it never pins to the literal end (where M3's slider
    // makes it finicky to grab); only the end is a problem, the top is fine. Scrubbing still reaches
    // 0 / 100%; only the thumb's resting display is inset. Keeps the default thumb + track, so it
    // matches the manga reader exactly.
    val state = rememberSliderState(
        value = value.coerceIn(0f, SEEKBAR_MAX),
        steps = SEEKBAR_STEPS,
        valueRange = 0f..100f,
    )
    state.value = value.coerceIn(0f, SEEKBAR_MAX)
    state.onValueChange = onValueChange
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    // Round the background (not a clip) so nothing crops the thumb at the ends.
    Column(
        modifier = Modifier
            .fillMaxHeight(0.72f)
            .padding(end = 8.dp)
            .background(backgroundColor, RoundedCornerShape(24.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Reading progress as a percent (like LNReader's reader): current at top, 100 at the bottom.
        Text(value.roundToInt().toString())
        VerticalSlider(
            state = state,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        Text("100")
    }
}

private const val SEEKBAR_STEPS = 33
private const val SEEKBAR_MAX = 99f
