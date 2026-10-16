# Paged novel reading

## Goal

Read a light novel a page at a time, the way an e-book reader does: a tap on an edge, a swipe or a volume key replaces the whole screen with the next page, with no scrolling animation, no line cut at a page edge, an optional flash for e-ink screens, and right-to-left page order. Both novel renderers, native and WebView, get it. Asked for in unseensnick/Reikai#76 by a reader on an e-ink tablet.

## Why

Scrolling is the only way to read a novel today. On e-ink every scroll frame smears and ghosts, and the reporter names LNReader and IReader as apps that already page. The manga reader pages already (Mihon's `PagerViewer`), so this levels a gap between the two content types too.

## Approach

**In plain terms.** Each renderer keeps laying the chapter out as one tall column, exactly as it does for scrolling. Paged mode measures where every line sits in that column, and one shared rule cuts the column into screen-high pages made of whole lines. Showing a page jumps the column so the page's first line sits at the top margin, and paints the strip below the page's last whole line in the page colour, so a half-visible line never shows. Turning a page is another jump, with no animation. Where one chapter ends and the next starts, the break is a page of its own. Your place is "the first character on the page", and saved progress stays a percent, so switching between scrolling and paging keeps your place.

**The shared page-break rule.** A pure Kotlin kernel takes a chapter's measured line boxes (top and bottom, relative to the chapter top) and the page's content height (screen height less the top and bottom margins), and returns the pages. Overlapping boxes merge into one band first, so a line with a taller inline element stays whole. A page takes whole bands until the next would overflow; a band taller than a page (a tall picture) is cut at page height, and the text after it starts whole on the next page. Turning back reuses the same list, so it mirrors turning forward exactly. Both renderers feed this one kernel, so the rule exists once.

**Margins.** The page margins equal the chapter's own top and bottom padding (the reader margin plus the cutout inset at the top), so every page's first line sits at the same height as the chapter's first line. The top band above the margin is masked too, so the previous page's tail never peeks in.

**Native.** `NovelTextViewport` keeps its vertical `RecyclerView`. Line boxes come from the chunk layouts the viewport already reads (`lineTopOf`, `textLineOf`, `boundsOf`), measured per chapter in about a millisecond. A page is shown with `scrollToPositionWithOffset`, and the masks are an item decoration's `onDrawOver`. Finger scrolling is turned off by a layout manager that refuses vertical scrolls only while a finger is down, so programmatic jumps still work and taps still reach the tap zones. The list gets one screen of bottom padding with `clipToPadding = false`, so the last page can reach the top.

**WebView.** `reader.js` measures line boxes with the uncounted text walker `topLine` already uses: client rects per text node, widened by half the leading to the full line box, plus picture boxes. A long chapter (about 2,000 fragments) measures in about 115 ms, once per chapter and on restyle, never per turn. A page is shown with `window.scrollTo`, the masks are two fixed elements, finger scrolling is off through `overflow: hidden` and `touch-action: none` (taps still reach the JS handlers), the scrollbar is hidden, and the body gets one screen of bottom padding.

**Re-measuring.** Pages are recomputed, holding the first character of the current page, when the layout changes: a font, size, spacing or margin setting, a rotation or inset change, a picture finishing loading, and a chapter appended or prepended. The existing `place` logic that holds the reader across a prepend applies unchanged, since the column is the same column.

**How chapters join.** `ChapterWindow`'s append, prepend and evict, and the `NovelSeam` rules, stay as they are; the seam is its own page. With seamless chapters off, the window holds one chapter, and a turn past its last page loads the next, as scrolling does.

**Position.** Stored progress stays `ChapterProgress.Percent`, computed in paged mode as page index over (page count minus one), so the last page reads 100 and completion works unchanged. Landing uses the existing `topLine` character offset, now meaning the first character on the page. A page index is never stored, since it changes with every font or size change; the prototype confirmed a character keeps its page across a font change.

**Settings.** One rebuild setting, `NovelPageLayout { SCROLL, PAGED_LTR, PAGED_RTL }`, in `NovelViewportSettings`, so "scroll plus right-to-left" cannot be stored. Page turns are instant. The rows stay hidden until both renderers are done.

**Taps, keys and direction.** `NovelTapZones.actionAt` learns the direction: in right-to-left, the left edge reads on. Volume down still means forward, now a page instead of `SCROLL_FRACTION` of a screen. A horizontal swipe turns the page in paged mode. Both viewports answer `isRtl` from the setting instead of `false`. Text selection keeps working, since long-press is not a scroll.

**E-ink flash.** The flash is manga-only today: `DisplayRefreshHost` reads `ReaderPreferences`, `ReaderActivity` fires it on `ReaderViewModel.Event.PageChanged`, gated on `flashOnPageChange`. The provider gains a per-type flash settings slot (novels get their own preferences), and the engine one "page turned" call that manga makes from `PageChanged` and novels from a new `onPageTurned` in `NovelViewportCallbacks`, after the new page has drawn.

**Auto-scroll and read aloud.** A paged viewport answers `ViewportAutoScroll.Stepped` (one page per interval), the shape the manga pager already answers. Read aloud turns to the page holding the paragraph being spoken.

**Reuse.** Native line reads: `lineTopOf`, `lineAtTop`, `textLineOf`, `boundsOf` in `NovelTextViewport`. WebView: the `topLine` walker and `place` in `reader.js`, one walker rather than a third. Shared: `ViewportAutoScroll.Stepped`, `NovelTapZones`, the chapter-swipe rules, `ChapterProgress.Percent`, `NovelSeam`, `ChapterWindow`. New: the page-break kernel and `NovelPageLayout` (searched: paginate, page break, PageLayout, bands).

**Steps, each with its check:**

1. **Kernels:** the page-break rule, `NovelPageLayout`, the direction-aware `actionAt`, the paged percent, the page holding a given character. Check: JVM unit tests (whole lines only, merged overlapping boxes, a tall band cut at page height with the next line whole, back mirrors forward), and `NovelViewportSettingsTest` covering the new setting.
2. **Flash plumbing:** per-type flash preferences and the engine's "page turned". Check: manga's flash still fires on a pager page change on device, plus a `ReaderEngineTest` case.
3. **Hidden preferences:** the settings and the novel e-ink group, rows hidden. A preference migration, if one is needed, gates on `versionCode` 203. Check: the migration test.
4. **Native paged.** Check: new `TextViewportContractTest` cases (no band straddles a mask, every page's first line at the top margin, a turn moves exactly one page and back mirrors it, the same character holds after a font change, a seam is its own page, a tall picture splits, finger scroll off while jumps and taps work, right-to-left taps).
5. **WebView paged.** Check: the same contract cases over the WebView, with page counts matching native for the same chapter and settings.
6. **Auto-scroll and read aloud.** Check: contract cases, and the Stepped countdown restarting per page.
7. **Unhide the rows; update docs and CHANGELOG.** Fold this plan into `reader.md` and `novel-reader-rendering.md`, and update the content-layer Reader row for the flash slot. Check: the full gate, then a device pass at large font, after rotation, in right-to-left and with the flash on, on the phone and the tablet.

## Key files

- `app/src/main/assets/novel-web/reader.js` (`place`, `topLine`): the WebView measure, jump and masks.
- `app/src/main/java/reikai/presentation/reader/NovelTextViewport.kt` (`lineTopOf`, `textLineOf`, `boundsOf`): the native measure, jump and masks.
- `app/src/main/java/reikai/presentation/reader/NovelWebViewport.kt`: `isRtl`, the turn verbs.
- `app/src/main/java/reikai/presentation/reader/NovelReaderProvider.kt`: `NovelViewportSettings`, the callbacks.
- `app/src/main/java/reikai/presentation/reader/NovelTapZones.kt`: `actionAt`, `SCROLL_FRACTION`.
- `app/src/main/java/reikai/presentation/reader/text/NovelSeam.kt`: the seam rules.
- `app/src/main/java/eu/kanade/presentation/reader/DisplayRefreshHost.kt` and `ReaderActivity.kt` (`flashOnPageChange`): the flash.
- `app/src/androidTest/java/reikai/presentation/reader/TextViewportContractTest.kt`: the cases both renderers must pass.

## Open questions

None blocking. Instant turns and following the seamless-chapters setting were ruled by the owner.

## Decisions & tradeoffs

- **A paged column, not sideways pages.** Cutting each renderer's existing column into pages keeps one layout for both modes, so selection, pictures, styling, the seam and the prepend hold all carry over, and one Kotlin rule decides the breaks for both renderers. Sideways pages (CSS columns in the WebView, a new horizontal pager natively) were the earlier recommendation; they needed a second layout in each renderer and two break rules held together only by tests.
- **True pages, not page-snap.** Snapping the scroll a screen at a time was rejected: its breaks depend on where the scroll stood, so turning back does not mirror turning forward.
- **The renderer measures, Kotlin decides.** Kotlin cannot predict where Chromium breaks a line, so each renderer reports its own line boxes; the decision over those boxes is shared. A throwaway prototype on a phone and a tablet gave identical page counts from both renderers for the same chapter.
- **A tall picture is cut, not scaled.** A picture taller than a page continues on the next page, as in scrolling, rather than being shrunk to fit; the text after it starts whole.
- **Mihon's pager model, not its code.** The seam page follows `PagerViewer`'s transition page; its classes are typed to manga pages and the image viewers are never diverged further.
- **Position stays a character offset plus a percent.** A page index drifts with every font change; the character offset both renderers already report does not.
