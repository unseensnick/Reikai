# Novel migration redesign

## Goal

Make novel migration (single and batch) genuinely usable: show covers so you can tell which result is the right book, surface the chapter count so you never migrate into a source with fewer chapters than you've read, and add the manga-style source-selection pre-step so you control which sources are searched and in what priority. The end state is on par with the browse-source catalogue for scanning results, while reading clearly as a migration tool (source to target), not a browse.

## Why

The current novel migration screen (`NovelMigrationListScreen`) renders every search result as a single line of text, `"SourceName: Title"`, with an expandable text list to override. Two concrete gaps make it hard to use:

- **No covers.** With near-namesake novels (`Reverend Insanity` vs `Reverend Insanity: Dream Gu`), you cannot tell which result is correct without opening each one. Covers are the fastest match signal, and the data is already present (`NovelItem.cover`); the screen just never draws it.
- **No chapter count.** Migration's real risk is moving to a source with fewer chapters than you've already read. Mihon's manga migration shows the chapter count on both sides precisely for this; the novel screen shows nothing, so a regression is invisible.
- **No source pre-step.** Manga opens a source-selection screen first (`MigrationConfigScreen`): pick which sources to search and drag them into priority order. Novel migration silently searches every installed source with no control, and the suggested top hit is just the first source's first result rather than the user's preferred source.

## Approach

Three independent phases, each shippable on its own and ordered so value lands early and the largest rebuild is last. All work stays inside Reikai's own files under `reikai.presentation.novel.migrate` and `reikai.domain.source`; no Mihon files are patched.

### Phase 1: covers + chapter-count signal

Add cover thumbnails to every result in the existing row layout (the suggested hit, the override candidate list, the chosen target) via the existing novel cover pipeline (`NovelCover` + `MangaCover.Book`, exactly as `NovelBrowseListCell` and `DuplicateNovelDialog` already do). Surface the chapter count on each side, and flag a target with fewer chapters than the source with a quiet warning color (load-bearing color per the brand: it means a regression risk).

Mostly view-layer. Two small `NovelMigrationListScreenModel` touches: carry the chosen source's `site` on the `Row` so the chosen-target cover loads with the right Referer, and expose the chapter counts.

Counts are split by cost. The source novel is already in the library, so its count is a free local read (`NovelChapterRepository.getByNovelId(...).size`) shown always. A search result (`NovelItem`) carries no count, and fetching one per result would mean a `parseNovel` detail hit for every candidate across every source: exactly the source hammering to avoid. So the target count, and the regression warning, appears only once a target is selected, where `materialize` already runs `refreshNovelFromSource` and populates the chapters; reading the count back is then free. Un-picked search results and the candidate picker show cover, title, and source only, never a count.

### Phase 2: source-selection pre-step

Add `NovelMigrationConfigScreen`, the novel twin of Mihon's `MigrationConfigScreen`: all installed novel sources split into **Selected** (reorderable, priority order) and **Available**, with select all / none / pinned bulk actions and a Continue action. Persist the selection and order to a new `novelMigrationSources` preference on `ReikaiSourcePreferences` (an ordered list of string source ids, mirroring `pinnedNovelSources` / `ln_pinned_sources`). Route the Migrate entry points through this screen first.

The migration list then searches only the selected sources, in the saved order, replacing the current search-all (`selectGlobalSearchSources(..., SourceFilter.All)` in `runSearch`). Because the suggested top hit is the first non-empty source result, honoring the saved order also makes the suggestion respect the user's source priority.

### Phase 3: comparison-row redesign + per-row actions

Restructure each row into a source-to-target comparison (the agreed mockup): both sides show cover, title, source, and chapter count, separated by an arrow that reads as "migrate". Replace the flat text override list with a browse-style cover grid grouped by source (the catalogue-parity picker; pick by tapping a cover, the selected target gets the in-library accent border). Keep the inline re-search field (a strength the novel screen already has over manga, which punts to a separate screen). Add per-row overflow actions (skip, search manually, migrate now, copy now) so a large batch is not all-or-nothing, and surface Copy vs Migrate as two distinct actions (Copy keeps the original, Migrate replaces it) instead of burying them in the confirm dialog. Phase 1's cover code relocates into the new cells here.

## Key files

- `reikai/presentation/novel/migrate/NovelMigrationListScreen.kt`, `NovelMigrationListScreenModel.kt`: the migration list (all three phases touch these).
- `reikai/presentation/novel/migrate/NovelMigrationConfigScreen.kt`: new in Phase 2, the source-selection pre-step.
- `reikai/domain/source/ReikaiSourcePreferences.kt`: new `novelMigrationSources` preference (Phase 2).
- `reikai/presentation/novel/browse/NovelBrowseGridCell.kt`, `reikai/data/coil/NovelCover.kt`: the existing cover pipeline reused for result thumbnails.
- Reference (Mihon, do not edit): `mihon/feature/migration/config/MigrationConfigScreen.kt` (pre-step), `mihon/feature/migration/list/MigrationListScreenContent.kt` (comparison row + per-row actions).

## Status

In progress (queued as Now in `ROADMAP.md`).

- **Phase 1: covers + chapter-count signal.** Implemented, compiles, pending on-device verification. Covers (tap to open details) on the suggested hit, candidate list, and chosen target; source chapter count always shown; target count with a red shortfall vs the source after selection. The cover tap opens details, which doubles as the verify path for the conservative paged-source count. Color choice: error on the delta only (count stays neutral).
- **Phase 2: source-selection pre-step.** Implemented, compiles, pending on-device verification. New `NovelMigrationConfigScreen` (Selected / Available, drag-reorderable priority, select all / none / pinned / enabled), saved to the new `novelMigrationSources` pref (ordered, newline-joined). The migration list searches only the selected sources in saved order (fallback to all when none saved). Both entry points (novel details, library multi-select) route through it first. Rows reuse `NovelSourceRow`; the screen mirrors Mihon's `MigrationConfigScreen`.
- **Phase 3: comparison-row redesign + polish.** Implemented, compiles, pending on-device verification. Each row is a source -> target comparison (both 48dp covers + an arrow). Per-row actions are manga-style: a one-tap Accept for a suggestion plus an overflow menu (Search manually, Don't migrate), keeping the lazy Accept -> materialize step (no per-result fetch). The override picker is a browse-style horizontal cover grid (`NovelBrowseGridCell`, 112dp cells) grouped by source: tap to pick, long-press to open details. The toolbar shows an "N novels - M matched" count (batch only), and the bottom bar offers Copy and Migrate as separate buttons; the what-to-include dialog then confirms the chosen action. Deferred: per-row migrate-now / copy-now (partial-batch convenience, low value).

## Decisions & tradeoffs

- **Phased, not one rewrite.** Covers (Phase 1) are the most-wanted fix and ship first without waiting on the full row rebuild. The minor rework (Phase 1's cover code moves into Phase 3's new cells) is cheap because it is the same `NovelCover` calls.
- **Chapter counts ride the materialise fetch, never a per-result fetch.** A count on every search result would need a `parseNovel` detail hit per candidate per source (source hammering). The target count instead reuses the fetch that already happens when a target is picked, so the regression signal costs nothing extra. The tradeoff: the count and warning appear after a target is selected, not on the result list. The source count is a free local read and is always shown.
- **Reuse the cover pipeline, do not invent a layout.** Thumbnails go through `NovelCover` + `MangaCover.Book` like the rest of the novel UI, keeping cross-format cohesion with manga migration.
- **A new pref, not reuse of `pinnedNovelSources`.** Pinned sources and migration-target sources are different intents; manga keeps them separate (`pinnedSources` vs `migrationSources`) and so should novels.
- **Stays in Reikai files.** This is a `reikai.*` screen; the manga screens are read as reference only, never patched.
