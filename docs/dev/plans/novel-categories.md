# Novel categories & hopper

Gives the Novels tab its own categories (the novel twin of manga categories), the floating category hopper plus its jump-to-category sheet, a tab-aware Display sheet, and background detection of light-novel plugin updates.

## Goal

Bring the Novels tab to parity with the Manga tab for everything category-shaped: saved novels group under their own categories, the user can create / rename / reorder / delete those categories, jump between them with the same floating hopper the manga library uses, and tune the Novels library's look from a Display sheet that writes to the right place. In the same area, the app learns when an installed light-novel plugin has a newer version upstream, so users get the same "needs updating" signal they already get for manga extensions.

## Why

Categories are how a library stays usable past a few dozen entries. Mihon gives that to manga; before this work the Novels tab had none of it, so a saved novel could only ever sit in one undifferentiated list with no fast way to navigate and no way to organise. The hopper (a small floating button that jumps you between categories without scrolling) and the Display sheet (grid size, badges, layout) are the other half of that experience. Light-novel plugins, unlike manga extensions, had no update detection at all, so a user could run a stale plugin indefinitely without knowing a fix had shipped. This work closes all of those gaps so the two content types feel like one product, not a manga app with novels bolted on.

## Approach

The Novels tab reuses Mihon's existing library screen, hopper, and category-manager UI rather than cloning them. The novel-specific data and logic live in their own `reikai.*` files; the only edits to Mihon's own files are small, fenced islands marked `// RK`.

### Novel categories (DB + UI)

Novels store their categories in their own tables and read them back through a novel category repository plus interactors (get / insert / delete / reorder), the exact mirror of Mihon's manga category stack. The category-management screen is shared: Mihon's `CategoryScreen` (a Voyager `Screen`) now takes a `novels: Boolean` flag and renders a Manga / Novels chip at the top; flipping to Novels swaps in `NovelCategoryScreenModel` behind the same `CategoryScreen` UI, dialogs, and action-mode toolbar (`app/src/main/java/eu/kanade/tachiyomi/ui/category/CategoryScreen.kt`). The novel screen model talks to the novel interactors but produces Mihon's `CategoryScreenState` / `CategoryDialog` types, so create / rename / reorder / hide / multi-select-delete all render through one set of composables for both content types.

Deletion is deferred, not immediate: selecting Delete moves the rows into a `pendingDeleteIds` set that is filtered out of the live category flow and only committed to the DB when the undo snackbar dismisses (`NovelCategoryScreenModel.kt`). Single-row delete routes through the same deferred path so it is undoable too, and leaving the screen commits any still-pending delete inside a `withNonCancellableContext` block so a cancelled snackbar coroutine never silently drops it (`NovelCategoryScreenModel.kt`). The synthesized Default category (id 0) is filtered out of the editable list, matching the manga side. Sort order respects the shared `categorySortOrder` preference via `reikaiSortCategories`.

### The category hopper + jump-to-category sheet on the Novels tab

The hopper is `ReikaiCategoryHopper`, a small rounded floating control with up / center / down buttons (`app/src/main/java/reikai/presentation/library/ReikaiCategoryHopper.kt`). Up and down jump to the previous / next category; the center button opens `ReikaiCategoryPickerSheet`, a bottom sheet listing every category (with item counts when enabled) so the user can jump straight to one (`app/src/main/java/reikai/presentation/library/ReikaiCategoryPickerSheet.kt`). The center button also has a long-press action.

Both the manga and novel libraries render through the same shared host (`LibraryTab.kt`), which draws the hopper and picker once, unconditionally, in the single-list view (`LibraryTab.kt`). The Novels tab is no longer special-cased: every hopper callback is content-aware, branching on the active content type. The center long-press dispatches all six actions (search, collapse/expand all, open Display options, open group-by, random-in-category, global-random), each routed to either the novel or manga screen model (`LibraryTab.kt`). The hopper can be dragged left / center / right to change its gravity, which persists per content type. The picker sheet decodes synthetic dynamic-grouping category names through `ReikaiDynamicCategory`, so it works for both real DB categories and dynamic groups.

The hopper appears when its visibility preference (`hideHopper`) is off and at least one category exists (`LibraryTab.kt`); it is not gated on search state. With autohide on, it fades while the list scrolls and returns when it settles.

### The tab-aware Display sheet

The library's Display sheet (sort, filter, display mode, badges, categories tabs) is shared between Manga and Novels. The host resolves both screen models and tracks which content type is active via `libraryContentType`; the sheet and its actions are routed to the active type's screen model (`LibraryTab.kt`). On the Novels tab the sheet's settings dialog is `NovelLibrarySettingsDialog` driven by `NovelLibraryScreenModel`; on the Manga tab it is Mihon's `LibrarySettingsDialog`. Crucially, the "Add or edit categories" / "Edit categories" buttons are tab-aware: on the Novels tab they push `CategoryScreen(novels = true)` (opening straight on the Novels category manager), and on the Manga tab they push `CategoryScreen()` (`LibraryTab.kt`). The change-category dialog and library settings sheet both route their edit-categories action the same way.

### Light-novel plugin update detection

Manga extensions get a "needs updating" badge; light-novel plugins now get the same signal. `LnPluginUpdateChecker` diffs each added registry's latest plugin `version` against the version stored at install time, using a single version comparator, and returns the outdated list (`app/src/main/java/reikai/novel/update/LnPluginUpdateChecker.kt`). Individual repo fetch failures do not fail the batch, so one down registry never hides updates from the others. `runIfStale()` wraps the diff with a 6-hour cache for an on-launch / on-resume path; a WorkManager job (`LnPluginUpdateJob`, 12h interval, 1h flex, network required) runs the same check on its own schedule, writes the Browse-tab badge count, and posts a notification listing the outdated plugins (`app/src/main/java/reikai/data/novel/update/LnPluginUpdateJob.kt`).

## Key files

Novel categories (data + logic):
- `app/src/main/java/reikai/domain/novel/NovelCategoryRepository.kt`, `app/src/main/java/reikai/data/novel/NovelCategoryRepositoryImpl.kt`: the novel category repository.
- `app/src/main/java/reikai/domain/novel/model/NovelCategory.kt`, `NovelCategoryUpdate.kt`: the novel category domain model and partial-update.
- `app/src/main/java/reikai/domain/novel/interactor/`: `GetNovelCategories`, `InsertNovelCategories`, `DeleteNovelCategories`, `ReorderNovelCategories`.
- `app/src/main/java/reikai/presentation/library/novels/NovelCategoryScreenModel.kt`: the Novels-side category manager screen model (multi-select, deferred delete, reorder, hide).

Category-manager UI (shared, `// RK` islands):
- `app/src/main/java/eu/kanade/tachiyomi/ui/category/CategoryScreen.kt`: the shared category screen, `novels: Boolean` flag + Manga/Novels chip; swaps in `NovelCategoryScreenModel`.

Hopper + picker (single-list view):
- `app/src/main/java/reikai/presentation/library/ReikaiCategoryHopper.kt`: the floating up/center/down control.
- `app/src/main/java/reikai/presentation/library/ReikaiCategoryPickerSheet.kt`: the jump-to-category bottom sheet.
- `app/src/main/java/reikai/presentation/library/ReikaiDynamicCategory.kt`: dynamic-group name decoding used by the picker.

Library host (shared, `// RK` islands):
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`: resolves both screen models, the content-type chip, the unconditional hopper + picker, content-aware callbacks, and the tab-aware Display sheet + edit-categories routing.
- `app/src/main/java/reikai/presentation/library/novels/NovelLibraryScreenModel.kt`: the Novels library screen model (state, dialogs, hopper actions, category filter).
- `app/src/main/java/reikai/presentation/library/novels/NovelLibrarySettingsDialog.kt`: the Novels-side Display sheet.

LN plugin update detection:
- `app/src/main/java/reikai/novel/update/LnPluginUpdateChecker.kt`: the version diff + `runIfStale` cache.
- `app/src/main/java/reikai/data/novel/update/LnPluginUpdateJob.kt`: the periodic WorkManager check + notification + badge count.

## Status

Shipped. Novel categories, the hopper and jump-to-category sheet on the Novels tab, the tab-aware Display sheet, and LN plugin update detection are all on-device verified (P5 S6 area). Two follow-ups landed later as round-2 parity items: the novel library category include/exclude filter, which also extracted the shared `CategoryFilterRow` reused by the manga library and the Updates feed (Roadmap item 2), and category bulk-delete on the shared category manager for both tabs (Roadmap item 6).

## Decisions & tradeoffs

- **Reuse Mihon's category screen, do not clone it.** `CategoryScreen` takes a `novels` flag and a Manga/Novels chip instead of a separate novel screen. One set of composables, dialogs, and action-mode toolbar serves both content types; the only divergence is which screen model backs them. This keeps the patch surface against upstream tiny (a fenced flag, not a parallel screen).

- **Deferred, undoable deletes over immediate DB writes.** Deleting a category hides it from the live flow and commits to the DB only when the undo snackbar dismisses, so undo never needs a lossy re-insert (junction tables cascade their membership rows, and a re-inserted category would get a new id). Single-row delete shares the same deferred path. The cost is the extra `pendingDeleteIds` bookkeeping and the non-cancellable commit-on-leave, which is worth it to make undo correct rather than approximate.

- **One shared hopper, content-aware callbacks.** Rather than disabling the hopper on the Novels tab or duplicating it, the shared host draws it once and routes each callback (jump, long-press actions, gravity drag) to whichever content type is active. Hopper preferences (gravity, autohide, long-press action) are tracked per content type so the two libraries can diverge.

- **Tab-aware actions, shared sheet shell.** The Display sheet shell is shared, but the edit-categories buttons and the settings dialog route to the active content type. This avoids a Novels-tab Edit button silently opening the manga category manager (the prior bug) without forking the sheet UI.

- **Plugin update detection mirrors manga, fails soft.** The LN checker reuses the manga update-badge mental model (a count plus a notification plus a periodic job) so users do not learn a second concept. A failed repo fetch is logged and skipped rather than aborting the batch, so a single bad registry URL cannot hide updates from healthy repos. The 6-hour `runIfStale` cache keeps quick relaunches from hammering every registry.

## See also

- [docs/categories.md](../../categories.md): user-facing reference for how categories behave across the app.
- [library-tabbed-shell.md](library-tabbed-shell.md): the shared tabbed / single-list library shell the Novels tab plugs into.
- [novel-update-job.md](novel-update-job.md): the novel library-update background job (sibling to the plugin-update job above).
