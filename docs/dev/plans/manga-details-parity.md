# Manga details parity (P3)

Closing the parity gaps on Mihon's manga details screen so it matches what Reikai's old Yōkai-based details page did: a unified Display-options surface, a cover-accent backdrop, two-finger range select, swipe-to-refresh from the source, and the favorite/library overflow actions.

## Goal

Bring Mihon's manga details screen up to the behavior Reikai users had on the old Yōkai base, without forking the screen. Reikai keeps Mihon's Compose + Voyager screen as-is and attaches its features through small `// RK` patches plus net-new helper code, so the page reads as one coherent surface rather than a port bolted on.

## Why

The rebase swapped Reikai's Yōkai-era details screen (a View-based Conductor controller with an RxJava presenter) for Mihon's Compose + Voyager `MangaScreen`. Mihon's version already carried a lot of what Reikai wanted (richer chapter rows, a cover-derived accent color, pull-to-refresh, a download tap-menu), so the work was less "port the old screen" and more "confirm Mihon covers it, then add only the Reikai-specific pieces Mihon lacks": multi-source merge, the Manage-sources UI, and the related-titles carousel. Doing it as `// RK` islands on Mihon's files (rather than a parallel screen) keeps future upstream syncs cheap and keeps a single details experience for the user.

## Approach

What landed and the mechanism behind each piece. This describes CURRENT behavior on `main`.

**Cover-accent backdrop.** Opening a manga tints the whole details screen with a color pulled from its cover art: a red cover gives a warm header, a blue cover a cool one. In Mihon this color is the `seedColor` field on the screen state, extracted from the cover bitmap (`MangaScreenModel.kt`, set inside `updateSeedColor`). Reikai's contribution is applying it as the live theme: a `// RK` block wraps the whole screen in `TachiyomiTheme(seedColor = ...)`, gated on a `themeCoverBased` preference so a user can turn it off (`MangaScreen.kt`). The blurred-cover backdrop and its vertical gradient live in the info header (`MangaInfoHeader.kt`).

**Swipe-to-refresh from the source.** Pulling down on the chapter list fetches fresh chapters (and metadata) from the source, with the cached list staying visible until the new data swaps in. This is Mihon-native: the chapter list is wrapped in `PullRefresh` (`MangaScreen.kt`) whose `onRefresh` calls `screenModel::fetchAllFromSource` (`MangaScreen.kt`). The screen model toggles an `isRefreshingData` flag around the source fetch and lets the reactive DB flow re-emit the synced chapters (`MangaScreenModel.kt`). Reikai did not need to add a fetch path here; it inherited Mihon's. (The novel details screen got the same gesture as its own port, documented under the light-novel plans.)

**Two-finger range select.** In chapter multi-select, picking two chapters selects everything between them, and the toolbar exposes select-all / invert. This is Mihon's existing chapter-list selection model: the list item wires the combined click / long-press (`MangaChapterListItem.kt`), but the range-fill itself (selecting everything between two picks via the tracked `selectedPositions`) lives in the screen model's `toggleSelection` (`MangaScreenModel.kt`), and select-all / invert go through `toggleAllSelection` (`MangaScreen.kt`). No Reikai patch was required.

**Richer chapter rows, download tap-menu, missing-chapter indicator, markdown description.** Each chapter row shows date, scanlator, and read progress; tapping the download icon opens a Start-now / Cancel / Delete menu; a "Missing N chapters" divider appears across gaps; the description renders markdown and is selectable. All of these are Mihon-native composables already present in `eu/kanade/presentation/manga/components/` (`MangaChapterListItem.kt`, `ChapterDownloadIndicator.kt`, `MissingChapterCountListItem.kt`, `MarkdownRender.kt`, `DotSeparatorText.kt`). The Yōkai-era plan had treated these as things to build; on Mihon they ship for free, so P3 reduced to verifying parity, not porting.

**Favorite / library overflow.** The favorite control and the chapter selection action bar expose the library actions a user expects (add/remove, mark read, download range, open in WebView, share). These ride Mihon's toolbar and bottom-action-menu composables (`MangaToolbar.kt`, `MangaBottomActionMenu.kt`).

**Reikai-specific additions (the real net-new work).** Three things Mihon does not have, attached via `// RK` islands in the screen model and screen:

- **Multi-source merge + Manage-sources UI.** When a manga is part of a merge group, the chapter list is the aggregated union across all grouped sources, a source-switcher chip row lets you view one source's chapters or the unified list, and a Manage-sources dialog lets you split or remove sources from the group. The aggregation, per-source chapter resolution (so download status and bookmark/read writes hit the right source), and group healing on open are all `// RK` blocks in `MangaScreenModel.kt` (merge group ids, the aggregated chapter list, the chip mirroring, and per-source download/bookmark/delete). The Manage-sources / merge UI itself is documented separately (see below), since it is its own subsystem.
- **Related-titles carousel.** A "Related" row of recommendation cards loads lazily on open (`MangaScreen.kt`, `MangaScreenModel.kt`); tapping a card opens that title (or routes to global search for tracker-origin cards with no installed source), and "See all" opens a full recommendations browse grid. The carousel is documented with the recommendations engine (see below).

## Key files

Mihon files patched with `// RK` islands:

- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`: the Voyager `Screen`; `// RK` blocks apply the cover-accent theme wrap, kick off the related carousel, and route merge/related actions.
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`: the `ScreenModel`; `// RK` blocks add merge-group aggregation, source-switcher state, cover seed-color extraction, related-manga loading, and the Manage-sources operations.

Mihon presentation composables (native, confirmed present, no Reikai edits needed for parity):

- `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`: the stateless screen body; wraps the chapter list in `PullRefresh`.
- `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt`: backdrop + gradient.
- `app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt`: chapter row metadata + selection.
- `app/src/main/java/eu/kanade/presentation/manga/components/ChapterDownloadIndicator.kt`: download tap-menu.
- `app/src/main/java/eu/kanade/presentation/manga/components/MissingChapterCountListItem.kt`: missing-chapter divider.
- `app/src/main/java/eu/kanade/presentation/manga/components/MarkdownRender.kt`: markdown description.
- `app/src/main/java/eu/kanade/presentation/manga/components/MangaToolbar.kt`, `MangaBottomActionMenu.kt`: toolbar + selection action bar.

## Status

Shipped. P3 is done (Roadmap P3, status done): merge / Manage-sources UI, private tracking, two-finger range select, cover-accent backdrop, and the recommendations carousel are all on `main` and on-device verified.

## Decisions & tradeoffs

- **Patch Mihon, don't fork it.** Everything possible rides Mihon's existing `MangaScreen` / `MangaScreenModel` through `// RK -->` / `// RK <--` islands, with net-new logic (merge, recommendations) in its own `reikai.*` code. This keeps a single details screen for users and keeps upstream syncs to a hand-merge inside the islands rather than reconciling two parallel screens.
- **Verify parity before porting.** The original Yōkai-era plan scoped a large list of chapter-row, refresh, and description features as work to build. On the Mihon base most of those are upstream-native, so the actual P3 effort collapsed to confirming Mihon covers them and adding only the genuinely Reikai-specific pieces. The plan's build list is therefore superseded by what Mihon already ships.
- **Cover-accent is opt-out.** The cover-derived theme is gated behind a `themeCoverBased` preference, so users who prefer the static app theme keep it; the screen falls back to the default `TachiyomiTheme` when off or when no color could be extracted.
- **Tablet two-pane layout deferred.** A side-by-side details layout was the one explicitly out-of-scope item (a large structural change); the screen uses Mihon's existing responsive single-pane behavior.

## Related plans

The two subsystems that surface on this screen are documented in their own plans, not duplicated here:

- [recommendations.md](recommendations.md): the related-titles carousel and recommendations engine.
- [merge-system-rebuild.md](merge-system-rebuild.md): the Manage-sources UI and the multi-source merge system.
