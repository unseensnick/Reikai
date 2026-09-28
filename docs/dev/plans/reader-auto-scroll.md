# Reader auto-scroll (manga and novels)

## Goal

Both readers scroll on their own: a paged manga turns a page every few seconds, and a long strip or a novel scrolls smoothly at a set speed. One button on the bottom bar, or the checkbox at the top of the reader sheet's Controls tab, starts and stops it, and a setting in each reader's Settings screen starts it when the reader opens.

## Why

Novels had auto-scroll and manga had none, an open parity gap on the reader surface. TachiyomiSY and Komikku carry a manga auto-scroll, but theirs is a timed loop inside the host Activity that knows two of the four image viewers; Reikai's reader is one engine over two providers, so the feature had to land in the engine for both content types at once (the write-once rule in `.claude/rules/content-layer.md`).

## Approach

**Two shapes, one driver.** A viewport says how it moves, and the engine decides whether and when. `ViewportAutoScroll` has two shapes: `Stepped` (turn a page every interval, counted from when the page on screen is ready) and `Continuous` (scroll at a speed, 0 stops). Each shape carries the preference its rate is read from, which is also how the reader sheet knows whether to offer an interval or a speed. A paged manga viewer answers `Stepped`; a long strip, the native novel renderer and the WebView novel renderer answer `Continuous`.

**Running now versus start on open.** `ReaderEngine.autoScrollRunning` is whether it runs now. It is seeded once, when the engine is built, from the provider's `autoScrollOnOpen` preference, and the bar and the sheet only flip it, never write the preference. The engine outlives an Activity recreation, so a rotation keeps it running; a new chapter keeps whatever state the reader left it in.

**Pauses never stop it.** The menu showing, the reader going off screen, a finger on the screen, read-aloud playing, and a scrub (continuous shape only, for `ReaderEngine.SEEK_HOLD`) each pause the drive and leave `autoScrollRunning` alone. The host feeds the first three (`setMenuVisible` from `setMenuVisibility`, `setOnScreen` from onStart and onStop, `setTouching` from `dispatchTouchEvent`). A pause cancels the drive, so a stepped countdown restarts from full when it clears and a continuous one is sent its 0.

**The stepped countdown** waits until `ShownPage.ready`, then turns every interval. A new page (a new `ShownPage.key`) starts it over; with no new page it keeps turning, which is what walks a navigate-to-pan page one pan at a time. Manga keys the page by the `ReaderPage` object, so a reload's fresh pages count as new, and calls a page ready once its `Page.State` is `Ready`. A failed page is not ready, so a paged reader waits on it until its Retry lands. Right-to-left WebGPU advances with `moveToPrevious`, because that viewer's next verb walks a reversed book backwards, as its PAGE_DOWN key does.

**The continuous scroll** runs through `FrameScroller`, one Choreographer callback a frame with the arithmetic in `ScrollCarry`. The speed is the WebView renderer's unit, a CSS pixel a frame at 60Hz, converted to device pixels so the same setting moves the same distance in every renderer. The WebView novel renderer runs its own `requestAnimationFrame` loop in `reader.js` instead, and is pushed the speed again whenever a chapter's document is rebuilt.

**A long strip holds at a loading page.** The two strip viewers each carry one `// RK` method, `autoScrollBy(px): Boolean`, which maps the pages from the one on screen to the next below it onto their `Page.State` and asks `holdsStripAutoScroll`. Only a load in flight holds: a failed page scrolls into view, where its Retry is. A refused step drops the carried fraction, so the scroll resumes at its speed instead of jumping. The WebGPU strip collects its pages on the renderer thread in `onViewport` and reads their load state on the main thread; a gate on its draw state deadlocks, because the renderer draws only when invalidated and a refused step invalidates nothing.

**Settings.** Settings -> Manga reader has Start auto-scroll when opening a chapter, Page turn interval (paged modes) and Scroll speed (long strip). Settings -> Novel reader has Start auto-scroll when opening a chapter and Scroll speed. The reader sheet's Controls tab shows the Auto-scroll checkbox and whichever rate the viewport showing now runs on.

**Upgrade.** The novel reader's old `ln_reader_auto_scroll` switch was whether the scroll ran, and it persisted across sessions. `NovelAutoScrollOnOpenMigration` (versionCode 197) carries it into `ln_reader_auto_scroll_on_open` and deletes it, and `PreferenceRestorer` carries it the same way when an older backup lands it after the migration has run.

## Key files

- `ReaderEngine.kt`: `autoScrollRunning`, `toggleAutoScroll`, the pause setters, `drive`, `SEEK_HOLD`.
- `ViewportAutoScroll.kt`: the two shapes and `ShownPage`.
- `FrameScroller.kt`, `ScrollCarry.kt`: the frame loop and its carry.
- `StripAutoScroll.kt`: `holdsStripAutoScroll`, the one rule both strip viewers hold by.
- `MangaViewport.kt` (`autoScroll`), `MangaReaderProvider.kt` (`shownPage`).
- `WebtoonViewer.autoScrollBy`, `WebGpuViewerContinuous.autoScrollBy`: the two strip islands.
- `NovelTextViewport.kt`, `NovelWebViewport.kt`: each viewport's `autoScroll`.
- `NovelAutoScrollOnOpenMigration.kt`, `NovelPreferences.carryReaderAutoScroll`.
- Tests: `ReaderEngineTest` (the auto-scroll block), `ScrollCarryTest`, `StripAutoScrollTest`, `MangaReaderProviderTest`, `MangaViewportTest`, `NovelAutoScrollOnOpenMigrationTest`, `ReaderProviderConformanceTest`.

## Status

Built on `feat-autoscroll`; JVM-tested and mutation-checked, device verification pending.

## Decisions & tradeoffs

Owner rulings, 2026-09-28: the strip's smooth scroll waits for a loading page; novels pause on a finger down as well as long-strip manga; the sheet and the bar mean running now and start-on-open lives in Settings only; start-on-open fires when the reader opens, not on every chapter; the strip is smooth only; a finger down restarts the stepped countdown.

**The sixth image-viewer island.** `autoScrollBy` in `WebtoonViewer` and `WebGpuViewerContinuous` is an owner-ruled island, listed in content-layer.md's Reader row. Driving the strips from `MangaViewport` instead would need the WebGPU viewer's protected page lookup, and the island stays one method per viewer, identical in shape, with the rule itself in `StripAutoScroll.kt`.

**Differences from Komikku** (compared at refs/komikku `936e25bf99`):

| | Komikku | Reikai |
|---|---|---|
| When a turn happens | A fixed loop: turn, wait, turn, whatever the reader does in between | The wait counts from each new page being ready, so a manual turn or a slow page restarts it |
| Starting it | Turns a page the moment it is switched on | Waits a full interval first |
| A page still loading | Turned past | Holds, stepped and long strip alike; a failed page holds a paged reader but not a strip |
| A finger on the screen | Nothing; only the menu pauses, polled every 100ms | Pauses it, and restarts a stepped countdown |
| The setting | One running switch in the adult-source bar, with the interval typed in as text | Running now on the bar and the sheet; start on open in each reader's Settings |
| The rate | Seconds per step, and a strip scrolls one screen per step | Seconds per page for paged modes, a speed in the novel reader's unit for strips |
| Smooth scrolling | A switch between smooth and stepped strips | Strips are smooth only |
| Viewers | The legacy pager and webtoon viewers | All four, including right-to-left WebGPU, which advances with its previous verb |
| An invalid interval | Stored as a -1 sentinel that disables the feature | Bounded slider values, with its own preference keys |
