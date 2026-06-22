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

- A top `TopAppBar` (chapter title + back) and a bottom `BottomAppBar` (previous chapter / settings / next chapter), both `AnimatedVisibility` that slide in and out with a `menuVisible` flag.
- A single center tap (relayed from the WebView's `hide` message) toggles `menuVisible`, which simultaneously hides/shows the chrome and the system bars via `WindowInsetsControllerCompat` (immersive reading). The system bars are restored when the screen is left so the rest of the app is unaffected.
- The settings sheet (`NovelReaderSettingsSheet.kt`) opens from the bottom bar.

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

## Key files

The reader and its details host are net-new `reikai.*` code. The only Mihon-file patches are the `// RK` launch sites that push `NovelReaderScreen`: `HistoryTab.kt`, `UpdatesTab.kt`, and `LibraryTab.kt`.

- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderScreen.kt`: Voyager `Screen`; Compose chrome, immersive bars, keep-screen-on + orientation effects, history-on-leave, settings sheet host.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderScreenModel.kt`: `StateScreenModel`; chapter loading + prefetch, prev/next ordering (cross-source, skip-duplicate aware), progress saves, read-state + tracker sync, incognito gating, settings flow.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderWebView.kt`: the `AndroidView(WebView)` canvas; document load, live settings push, theme-color derivation, cutout padding.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderHtmlBuilder.kt`: `buildReaderHtml` + `readerSettingsJson`; the per-chapter document, CSS variables, `initialReaderConfig`, the `ReactNativeWebView` -> `NativeReader` shim.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderWebInterface.kt`: `@JavascriptInterface` bridge (`hide` / `save` / `console`).
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderSettings.kt`: settings data class, theme presets, font list.
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderSettingsSheet.kt`: Compose settings sheet (Display + Theme tabs).
- `app/src/main/java/reikai/domain/novel/NovelPreferences.kt`: reader preference keys (the `ln_reader_*` strings above).
- `app/src/main/assets/lnreader-web/`: bundled `css/index.css` + `js/{core.js, van.js, icons.js, text-vibe.js, polyfill-onscrollend.js}`, copied verbatim from LNReader.

## Status

Shipped (P5 core sequence, on-device verified). Live reading with typography and themes, scroll resume, prev/next with prefetch, cross-source reading on merged novels, auto-mark-read, history, tracker sync, incognito gating, keep-screen-on, orientation lock, and mark-read-on-skip are all in place.

## Decisions & tradeoffs

**Compose owns all chrome; the WebView is text only.** LNReader's `index.js` renders an in-page ToolWrapper, buttons, and scrollbar. Reikai does not load it. Loading it would put a second, web-styled UI on top of the native one and break cohesion with the Mihon base. The cost is that the bridge and prev/next plumbing are native, which is the point.

**LNReader engine extras are wired off on purpose.** `core.js` supports TTS, page-mode (vs scroll), auto-scroll, bionic reading, tap-to-scroll, volume-button paging, swipe-to-change-chapter, remove-extra-paragraph-spacing, custom CSS/JS/themes, an in-reader chapter drawer, and a progress seekbar. All are hard-set off in `NovelReaderHtmlBuilder`: the behavior flags (TTS, page-mode, auto-scroll, bionic, tap/volume/swipe paging, seekbar, remove-extra-spacing) in its `generalSettings` block, and the custom CSS / JS / themes in its `readerSettingsJson` block (the in-reader chapter drawer simply never renders, since `index.js` is not loaded). The reasoning:

- **TTS** is the only high-interest extra, and it is gated by a real constraint, not preference. Plugins can run JS in a headless QuickJS host that lacks the browser globals a WebView gave for free, so any TTS path that depends on those globals fails silently unless fully polyfilled (see the `ln-host-polyfill-parity` memory and [novel-plugin-host.md](novel-plugin-host.md)). TTS is therefore deferred until the host parity work makes it safe, not switched on speculatively.
- **The rest are low value** for this reader (page-mode, auto-scroll, bionic reading, volume/swipe paging, in-page drawer/seekbar) and would each add surface area and another way the in-page UI could diverge from the Compose chrome.

This matches the old Yokai reader, which was also limited to scroll mode with native chrome. So leaving these off is unported-LNReader-parity, not a regression. The tracked follow-up (TTS plus the lower-value extras) is the "Novel reader engine extras" round-2 item in [novel-parity-backlog.md](novel-parity-backlog.md).

**Live settings over reload.** Display changes push through `reader.readerSettings.val` instead of rebuilding the document, so font/size/spacing/theme adjustments are instant and never lose scroll position. The document rebuilds only on a chapter change or an app-theme change.

The broader direction of folding the manga and novel readers toward a shared Compose reader shell is tracked separately in [unified-reader.md](unified-reader.md); this doc describes the novel reader as it stands today, not that target.
