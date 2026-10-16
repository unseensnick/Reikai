# Paged novel reading

## Goal

Read a light novel a page at a time, the way an e-book reader does: a tap on an edge, a swipe or a volume key replaces the whole screen with the next page, with no scrolling animation, no line cut at a page edge, an optional flash for e-ink screens, and right-to-left page order. Both novel renderers, native and WebView, get it. Asked for in unseensnick/Reikai#76 by a reader on an e-ink tablet.

## Why

Scrolling is the only way to read a novel today. On e-ink every scroll frame smears and ghosts, and the reporter names LNReader and IReader as apps that already page. The manga reader pages already (Mihon's `PagerViewer`), so this levels a gap between the two content types too.

## Approach

**In plain terms.** Each renderer cuts the open chapters into a row of screen-sized pages and shows one at a time. Where one chapter ends and the next starts, the break is a page of its own, as in the manga pager, so the next chapter follows on without a reload. Your place is "the first character on the page": after a rotation or a font change the reader lands on whichever page now holds that character. The saved progress stays a percent, so switching between scrolling and paging keeps your place.

**Who cuts the pages.** Each renderer uses its own layout engine, because only that engine knows where its lines break:

- **WebView.** Each chapter's container becomes a box of CSS columns, one page wide and as tall as the screen (`column-fill: auto`, the height rounded to a whole number of lines so no line is cut). The chapter boxes and seam pages sit side by side in one strip moved with `translateX`, never scrolled. Pictures are kept whole and no taller than a page. Inserting the previous chapter to the left shifts the offset by its width, the same "hold the reader in place" idea `place.across` already applies vertically.
- **Native.** A new paged viewport over a horizontal `RecyclerView` of page items. A pure `TextPaginator` cuts each chunk's existing text layout into whole-line page slices; a picture is a page of its own, scaled to fit. A page item shows its slice through a clipped window onto the chunk's layout rather than a substring, since a substring would re-indent mid-paragraph. Rendering, styling and pictures stay the native renderer's (`NovelTextRenderer`, `NovelTextStyle`, `ChapterTextBlock`); the window bookkeeping and landing logic `NovelTextViewport` holds move into a helper both native viewports share, rather than growing that file.

**How chapters join.** `ChapterWindow`'s append, prepend and evict, and the `NovelSeam` rules, stay as they are; a seam renders as a page.

**Position.** Stored progress stays `ChapterProgress.Percent`, computed in paged mode as page index over (page count minus one), which matches the scroll mode's "height less one screen", so the last page reads 100 and completion works unchanged. Landing uses the existing `topLine` character offset, now meaning the first character on the page. A page index is never stored, since it changes with every font or size change.

**Settings.** One rebuild setting, `NovelPageLayout { SCROLL, PAGED_LTR, PAGED_RTL }`, in `NovelViewportSettings`, so "scroll plus right-to-left" cannot be stored. Animated page turns are a live setting, off by default when paged. The rows stay hidden until both renderers are done.

**Taps, keys and direction.** `NovelTapZones.actionAt` learns the direction: in right-to-left, the left edge reads on. Volume down still means forward, now a page instead of 0.75 of a screen. Both viewports answer `isRtl` from the setting instead of `false`.

**E-ink flash.** The flash is manga-only today: `DisplayRefreshHost` reads `ReaderPreferences`, `ReaderActivity` fires it on `ReaderViewModel.Event.PageChanged`, gated on `flashOnPageChange`. The provider gains a per-type flash settings slot (novels get their own preferences), and the engine one "page turned" call that manga makes from `PageChanged` and novels from a new `onPageTurned` in `NovelViewportCallbacks`, after the new page has drawn.

**Auto-scroll and read aloud.** A paged viewport answers `ViewportAutoScroll.Stepped` (one page per interval), the shape the manga pager already answers. Read aloud turns to the page holding the paragraph being spoken.

**Steps, each with its check:**

1. **Kernels:** `NovelPageLayout`, the direction-aware `actionAt`, the paged percent, `TextPaginator` over fake line metrics, the per-type flash settings. Check: JVM unit tests (whole lines only, a picture alone on its page, the page holding a given offset), and `NovelViewportSettingsTest` covering the new setting.
2. **Flash plumbing:** per-type flash preferences and the engine's "page turned". Check: manga's flash still fires on a pager page change on device, plus a `ReaderEngineTest` case.
3. **Hidden preferences:** the settings and the novel e-ink group, rows hidden. A preference migration, if one is needed, gates on `versionCode` 203. Check: the migration test.
4. **WebView paged.** Check: new `TextViewportContractTest` cases (no line cut at an edge, a turn moves exactly one page, the same character holds after a size or font change, a prepend keeps the page, right-to-left taps), plus seam cases.
5. **Native paged.** Benchmark the page-item approach on a 30,000-character chapter first; if it is too slow, draw the shared layout directly. Check: the same contract cases over the native renderer, and `RenderedLinesParityTest` unchanged.
6. **Auto-scroll and read aloud.** Check: contract cases, and the Stepped countdown restarting per page.
7. **Unhide the rows; update docs and CHANGELOG.** Fold this plan into `reader.md` and `novel-reader-rendering.md`, and update the content-layer Reader row for the flash slot. Check: the full gate, then a device pass at large font, after rotation, in right-to-left and with the flash on, on the tablet and an e-ink-like setting.

## Key files

- `app/src/main/assets/novel-web/reader.js` (`place`, `topLine`) and `reader.css`: the WebView column strip.
- `app/src/main/java/reikai/presentation/reader/NovelTextViewport.kt`: the native viewport whose window logic the paged viewport shares.
- `app/src/main/java/reikai/presentation/reader/NovelWebViewport.kt`: `isRtl`, the turn verbs.
- `app/src/main/java/reikai/presentation/reader/NovelReaderProvider.kt`: `NovelViewportSettings`, the callbacks.
- `app/src/main/java/reikai/presentation/reader/NovelTapZones.kt`: `actionAt`, `SCROLL_FRACTION`.
- `app/src/main/java/reikai/presentation/reader/text/NovelSeam.kt`: the seam rules.
- `app/src/main/java/eu/kanade/presentation/reader/DisplayRefreshHost.kt` and `ReaderActivity.kt` (`flashOnPageChange`): the flash.
- `app/src/androidTest/java/reikai/presentation/reader/TextViewportContractTest.kt`: the cases both renderers must pass.

## Open questions

1. **Turn animation default when paged:** instant, or a short slide. Recommended: instant, the e-ink case the feature exists for; the slide stays a setting.
2. **Chapter joins in paged mode:** follow the existing seamless-chapters setting, or always reload at a chapter edge as LNReader and tsundoku do. Recommended: follow the setting, since the seam page needs no new rule.

## Decisions & tradeoffs

- **True pages, not page-snap.** Snapping the vertical scroll a screen at a time was considered and rejected: its breaks depend on where the scroll stood, so turning back does not mirror turning forward, there is no stable page, and right-to-left would only be cosmetic.
- **Each renderer paginates itself.** One Kotlin page model measured once and drawn by both was rejected: Kotlin cannot predict where Chromium breaks a line under a source's own CSS and fonts, and forcing pre-cut pages into the WebView would remove what that renderer is for. Both renderers are held to one behaviour by the contract test instead.
- **Mihon's pager model, not its code.** Its flat list of previous pages, transition, current pages, transition and next pages is the shape the seam page follows; its classes are typed to manga pages and the image viewers are never diverged further.
- **Position stays a character offset plus a percent.** tsundoku stores a ratio, which drifts when the font changes; a page index drifts worse. The character offset both renderers already report survives both.
