# Unified reader (shared chrome)

Give the manga reader and the novel reader the same on-screen controls (top bar, bottom bar, page/chapter navigation, settings) by sharing one set of Compose chrome components between them, without rewriting either reader's content engine.

This is the developer-facing record for the unified-reader initiative. For the novel reader on its own see [novel-reader.md](novel-reader.md); for the broader rebase context see [rebase-overview.md](rebase-overview.md).

## Goal

One coherent reading experience across both mediums. A reader of a manga and a reader of a light novel should see the same toolbar, the same immersive tap-to-hide behavior, the same chapter navigator, and the same settings affordances, so the app feels like one reader with two content types rather than two unrelated screens. The underlying content engines stay separate (images for manga, a text WebView for novels); only the chrome (the controls wrapped around the content) is unified.

## Why

Reikai inherited two readers built on different stacks. The manga reader is Mihon's, and it is the one deliberate View-based holdout in an otherwise Compose app: "View-based" means the old Android way of building UI from XML layouts and imperative code, as opposed to Compose, the modern declarative toolkit where UI is described in Kotlin functions. Mihon keeps the manga reader on Views because its image viewers (paged and webtoon) are performance-sensitive and battle-tested as-is. The novel reader, added by Reikai, is Compose + Voyager from the start: a WebView text canvas surrounded by Compose chrome (see [novel-reader.md](novel-reader.md)).

If each reader carried its own toolbar and settings, they would drift apart in look and behavior, and every chrome tweak would have to be done twice. Sharing the chrome fixes both: consistent UX for users, one place to change for maintainers. The constraint is to do this without forking Mihon's image viewers away from upstream, because those files are ported from Mihon on every sync and re-translating them by hand each time is a permanent tax.

## Approach

The current direction (called "Option F") keeps each reader's content host exactly where it is and shares only the chrome layer.

The manga reader stays a View-based screen (`ReaderActivity`) that hosts the existing image viewers the proven Mihon way. The novel reader stays a Compose Voyager screen hosting a WebView. The manga reader's controls (the toolbar, page seekbar, chapter buttons, overlays) get re-expressed as the same Compose components the novel reader already uses, embedded into the View screen through a `ComposeView` (an Android bridge widget that renders Compose UI inside an XML/View layout). The result is two content hosts under the hood, but one shared chrome on top.

The mechanism:

- **The shared chrome composables** live under `eu/kanade/presentation/reader/`: `ReaderAppBars` (top + bottom bars with tap-to-toggle immersive animation), `ReaderTopBar`, `ReaderBottomBar`, `ChapterNavigator` (prev/next + seekbar), `ReaderPageIndicator`, and the settings dialog pages. These are Mihon's own reader chrome, already pure Compose, already driven by immutable state. The novel reader consumes them today.

- **The manga reader stays View-based.** `ReaderActivity` remains the manga host. The image viewers (`PagerViewer`, `WebtoonViewer`) take a concrete `ReaderActivity` reference, so leaving the activity in place means the viewers stay byte-identical to upstream and keep porting cleanly on each Mihon sync. No viewer decoupling is required.

- **The novel reader's WebView canvas** (`NovelReaderWebView`) renders the chapter HTML with bundled CSS/JS; a center tap posts a message over a native JavaScript bridge to toggle the chrome. The Compose chrome around it is `NovelReaderScreen`.

- **How chrome is shared (Option F).** `ReaderActivity` keeps the window-bound responsibilities that only an Activity can own (orientation lock, screen brightness, system-bar visibility), but its menu, page indicator, seekbar, chapter-navigation buttons, and color/brightness overlays move out of imperative View code and into a `ComposeView` that renders the same shared chrome composables. Both readers then present the identical control surface, fed by each side's own state. Unification happens at the chrome and settings level, not the shell level: the manga and novel content hosts remain distinct.

## Key files

- Manga reader (View host, stays): `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`.
- Shared chrome composables: `app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt`, `.../appbars/ReaderTopBar.kt`, `.../appbars/ReaderBottomBar.kt`, `.../components/ChapterNavigator.kt`, `.../ReaderPageIndicator.kt`, `.../ReaderContentOverlay.kt`, and the settings pages under `.../reader/settings/`.
- Novel reader (Compose shell + WebView canvas): `app/src/main/java/reikai/presentation/novel/reader/NovelReaderScreen.kt`, `.../NovelReaderWebView.kt`, with `NovelReaderHtmlBuilder.kt`, `NovelReaderWebInterface.kt`, `NovelReaderScreenModel.kt`, and `NovelReaderSettingsSheet.kt`.

## Status

- **Phase 1 (shipped):** the novel reader runs on the Compose shell with the shared chrome composables and a WebView text canvas. This is the live novel-reading surface; see [novel-reader.md](novel-reader.md).
- **Phase 2 (current direction, Option F):** move the manga reader's chrome into a `ComposeView` of the shared composables while `ReaderActivity` stays the manga host. This is the agreed direction; the rest of the reader work for the rebase is the Roadmap P7 reader-tweaks pass, which has shipped.

Rollback and recovery pointers (from the project memory):

- Rollback anchor: annotated tag `reader-phase2-baseline` at commit `9dcc31f8d` (pushed). Reset here to return to the pre-Phase-2 baseline.
- The earlier Option A experiment (see below) is recoverable from the reflog at commits `c04e1ba00` and `43860947b`, plus a `git stash` entry labelled "abandoned-option-A-worktree" captured at revert time.

## Decisions & tradeoffs

**Option A was explored and reverted.** Option A was a single Compose shell hosting *both* mediums, with the image viewers decoupled from `ReaderActivity` (via a `ViewerHost` interface) and embedded through `AndroidView`. It was actually built across two commits and then reverted. The revert was for cost, not feasibility: an on-device spike confirmed the webtoon view scrolled and zoomed correctly inside `AndroidView` with no gesture conflict, so it was technically viable.

**Why Option A was dropped.** It diverges the hot image-viewer files from upstream, which means re-translating them by hand on every Mihon sync (a permanent tax). It also runs modified viewers even on the fallback path, weakening the safety net, and its single-host payoff only fully lands after a large parity lift to delete `ReaderActivity` entirely. The effort outweighed the benefit at this stage.

**Why Option F.** Keeping `ReaderActivity` as the manga host means the image viewers stay byte-identical to upstream and keep porting cleanly, and the existing reader keeps acting as a proven fallback. The shared payoff (one consistent control surface) is achieved by sharing the chrome rather than the shell. The accepted cost is two content hosts maintained permanently and the manga chrome diverging from Mihon's XML layout (offset by being the same Compose chrome the novel reader already uses). This is the shorter, lower-risk path to a stable unified experience.

**A deeper future direction: fold novels into `ReaderActivity`.** The eventual endgame of this initiative (novels sharing the *actual* manga reader, not just its chrome) is captured separately in [novel-reader-tsundoku.md](novel-reader-tsundoku.md): adopting tsundoku's native novel reader, which already lives inside Mihon's `ReaderActivity` as a `Text` viewer. That accepts a View-based novel reader in exchange for true unification, native rendering, and a maintained upstream to sync from. Recommended, deferred to its own branch.
