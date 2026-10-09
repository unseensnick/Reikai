# Reader

## Purpose

The reader opens a chapter of a manga or a light novel in one host with one set of chrome: the app bars, the chapter navigator, the bottom action row, the chapter sheet and the settings sheet. Manga reads as images through Mihon's viewers; novels read as text, drawn natively or as Reikai's own web page. This doc covers the host, the engine and the manga side; how a novel chapter is turned into a screen is [novel-reader-rendering.md](novel-reader-rendering.md).

## How it works

### One host, one engine, one provider per session

`ReaderActivity` is the single host for both content types, because a window, its system bars and an orientation lock cannot exist twice. It keeps everything only an Activity can own: system bars and insets, cutout padding, orientation, keep-screen-on, custom brightness, the secure flag, the brightness and colour overlay, menu visibility, key and motion dispatch, and the transitions.

Above it sits `ReaderEngine`, a Reikai-owned `ViewModel` built through `ReaderEngine.Factory` with the session's `ReaderProvider` as its one assisted argument. The host resolves it with `viewModels { }` over `graph.viewModelFactory.createManuallyAssistedFactory(...)`, since the Compose-only `assistedMetroViewModel` is not reachable from an Activity. The engine owns the dialog slot (`ReaderDialog`), the viewport slot, chapter steps and picks, seeking, the auto-scroll drive, and the shared state the chrome reads (`chrome`, `navigator`, `bottomButtons`, `bookmarked`, `webUrl`, `orientation`, `keepScreenOn`).

`ReaderProvider` is one content type's answers, with no manga type in any signature. `MangaReaderProvider` adapts the live `ReaderViewModel`, which stays Mihon's and minimally patched. `NovelReaderProvider` adapts the Reikai-owned `NovelReaderViewModel`. A capability one type cannot support is a null slot the host hides: `textSettings`, `bionicReading` and `readAloud` are null for manga. `attach(host)` wires a session into a freshly created host (the novel provider installs its viewport and read aloud; the manga provider does nothing, since Mihon's `updateViewer` and `setChapters` still do that), and `detach(viewport)` runs before the engine destroys a viewport.

The launch names its entry as a type tag plus a raw id (`putEntryId`, `readEntryId` in `ReaderIntent.kt`), since the two id spaces overlap and an Intent extra cannot carry the sealed `EntryId`; the manga path also writes upstream's `"manga"` extra for `ReaderViewModel`'s saved state. `ReaderActivity.newIntent` and `newNovelIntent` are the two entry points, and `onNewIntent` restarts the reader on a different entry or chapter (`isSameLaunch` decides).

### The viewer contract

`ReaderViewport` is the downward contract the host drives: the view, `isRtl`, `seekTo(ChapterProgress)`, `onChapterStepped`, `onChapterOpened`, `autoScroll`, `destroy`, key and motion events. Chapter delivery is deliberately absent. `MangaViewport` wraps whatever `ReadingMode.toViewer` built, and manga chapters still arrive through `Viewer.setChapters` with `ViewerChapters`. Novel viewports implement `TextViewport` beside it, whose `ChapterWindow` carries novel chapters as HTML. Mihon's `Viewer` is one adapter under the contract, never the interface a novel viewer implements, because `ViewerChapters` and `ReaderPage` cannot carry a novel chapter without treating novels as manga.

The image viewers are constructed with the concrete `ReaderActivity` and call back into it at many sites, so the Activity stays the object viewers talk to and forwards upward calls (`onPageSelected`, `requestPreloadChapter`, the menu toggles) into the engine. Which viewer implementation is running is a manga question, answered by unwrapping `MangaViewport.viewer` (`Viewer?.running()` in the settings pages), not by widening the contract.

### Position

Position is a typed `ChapterProgress`, never an `Int`: `Pages(lastPageRead, pageCount)` for manga and `Percent(hundredths)` for novels. `ReaderPosition.kt` holds the derived fraction, the slider shape (`stepCount`, `isSeekable`), the labels and the completion predicate `isChapterComplete`: the last page for pages, a whole percent for a novel (`CONTINUOUS_COMPLETE_PERCENT`, 97, the default of the novel setting `readerMarkReadPercent`). The navigator, page indicator and both rails read the fraction and labels scoped to the visible chapter. The one surviving integer is `ReaderChapter.requestedPage`, which the three image viewers read directly when they restore a page; it serves manga only. The gallery page-preview jump uses its own `launch_page` extra, apart from the `page_index` key used for process-death restore.

### Chapter lists and stepping

Both readers resolve their list through shared kernels: ordering by `readingOrderComparator`, hidden chapters, `removeDuplicateChapters` (kept inside one entry, since across a merge group a number identifies nothing), `neighbourChapter` (previous steps once over the whole list, next skips to the first still-eligible chapter), `navigableChapters` (which copy of a duplicate set a forward step may land on, ranking an on-disk copy first under Downloaded only), and `withOpenedChapter` for a merged sibling's copy opened from History. Each type keeps its own skip settings on its own screen. Download-ahead, the trim behind the reader and Downloaded only are in [downloads.md](downloads.md); merged groups in [merged-series.md](merged-series.md).

The latest chapter switch wins in both readers by two mechanisms: manga's overlapping background loads go through `ChapterSwitches` (a pick, a step or the first open always lands; a page switch something newer overtook is dropped), and novels serialize switches under the model's `lane`.

### Chrome, dialogs and the sheets

The chrome composables are content-neutral: `ReaderAppBars`, `ChapterNavigator`, `ReaderPageIndicator`, `VerticalReaderRail` and `ReaderActionRow`. The navigator shape is `ReaderNavigatorState`, which each provider answers, including `ReaderNavigatorShape.None` when the user hides it (the action row then draws the chapter buttons at its ends, swapped for right to left).

Every dialog and sheet goes through the engine's one slot, so they survive rotation and are mutually exclusive by construction. `Loading` goes only into an empty slot or over a load dialog; a failure (`ReaderLoadState.Failed`) replaces any sheet and offers the failed chapter's own page in WebView (`chapterWebUrl`). A first chapter that fails with nothing on screen closes the reader (`canKeepReading`). Both readers can reload the open chapter, from disk or from the source (`reloadChapter`).

The bottom bar is an ordered selection per content type (`ReaderBottomButton.arranged` and `ordered`, with separate order preferences beside the selection preferences). The gear is a button like any other, but `ordered` always keeps it. `ReaderBottomButtonsScreen` edits it, from either reader's settings screen or from the reader.

The in-reader settings sheet is one `ReaderSettingsSheet` with four icon tabs for both types (Reading, Appearance, Controls, Filters), plus a Read aloud tab for novels; manga's pages are `MangaReaderSettingsPages` and novels' are `NovelReaderSettingsPages`. The sheet holds what is adjusted while looking at the page; the rest stays on each reader's settings screen. A novel change that needs a new viewport (rendering mode, text selection, the WebView font settings) recreates the Activity around the live session (`NovelReaderProvider.viewportRebuilds`). The cover-tinted chrome comes from one seed-colour kernel both readers and both details screens call. Brightness, the colour filter, grayscale and invert are per type (`ReaderDisplayFilters`), as are fullscreen, draw under cutout, rotation and keep screen on.

### Auto-scroll

`ViewportAutoScroll` has two shapes. `Stepped` turns a page every interval counted from when the page on screen is ready (`ShownPage.ready`); a new page restarts the countdown, and a failed page holds it until its Retry lands. `Continuous` scrolls at a speed in CSS pixels a frame at 60Hz, converted to device pixels, through `FrameScroller` and `ScrollCarry`; the WebView renderer runs its own `requestAnimationFrame` loop in `reader.js`. Paged manga answers `Stepped`; long strips and both novel renderers answer `Continuous`. Right-to-left WebGPU advances with `moveToPrevious`.

`ReaderEngine.autoScrollRunning` is whether it runs now, seeded once from the provider's `autoScrollOnOpen` preference; the bar and the sheet only flip it. Pauses never stop it: the menu showing, the reader off screen, a finger down, any dialog in the slot, read aloud playing, and a scrub (continuous only, for `SEEK_HOLD`). A long strip holds at a page still loading through `holdsStripAutoScroll`; a failed page scrolls into view, where its Retry is.

### Auto webtoon mode

With `eh_use_auto_webtoon` on (the default), a manga whose reading mode is Default opens in long strip when `exh.util.defaultReaderType` classifies it as manhwa, manhua or webtoon from its genre tags and source name, never from image sizes. Any member of a merge group calling it long strip decides it, since grouped sources describe one series differently and the opened member is rarely the tagged one. `ReaderViewModel.autoWebtoonMode` is the one predicate feeding the viewer and the override notice, which shows only when the pick differs from the global default. Nothing is written to `viewer_flags`, so the user's own pick is the only stored mode.

## Key files

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`: the host, `newIntent`, `newNovelIntent`, `onNewIntent`.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`: the manga model, `autoWebtoonMode`, `loadAdjacent`.
- `app/src/main/java/reikai/presentation/reader/ReaderEngine.kt`: the engine, dialog slot, viewport slot, auto-scroll drive.
- `app/src/main/java/reikai/presentation/reader/ReaderProvider.kt`: the provider seam; `app/src/main/java/reikai/presentation/reader/MangaReaderProvider.kt` and `app/src/main/java/reikai/presentation/reader/NovelReaderProvider.kt`.
- `app/src/main/java/reikai/presentation/reader/ReaderViewport.kt`, `app/src/main/java/reikai/presentation/reader/MangaViewport.kt`, `app/src/main/java/reikai/presentation/reader/TextViewport.kt`: the viewer contract and its adapters.
- `app/src/main/java/reikai/presentation/reader/ReaderIntent.kt`: `putEntryId`, `readEntryId`, `isSameLaunch`.
- `app/src/main/java/reikai/domain/reader/ChapterProgress.kt` and `app/src/main/java/reikai/domain/reader/ReaderPosition.kt`: the typed position and completion.
- `app/src/main/java/reikai/domain/reader/DuplicateChapters.kt`, `app/src/main/java/reikai/domain/reader/ChapterNeighbours.kt`, `app/src/main/java/reikai/domain/reader/ReaderChapterFilters.kt`: the chapter-list kernels.
- `app/src/main/java/reikai/presentation/reader/ChapterSwitches.kt`, `app/src/main/java/reikai/presentation/reader/ReaderLoadState.kt`, `app/src/main/java/reikai/presentation/reader/ReaderDialog.kt`.
- `app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt`, `app/src/main/java/eu/kanade/presentation/reader/components/ChapterNavigator.kt`, `app/src/main/java/eu/kanade/presentation/reader/ReaderPageIndicator.kt`, `app/src/main/java/reikai/presentation/reader/VerticalReaderRail.kt`, `app/src/main/java/reikai/presentation/reader/ReaderActionRow.kt`: the chrome.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderBottomButton.kt` and `app/src/main/java/reikai/presentation/reader/ReaderBottomButtonsScreen.kt`: the ordered bar.
- `app/src/main/java/reikai/presentation/reader/settings/ReaderSettingsSheet.kt`, `app/src/main/java/reikai/presentation/reader/settings/MangaReaderSettingsPages.kt`, `app/src/main/java/reikai/presentation/reader/settings/NovelReaderSettingsPages.kt`, `app/src/main/java/reikai/presentation/reader/settings/ReaderFiltersPage.kt`.
- `app/src/main/java/reikai/presentation/reader/ViewportAutoScroll.kt`, `app/src/main/java/reikai/presentation/reader/FrameScroller.kt`, `app/src/main/java/reikai/presentation/reader/ScrollCarry.kt`, `app/src/main/java/reikai/presentation/reader/StripAutoScroll.kt`: auto-scroll.
- `app/src/main/java/exh/util/MangaType.kt`: `mangaType`, `defaultReaderType`.
- `app/src/main/java/reikai/presentation/reader/WebGpuRetryRules.kt`, `app/src/main/java/reikai/presentation/reader/WebGpuPageCache.kt`, `app/src/main/java/reikai/presentation/reader/WebGpuSpreadPlaceholder.kt`: the rules the WebGPU islands call.
- `app/src/main/java/reikai/domain/reader/ChapterRetryCooldown.kt`: the wait before an unprompted retry of a failed chapter, both readers.

## Invariants and traps

- **The image viewers are never diverged past the divergences the Reader row of [.claude/rules/content-layer.md](../../../.claude/rules/content-layer.md) names.** Each island's rule lives in a Reikai file the island calls. A change that needs another edit inside `PagerViewer`, `WebtoonViewer` or `WebGpuViewer` is a design decision recorded there first; decoupling the viewers is the cost that once got a unified reader reverted.
- **Every provider flow is cold, and the engine shares it in its own scope.** `ReaderActivity` declares no `configChanges`, so a rotation recreates the Activity while the engine survives; state shared in the Activity's scope froze after the first rotation.
- **The host's chapter delivery has ordering hazards, each marked `// RK` where it sits.** `ReaderViewModel`'s init collector must stamp `requestedPage` before the viewers read it; `manga` must be set (and the `manga` collector registered) before `viewerChapters`, or `setChapters` removes the spinner with no viewer and the reader stays black; a reading-mode switch re-emits the same `ViewerChapters`, which `distinctUntilChanged` swallows, so `Event.ReloadViewerChapters` is the only delivery. The merge group must also resolve before the state update that builds the viewer, or auto webtoon sees no members.
- **A neighbour chapter that failed to load waits out one cooldown before either reader asks for it again on its own** (`ChapterRetryCooldown`, 15 seconds): the manga viewers ask in `ReaderViewModel.preload` on every page turn, the novel window on every reach. The user's Retry skips it, through `userAsked` on `requestPreloadChapter` and `preload` for manga and `retryBoundary` for novels, and an explicit load clears every wait (`reportExplicitLoad`, the novel `load`). A new Retry control that leaves `userAsked` off does nothing for up to 15 seconds.
- **The event channel is `Channel.UNLIMITED`.** Upstream's rendezvous channel with `trySend` drops an event when no receiver is parked, and a lost `PageChanged` does not heal.
- **Menu visibility is host state but still lives in `ReaderViewModel.State`**, because the three image viewers read `menuVisible` directly; moving it means editing all three.
- **A finished manga chapter opens at its start only when picked or stepped to** (`loadAdjacent`), never on every way it becomes current, or paging back into it overwrites the page `onPageSelected` stamped.
- **An untouched Apply in the quick reading-mode or rotation picker writes nothing** (`ModeSelectionApply.modeToApply`). The rotation picker highlights the resolved rotation, a deliberate divergence from Mihon's `OrientationSelectDialog`.
- **The per-entry orientation flag crosses the provider seam unresolved**, 0 meaning follow the type's default; `resolvedOrientation` is beside it. Resolving it early stops the picker's "use default" row showing as selected.

## Decisions

- **One host, a neutral engine, two providers (not novels folded into `ReaderViewModel`, not a second host).** Folding synthesizes manga rows for novels, which the data layer rejected; a second host guarantees two of everything. Void if novels become manga rows.
- **`ReaderViewModel` stays live as the manga provider.** It is Mihon's engine file with real callers, so absorbing it would turn every upstream reader fix into a hand-port.
- **The engine floor stays Mihon's**: `ChapterLoader`, the page loaders, `DownloadManager` and the read, history and track interactors, with the novel sinks beside them. The engine orchestrates; it never reimplements them.
- **Seeking and completion are typed.** A shared integer between a paged medium and a continuous one would share storage with two meanings.
- **A failed boundary load waits before an unprompted retry, for manga as for novels.** Retrying on the next trigger asked a failing source again on every page turn. The user's Retry and an explicit open skip the wait, since making the user wait out a wait they just overrode strands them.
- **Settings stay per content type.** A reader may want to skip read chapters in one library and not the other, and one value editable from two screens surprises.
- **Auto webtoon is computed per open and never stored**, and any merge member votes. A stored guess would outlive the preference being switched off. Manga only: long strip is an image layout.
- **Auto-scroll pauses rather than stops, and the bar never writes start-on-open.** The sheet keeps it paused, speed controls included; a speed change is judged after closing it.

## Upstream divergences

Reikai patches sit in `// RK` islands in `ReaderActivity` (engine and provider wiring, `onNewIntent`, the ordering-hazard notes, the chapter sheet pick, the Retry flag on `requestPreloadChapter`) and `ReaderViewModel` (merged lists, `ChapterSwitches`, the event channel, the requested-page reset, auto webtoon, duplicate and Downloaded-only ranking, the preload cooldown, `fetchableChapterIds` for the sheet's download control). The image-viewer islands are the ones the content-layer Reader row names. Recorded in [upstream-sync.md](../upstream-sync.md) "Deliberate divergences", including Downloaded only applied after the duplicate ranking.

## Extending

- **A setting both readers have**: give each type its own preference and settings row, expose it through `ReaderProvider` if the host or chrome reads it, and add the row to both settings pages in the sheet.
- **A capability only one type can support**: a nullable typed slot on `ReaderProvider` that the host hides when null, never a disabled control.
- **A new bottom-bar button**: add it to `ReaderBottomButton` with its scope; `ReaderActionRow` draws by an exhaustive `when`, so it cannot go undrawn.
- **A new dialog or sheet**: a `ReaderDialog` case opened through `ReaderEngine.openDialog`, so auto-scroll pauses and loading does not replace it.

## Tests

Engine and seam: `ReaderEngineTest`, `ReaderProviderConformanceTest`, `MangaReaderProviderTest`, `NovelReaderProviderTest`, `MangaViewportTest`, `ReaderIntentTest`, `ReaderLoadStateTest`, `ReaderLoadFailureConformanceTest`, `ChapterSwitchesTest`, `ReaderChapterReloadTest`, `ChapterRetryCooldownTest`, `ChapterRetryCooldownConformanceTest`. Position and lists: `ReaderPositionTest`, `MangaRequestedPageTest`, `ChapterNeighboursTest`, `DuplicateChaptersTest`, `NavigableChaptersTest`, `SkipDuplicateForwardConformanceTest`, `ReaderChapterFiltersTest`, `ReaderSheetReadConformanceTest`, `ReaderSheetDownloadOfferConformanceTest`, `MarkReadOnSkipConformanceTest`, `ReaderChapterGapConformanceTest`. Chrome and settings: `ReaderBottomButtonTest`, `ReaderBottomButtonsViewModelTest`, `ModeSelectionApplyTest`, `ReaderOrientationsTest`, `ViewerOrientationConformanceTest`, `ReaderDisplayFiltersTest`, `ReaderWindowSettingsTest`, `ChapterTitleTest`. Viewer islands: `WebGpuRetryRulesTest`, `WebGpuPageCacheTest`, `WebGpuSpreadPlaceholderTest`. Auto-scroll: `ScrollCarryTest`, `StripAutoScrollTest`, `NovelAutoScrollOnOpenMigrationTest`. Auto webtoon: `MangaTypeTest`.

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`.

## Related

- User docs: [reader-settings.md](../../guides/reader-settings.md), [novel-reader.md](../../novel-reader.md).
- The novel renderers, the content pipeline and read aloud: [novel-reader-rendering.md](novel-reader-rendering.md).
