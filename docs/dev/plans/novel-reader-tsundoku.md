# Novel reader: tsundoku as the foundation

Developer-facing record of evaluating [Tsundoku](https://github.com/tsundoku-otaku/tsundoku) (an actively-maintained, Apache-2.0 Mihon fork built for novels) as the basis for Reikai's novel reader, and the two-track plan that came out of it: a near-term seamless-transitions port onto the current reader (Option 1), and a long-term migration to tsundoku's native reader (Option 3). Investigated 2026-07-11 from the `refs/tsundoku` clone.

## Goal

Give the novel reader seamless chapter-to-chapter reading (scroll out of one chapter straight into the next, the way the manga webtoon reader already does), and set a direction that moves the novel reader off bespoke, hard-to-maintain code onto a maintained upstream.

## Why

Reikai's current novel reader ([novel-reader.md](novel-reader.md)) is net-new `reikai.*` code: a Voyager screen hosting a WebView plus a **verbatim-vendored LNReader `core.js`** that this session established we cannot easily update. The Kotlin↔JS contract is fragile (the progress-% formula mismatch, the `generalSettings` DOM-rebuild gotcha, the polyfill-parity concern), so every novel-reader feature is bespoke hand-work with no upstream to sync from. The manga webtoon reader has seamless chapter transitions that novels lack.

Tsundoku is a Mihon fork whose novel reader is **native, folded into Mihon's own `ReaderActivity`, and far richer than LNReader**, and it is actively maintained (Apache-2.0; roughly three commits a day, latest 2026-07-10 at review). That makes it both a feature reference and a candidate upstream.

## What tsundoku is (the evidence)

Symbols below are in `refs/tsundoku` at review time.

- **Folded into Mihon's reader, not a separate engine.** A novel opens in the **same `ReaderActivity`** as manga. `ReaderViewModel.getMangaReadingMode()` forces `ReadingMode.NOVEL` when `source.isNovelSource()`, and `NOVEL` (a `ViewerType.Text` reading mode) maps through `ReadingMode.toViewer()` to `NovelViewer` (native) or `NovelWebViewViewer` (opt-in). The rest of the reader branches on the viewer class (`viewer is NovelViewer || viewer is NovelWebViewViewer`).
- **Native TextView renderer is the default** (`ReaderPreferences.novelRenderingMode` defaults to non-webview). `NovelViewer` renders with Android `TextView` + spans, no WebView: HTML via `Html.fromHtml`, Markdown via `NovelMarkdownUtils`, inline images via a Coil `ImageGetter`, custom paragraph spans (`NovelViewerSpans`), and `PrecomputedText` for performance. It is continuous-scroll (a `NestedScrollView` + `LinearLayout`); `moveToPage()` is a no-op. A chapter is one stub `Page` whose `page.text` flows through Mihon's `ViewerChapters` model (`LocalNovelPageLoader`).
- **Seamless "infinite scroll"** is bespoke, not Mihon's manga `ChapterTransition`. Gated on `ReaderPreferences.novelInfiniteScroll` (default off) with threshold `novelAutoLoadNextChapterAt` (default ~95%). Crossing the threshold appends the prefetched next chapter into the same scroll surface (native: a separator plus the chapter's views; webview: a divider div in the DOM via `scroll-tracking.js` + a JS bridge), tracks the boundary crossing (`onChapterChanged` saves the leaving chapter at 100%, calls `ReaderViewModel.setNovelVisibleChapter` which drives the top-bar title, marks read, records history, prefetches the next), and prunes distant chapters to bound memory.
- **Feature set well beyond LNReader:** dual native/WebView engines; six fonts plus custom import; seven themes plus custom colors; full text controls (justify, indent, four margins); full TTS (highlight styles, auto-next, background service); tap-zones / volume-key / swipe / auto-scroll; user CSS + JS and regex find-replace; a customizable in-reader status bar; EPUB / TXT / HTML / Markdown plus EPUB import and export; novel-native trackers (NovelUpdates, NovelList); translation hooks.

## Approach: two tracks

**Option 1 (near-term, part of the current parity program): port seamless transitions onto the current reader.** Re-implement tsundoku's infinite-scroll idea inside Reikai's existing WebView + `core.js` reader. At a scroll threshold, append the prefetched next chapter's HTML into the document behind a divider, track the boundary crossing (top-bar title, progress reset, mark-read, history, prefetch-next), and prune distant chapters. Reikai already prefetches the next chapter, so the data groundwork exists; the append + boundary tracking + pruning is the new work. Lower risk, incremental, ships value now, and it is subsumed by tsundoku's design if we later migrate.

**Option 3 (long-term, its own branch): adopt tsundoku's native reader.** Replace Reikai's Voyager WebView novel reader with tsundoku's native reader folded into Mihon's `ReaderActivity`. Novels join the manga reader (the deepest unification), gain native rendering (no WebView / `core.js` fragility) and the full feature set, and become **syncable from a maintained upstream** (tsundoku), the way manga syncs from Mihon. The domain / data / merge / tracking / source layers stay; the source→`page.text` loader is the net-new bridge.

## Recommendation

**Option 1 now; Option 3 as the deliberate target for its own branch.** Accepting the **View-based novel reader** (Option 3) is the right call: it does not so much expand the sanctioned View exception as make it consistent (today manga=View / novel=Compose is a split; Option 3 makes "the reader is View-based" uniform), it removes the WebView / `core.js` fragility, and it converts the biggest bespoke subsystem into one synced from a live upstream, which is the core of how Reikai is maintained. The main contingency is tsundoku staying healthy (verified active at review; re-check when starting). Kick off Option 3 with a migration-planning `/scout`: what to keep vs replace, the source→loader bridge, the tsundoku-sync setup, and a phased path so novels never break mid-migration.

## Status

Evaluated and recommended (2026-07-11). Not started. Option 1 is queued in the unified-content-UI parity program; Option 3 is deferred to its own branch. Backlog lines in [ROADMAP.md](../../../ROADMAP.md).

## Decisions & tradeoffs

- **View-based for novels is accepted** for Option 3. This reverses the Compose-native novel-reader choice ([novel-reader.md](novel-reader.md)); justified by maintainability-via-upstream plus true manga/novel unification, and by the reader already being the one sanctioned View holdout ([unified-reader.md](unified-reader.md)).
- **Two upstreams** (Mihon + tsundoku) is more sync surface, but replaces the far larger burden of maintaining a bespoke reader alone.
- **This session's parity features are not wasted:** always-on progress %, volume-key navigation, and the settings reorg ship value now and have tsundoku equivalents if we migrate.
- **Attribution:** tsundoku is Apache-2.0; port with credit, the same as the Komikku ports.
