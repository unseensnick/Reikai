# Novel reader

The light-novel reading surface: a WebView renders the chapter text and the rest of the screen (toolbar, prev/next, settings, immersive bars) is Compose, with per-chapter scroll resume, read-state syncing, and per-novel display settings.

This is the developer-facing record for the novel reader. It explains how the reader is built and why; for the broader rebase context see [rebase-overview.md](rebase-overview.md), and for the surrounding reader direction and plumbing see the cross-links at the end.

## Goal

Make light novels actually readable inside Reikai. Tapping a chapter on the novel details screen opens a full-screen reader that renders the chapter's HTML with book-like typography and themes, resumes where you left off, marks chapters read as you go, and walks prev/next through the novel's chapter list (across sources for a merged novel). Keep all of this feeling like part of Mihon, not a bolted-on web page.

## Why

A novel "chapter" arrives from a plugin as a blob of HTML (`NovelSource.parseChapter` returns a `String`). HTML from arbitrary sources is messy and varied, with inline images, odd markup, and relative URLs, so the proven way to render it faithfully is a real browser engine: a WebView. That is what both the old Yokai reader and LNReader do. But a raw web page styled by in-page JavaScript would look and behave like a web page, which clashes with the rebase's cohesion goal. So the reader splits the two concerns: the WebView is a pure text canvas (typography, theme, scroll), and every piece of chrome a user touches is native Compose, matching Mihon's own widgets.

This mirrors Mihon's manga reader, which is itself the one deliberate View-based holdout in an otherwise Compose + Voyager app (see [.claude/rules/architecture.md](../../../.claude/rules/architecture.md)). The novel reader is the text analog of that image surface: a focused rendering view wrapped in Compose chrome.

## Approach

### The WebView text canvas + NovelReaderHtmlBuilder

The reader builds one self-contained HTML document per chapter and hands it to a WebView. The document contains the chapter text, a bundled stylesheet that turns CSS variables into the chosen font/size/spacing/theme, and a bundled script that handles scroll-saving, font loading, and a center-tap signal. No in-page buttons, toolbars, or scrollbars: that is all Compose.

- `buildReaderHtml` (`NovelReaderHtmlBuilder.kt`) assembles the document. It wraps the chapter HTML in `<div id="LNReader-chapter">`, emits a `:root` block of `--readerSettings-*` variables (background, padding, text size/color/align, line height, font family) plus `--theme-*` variables built from the app's `MaterialTheme.colorScheme` (so links and selection match the app theme), and injects an `initialReaderConfig` JSON object.
- The bundled web layer lives under `app/src/main/assets/lnreader-web/`, copied verbatim from LNReader: `css/index.css` plus `js/{core.js, van.js, icons.js, text-vibe.js, polyfill-onscrollend.js}`. `core.js` is the `window.reader` object that applies the CSS variables reactively, loads fonts, and posts the scroll-save and tap messages. Keeping these byte-identical to upstream means LNReader fixes port cleanly.
- The native bridge stays invisible to that vendored JS: `buildReaderHtml` defines `window.ReactNativeWebView.postMessage` as a shim that forwards to an Android `@JavascriptInterface` named `NativeReader`. So `core.js` thinks it is still talking to react-native-webview and needs no edits.
- `NovelReaderWebView.kt` is the `AndroidView(WebView)` composable. It loads the document with `loadDataWithBaseURL(baseUrl, ...)` so relative image URLs in the chapter resolve against the source site. `setDefaultSettings()` plus `allowFileAccess = true` is the whole security surface: the dangerous `setAllowFileAccessFromFileURLs` / `setAllowUniversalAccessFromFileURLs` flags stay off (see [.claude/rules/security.md](../../../.claude/rules/security.md)). It also adds top padding for the display cutout so the first line clears a punch-hole in immersive mode.
- `NovelReaderWebInterface.kt` is the tiny Android side of the bridge. It parses the JSON message and dispatches only known types: `hide` (toggle the Compose chrome), `save` (scroll progress percent), and `console` (debug log). Prev/next are Compose buttons, so they never touch the bridge.

A document is rebuilt only when the chapter HTML or the app theme colors change. Display-setting changes (font, size, spacing, padding, align, theme) are pushed live without a reload via `evaluateJavascript("reader.readerSettings.val = ...")`, so adjustments apply in place.

### Compose chrome

`NovelReaderScreen.kt` is the Voyager `Screen` (serializable args only: `novelId`, `initialChapterId`, and an optional `orderedChapterIds` reading order). Its `Content()` is a `Box` holding the WebView canvas plus the chrome:

- A top `TopAppBar` (back, chapter title, WebView, bookmark) and a bottom `BottomAppBar` (previous chapter, chapters list, rotation, settings, next chapter), both `AnimatedVisibility` that slide in and out with a `menuVisible` flag. Both bars use the manga reader's translucent `surfaceColorAtElevation(3.dp)` chrome color, and the bottom bar is evenly spaced.
- A single center tap (relayed from the WebView's `hide` message) toggles `menuVisible`, which simultaneously hides/shows the chrome and the system bars via `WindowInsetsControllerCompat` (immersive reading). The system bars are restored when the screen is left so the rest of the app is unaffected.
- The settings sheet (`NovelReaderSettingsSheet.kt`) opens from the bottom bar.
- The chapters list (`NovelReaderChapterListDialog.kt`) is the in-reader jump-to-chapter sheet, the novel twin of the manga reader's `ChapterListDialog`. It reuses `MangaChapterListItem` so the rows match the novel details list and the manga reader exactly (read dot, relative date, bookmark, a working download button with start/cancel/delete + optimistic delete + live queue state), opens scrolled to the current chapter, and tapping a row jumps to it. For a merged novel it shows the unified cross-source order with a per-source label (`NovelReaderScreenModel.chapterSourceNames`); a single-source novel shows no label. The reader model mirrors the details model's `downloadQueue` / `onChapterDownloadAction` / `setChapterBookmark`. (The manga reader's own chapter list is single-source today; bringing it to this same merge parity is a queued ROADMAP item.)
- The rotation button opens `NovelReaderOrientationDialog`, the orientation picker reusing the shared `ModeSelectionDialog` icon grid (the same one the manga reader's `OrientationSelectDialog` uses), wired to the per-novel orientation flag.
- The WebView button (top bar) opens the current chapter's page on the source site (`site` + chapter path, via `NovelSource.webUrl`); it appears only when the chapter's source is loaded. The bookmark button toggles the current chapter's bookmark.

### NovelReaderScreenModel state + progress saves

`NovelReaderScreenModel.kt` (a `StateScreenModel`) owns all the loading and persistence logic. State is a `NovelReaderState` sealed interface (`Loading` / `Loaded` / `Failed`); display settings are a separate `settings: StateFlow<NovelReaderSettings>` so changing them never forces a reload.

- On open it resolves the reading order (the passed `orderedChapterIds`, which for a merged novel is the unified cross-source order, else the novel's own chapter list) and loads the current chapter. Each chapter resolves its source per `chapter.novelId`, so a merged session walks across sources.
- Chapter HTML loads through `loadChapterHtml`: a downloaded chapter reads self-contained HTML from disk (null base URL, images already inlined); otherwise it resolves the source and calls `parseChapter` live, using the source site as the base URL. Results go into a small session LRU cache (`htmlCache`, RAM only, dies with the screen). The resolved next chapter is prefetched once per open so forward paging is instant.
- `next()` / `prev()` jump to the neighbors resolved by `resolveNeighbor`, which is skip-duplicate aware (with the pref on, it walks past same-numbered chapters a merge produces).
- `saveProgress(percent)` is called from the WebView's `save` message. It stores the scroll position on the chapter (as 0..10000 hundredths of a percent, matching `lastTextProgress`), stamps the owning novel's last-read time for the library LastRead sort, and at >=97% auto-marks the chapter read, pushes progress to bound trackers (gated on `autoUpdateTrack`), and deletes the file if remove-after-read is on. On reopen the stored progress seeds the initial scroll.
- Reading history (timestamp + session duration) is stamped on chapter switch and when the reader is backgrounded or left, via the host activity's `lifecycleScope` with a non-cancellable write so it survives teardown.
- All of history, scroll, read-state, last-read, and the tracker push are gated on the global Incognito state (captured once at open).
- A forward "next" optionally marks the chapter you just left as read (`markReadOnSkip`, opt-in, forward only, never backward, never in incognito), matching the manga reader.

### Display settings

Reader display settings persist through `NovelPreferences` (`reikai.domain.novel.NovelPreferences`), preserving the Yokai-era key strings so existing installs upgrade in place: `ln_reader_font_size_sp`, `ln_reader_line_spacing`, `ln_reader_text_align`, `ln_reader_font_family`, `ln_reader_padding`, `ln_reader_follow_system_theme`, `ln_reader_bg_color`, `ln_reader_text_color`, plus the newer `ln_reader_keep_screen_on`, `ln_reader_default_orientation`, `ln_reader_skip_duplicate_chapters`, and `ln_reader_mark_read_on_skip`.

The ScreenModel reads these as a combined `StateFlow` and exposes them as `NovelReaderSettings` state (per the no-prefs-in-Composable convention). The settings sheet edits them; the WebView picks up display changes live.

Two device-level settings are applied by the screen, not the WebView:

- **Keep screen on:** when the pref is on, a `DisposableEffect` sets `FLAG_KEEP_SCREEN_ON` on the activity window and always clears it on leave so the rest of the app keeps its normal timeout.
- **Orientation lock:** per-novel orientation (stored in `novels.viewer_flags`, the twin of `Manga.viewerFlags`) resolves against a global default; a `LaunchedEffect` applies it and a `DisposableEffect(Unit)` restores free rotation on leave. Note: Android 16 (targetSdk 36) ignores app fixed-orientation on large screens (unfolded foldables, tablets), so the lock is a no-op there by OS policy and takes effect on phones and folded covers. The shipping detail lives in [novel-parity-backlog.md](novel-parity-backlog.md).

The reader theme defaults to following the app theme ("Auto", resolved to a light/dark preset in the screen); sepia / mint / dark / black presets apply when chosen, so the surface never feels disconnected on first open.

### Read-aloud (text-to-speech)

A floating puck reads the chapter aloud. The split is the same as the rest of the reader: `core.js` owns "which paragraph and the highlight", Reikai owns the voice and all the chrome.

- The loop reuses `core.js`'s TTS contract verbatim. Tapping the puck asks `core.js` to start from the paragraph at the top of the screen; it highlights that paragraph and posts a `speak` message; `NovelTtsController` voices the text through a `NovelTtsEngine` and, when the utterance finishes, tells `core.js` to advance (`tts.next()`), which re-highlights and posts the next `speak`. The engine sits behind an interface (`SystemTtsEngine` wraps Android `TextToSpeech`) so an offline neural backend can replace it without touching the bridge or the chrome.
- The control is a small draggable Compose puck (`NovelTtsFloatingButton`), not LNReader's in-page button (we still don't load `index.js`). Tap toggles play/pause, long-press stops, and it free-positions anywhere with its spot persisted; it dims while playing so the text stays readable.
- The TTS settings tab picks the engine and voice (the voice list filters by language), speed, pitch, auto-advance to the next chapter, and scroll-to-top. Rate/pitch/voice are applied to the native engine; the WebView's `tts` block is only steering `core.js`.
- Playback survives backgrounding. `NovelTtsController` mirrors its state to `NovelTtsSession`, and `NovelTtsService` (a foreground `mediaPlayback` service) keeps the process alive and renders a `MediaSession` notification with lock-screen / headset play-pause-stop. No native queue-walking is needed: the foreground service keeps the WebView's loop running in the background. The service stops itself, and the notification clears, when playback ends or the reader is closed.
- The general-settings block (`TTSEnable`) is pushed live only when it actually flips, because a `core.js` watcher rebuilds the chapter DOM on any `generalSettings` reassignment, which would wipe the read-aloud highlight (the `display`/`readerSettings` block reassigns freely).

## Key files

The reader and its details host are net-new `reikai.*` code. The only Mihon-file patches are the `// RK` launch sites that push `NovelReaderScreen`: `HistoryTab.kt`, `UpdatesTab.kt`, and `LibraryTab.kt`.

- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderScreen.kt`: Voyager `Screen`; Compose chrome, immersive bars, keep-screen-on + orientation effects, history-on-leave, settings sheet host.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderScreenModel.kt`: `StateScreenModel`; chapter loading + prefetch, prev/next ordering (cross-source, skip-duplicate aware), progress saves, read-state + tracker sync, incognito gating, settings flow.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderWebView.kt`: the `AndroidView(WebView)` canvas; document load, live settings push, theme-color derivation, cutout padding.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderHtmlBuilder.kt`: `buildReaderHtml` + `readerSettingsJson`; the per-chapter document, CSS variables, `initialReaderConfig`, the `ReactNativeWebView` -> `NativeReader` shim.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderWebInterface.kt`: `@JavascriptInterface` bridge (`hide` / `save` / `console`, the `core.js` TTS messages, and the `reikai-ready` ping).
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderSettings.kt`: settings data class, theme presets, font list.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderSettingsSheet.kt`: Compose settings sheet (Display / Theme / TTS tabs).
- `app/src/main/java/reikai/domain/novel/NovelPreferences.kt`: reader preference keys (the `ln_reader_*` strings above).
- `app/src/main/assets/lnreader-web/`: bundled `css/index.css` + `js/{core.js, van.js, icons.js, text-vibe.js, polyfill-onscrollend.js}`, copied verbatim from LNReader.

Read-aloud (TTS) lives in its own files:

- `app/src/main/java/reikai/domain/novel/tts/NovelTtsEngine.kt`: the engine interface + voice/engine/playback models (the seam for a future neural engine).
- `app/src/main/java/reikai/data/novel/tts/SystemTtsEngine.kt`: Android `TextToSpeech` backend.
- `app/src/main/java/reikai/presentation/novel/reader/NovelTtsController.kt`: drives the `core.js` read-aloud loop, owns playback state, mirrors it to the session.
- `app/src/main/java/reikai/presentation/novel/reader/NovelTtsFloatingButton.kt`: the draggable play/pause puck.
- `app/src/main/java/reikai/data/novel/tts/NovelTtsSession.kt` + `NovelTtsService.kt`: the foreground `mediaPlayback` service + `MediaSession` notification, and the singleton that bridges them to the controller. Mihon-file patches (`// RK`): the `androidx.media:media` dependency, the manifest service + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission, and a `Notifications` channel.

## Status

Shipped (P5 core sequence, on-device verified). Live reading with typography and themes, scroll resume, prev/next with prefetch, cross-source reading on merged novels, auto-mark-read, history, tracker sync, incognito gating, keep-screen-on, orientation lock, and mark-read-on-skip are all in place. Read-aloud (TTS) shipped as the engine-extras round 2: foreground reading with the draggable puck and settings tab, plus background playback with a lock-screen / headset media notification (on-device verified on the Fold6). Round 3 (chrome + chapter-list parity: the chapters sheet, orientation picker, top-bar WebView + bookmark, seekbar percent labels, translucent chrome) shipped in `bc3d21ed2` / `762704ec1`; compiles, on-device verification of the chapter-list sheet pending.

## Decisions & tradeoffs

**Compose owns all chrome; the WebView is text only.** LNReader's `index.js` renders an in-page ToolWrapper, buttons, and scrollbar. Reikai does not load it. Loading it would put a second, web-styled UI on top of the native one and break cohesion with the Mihon base. The cost is that the bridge and prev/next plumbing are native, which is the point.

**Most LNReader engine extras shipped; a few stay off.** `core.js` supports TTS, page-mode (vs scroll), auto-scroll, bionic reading, tap-to-scroll, volume-button paging, swipe-to-change-chapter, remove-extra-paragraph-spacing, custom CSS/JS/themes, an in-reader chapter drawer, and a progress seekbar. Shipped (engine-extras round 2): **TTS** (native, see above), **bionic reading** and **remove-extra-spacing** (`core.js` flags), **tap-edges-to-scroll** and **swipe-between-chapters** (`core.js` gestures, with `next`/`prev` routed natively), and two native builds, **auto-scroll** (an injected requestAnimationFrame scroller, paused while the chrome is shown) and the **vertical progress seekbar** (Material3 `VerticalSlider`, the same primitive the manga reader's `ChapterNavigator` uses). What stays off:

- **Volume-button paging** needs Activity-level key interception (not a `core.js` flag); stays off.
- **Page-mode** (`core.js` supports it, but it reworks the scroll-and-progress model and is LNReader-"Experimental"); stays off as high-cost / low-reward.
- **Battery/time and reading-% overlays** duplicate the system status bar and the seekbar; stays off.
- **Custom CSS/JS/themes and the in-reader chapter drawer** would require loading `index.js`, which breaks the Compose-chrome rule; stays off.

These stay off by design; noted alongside the shipped round-2 extras in [ROADMAP.md](../../../ROADMAP.md).

One `core.js` gotcha the extras surfaced: reassigning `reader.generalSettings.val` re-runs a watcher that rebuilds the chapter DOM (the bionic/spacing reflow), so the live-settings push reassigns the general block only when one of its flags actually changes; the display block (font/size/theme/tts rate-pitch) reassigns freely.

**Live settings over reload.** Display changes push through `reader.readerSettings.val` instead of rebuilding the document, so font/size/spacing/theme adjustments are instant and never lose scroll position. The document rebuilds only on a chapter change or an app-theme change.

The broader direction of folding the manga and novel readers toward a shared Compose reader shell is tracked separately in [unified-reader.md](unified-reader.md); this doc describes the novel reader as it stands today, not that target.
