# Novel details screen

The light-novel details screen (`NovelScreen`), the novel twin of the manga details screen, at feature parity for everything that makes sense for novels: chapter list, downloads, description and tinted backdrop, and the overflow actions.

## Goal

Give a light novel its own details screen that feels identical to the manga one: open a novel from browse, search, the library, or History, and land on a screen with the cover and backdrop up top, an expandable description, and the chapter list below, with the same toolbar, multi-select bar, download controls, and overflow menu a manga reader already knows. A user should not be able to tell, by feel, that they are looking at a novel rather than a manga.

## Why

The manga details screen is the hub of the manga experience: it is where you favorite, browse chapters, download, edit metadata, and start reading. The novel vertical needs the same hub or novels are second-class. Matching the manga screen's layout and interactions (a standing requirement for the fork) means a user carries one mental model across both content types, and it lets the novel screen reuse Mihon's existing detail building blocks instead of inventing parallel UI.

## Approach

`NovelScreen` is a Voyager screen that mirrors the manga details screen's two layouts (a phone single column whose toolbar title and background fade in as you scroll past the cover, and a tablet two-pane split with chapters on the right). All the logic (loading, refreshing, sorting, filtering, favoriting, downloading, multi-select) lives in `NovelDetailsScreenModel`, which exposes one `StateFlow` the screen renders over. The novel-specific pieces are net-new (the info box, action row, toolbar, edit-info and chapter-settings dialogs) because Mihon's equivalents are hard-typed to `Manga`/`Chapter`. The genuinely type-neutral pieces (the expandable description, the chapter row, the chapter header, the bottom multi-select menu) are reused from Mihon as-is.

What it mirrors from manga details:

- **Layout.** `NovelDetailsSmallImpl` / `NovelDetailsLargeImpl` mirror Mihon's `MangaScreenSmallImpl` / `MangaScreenLargeImpl`: phone `LazyColumn` of info box, action row, description, chapter header, then chapters; tablet `TwoPanelBox` with info on the left and chapters on the right.
- **Backdrop and cover tint.** The info box renders a blurred cover backdrop that bleeds edge-to-edge behind the toolbar; the screen wraps content in `TachiyomiTheme(seedColor = ...)` so the palette is extracted from the cover, the novel twin of the manga per-cover theming.
- **Reused composables.** `ExpandableMangaDescription`, `ChapterHeader`, `MangaChapterListItem`, and `MangaBottomActionMenu` are Mihon's, consumed directly because they take primitives (strings, lambdas) not domain types.
- **Resume FAB.** `NovelResumeFab` is the twin of the manga resume button: jumps to the first unread chapter, collapses to an icon on scroll, hides when everything is read or in selection mode.
- **DB-first loading.** The ScreenModel observes the stored novel and its chapters; the source plugin is only hit on first open (no local chapters yet) or an explicit pull-to-refresh, exactly like the manga screen reads from the database and refreshes on demand.

Where it diverges:

- **Different types and infra.** Novels use `Novel`/`NovelChapter`, `NovelRepository`/`NovelChapterRepository`, `NovelDownloadManager`, `NovelMergeManager`, and `NovelPreferences`, so the ScreenModel business logic is its own, not shared with the manga model. It follows the manga shape; it does not import the manga model.
- **Reading-order routing.** A chapter tap hands the reader the chapters in reading order (ascending `sourceOrder`, the restamped cross-source order for a merged group), independent of the details display sort, so "next" always advances forward.
- **Paged sources.** Some LN sources expose chapters one page at a time. The header shows a "Page n / N" bar and a page-selector sheet; sort and filter are page-scoped. Manga has no equivalent.
- **Notes on the anchor.** Per-novel notes attach to the favorited (anchor) row, not the viewed source's metadata, so a merged group's notes stay stable when you switch source chips.

Current behavior of the overflow and toolbar: a Filter action (tinted when a filter is active) opens the chapter-settings sheet; the overflow holds Refresh, Edit categories, Edit info, Notes, Manage sources (merged only), Migrate (favorited only), Share, and Show hidden chapters (when any exist); a download dropdown queues by count or status; the action-mode bar offers select-all, invert, hide/unhide; the bottom menu marks read/unread, bookmarks, mark-previous, downloads, and deletes the selection.

## Key files

All under `app/src/main/java/reikai/presentation/novel/details/`:

- `NovelScreen.kt`: the Voyager `Screen` (constructor: `sourceId`, `novelUrl`); phone + tablet layouts, the resume FAB, the selection bar, dialog host, and the `LazyListScope` builders for the info, header, and chapter items.
- `NovelDetailsScreenModel.kt`: `StateScreenModel<NovelDetailsState>`; injects the novel repos, source manager, plugin installer, category interactors, preferences, merge manager, and (Active 8) the tracker interactors. Owns loading, refresh, favorite/categories, edit-info, sort/filter/display, multi-select, download actions, cover-tint seed, and the merge/tracking seams.
- `NovelToolbar.kt`: the `MangaToolbar` twin: filter action, overflow, download dropdown, action-mode controls, scroll-driven title/background fade.
- `NovelInfoBox.kt`: net-new cover, backdrop, title, source, status header (Mihon's `MangaInfoBox` is `Manga`-typed), plus the `NovelActionRow` (favorite / tracking / web-view actions).
- `NovelDetailsDialogs.kt`: `NovelCategoryDialog`, `EditNovelInfoDialog`, and `NovelChapterSettingsDialog`.
- `NovelCoverScreenModel.kt`: drives the full-cover viewer (save/share/edit); the viewer itself is the shared `reikai/presentation/components/EntryCoverDialog.kt`, hosted here by `NovelCoverDialogHost`.
- `NovelMergeSourceChips.kt` / the shared `ManageMergeSourcesDialog`: the merge source switcher and Manage-sources sheet (see merge-system-rebuild.md).
- `NovelPageSelectorSheet.kt`: the paged-source page picker.

## Status

Shipped and on-device verified. The screen carries the full manga-parity feature set (chapter list, download dropdown and per-chapter download, edit-info, sort/filter/display, multi-select, cover viewer, refresh, merge, tracking, migration, notes), well beyond the original single-source slice it started as.

## Decisions & tradeoffs

- **Net-new novel components, reuse only the type-neutral primitives.** Mihon's info box, action row, and toolbar are `Manga`-typed, so cloning them as novel twins was cheaper and lower-risk than generifying upstream code (which would widen the patch surface against Mihon). The description, chapter row, chapter header, and bottom menu take primitives, so those are reused directly. Tradeoff: some structural duplication between the manga and novel screens, accepted because it keeps the two screens independently evolvable and the Mihon files near-untouched.
- **Per-surface ScreenModel, shared shape.** The novel model does not share code with the manga model (different types and infra); it follows the same shape. This keeps each content type's logic self-contained at the cost of two parallel models.
- **Metadata follows the viewed source; library state stays on the anchor.** For a merged novel, the header and description read the selected source (or the anchor when viewing "All"), but favorite, categories, and notes always act on the favorited anchor row, so switching source chips never moves library state.
- **Reader gets reading-order chapter ids, not the display order.** Decoupling the reader's chapter sequence from the details sort means "next" advances forward regardless of how the list is sorted on screen.

Features that attach to this screen but are documented separately, to avoid duplication: the reader launched from a chapter tap (novel-reader.md), multi-source merge and the Manage-sources flow (merge-system-rebuild.md), the Tracking sheet and auto-sync (novel-tracking.md), and the remaining parity items including per-novel Notes, source migration, and the bulk-download dropdown (novel-parity-backlog.md).
