# Implementation & decision records

One markdown per substantial Reikai feature or initiative: a developer-facing record of what was built and why. These describe current behavior and the decisions behind it, not the chronology of how a plan evolved.

These are distinct from the user-facing architecture references in [`docs/`](../../) and [`docs/dev/`](../) (for example [multi-source.md](../../multi-source.md), [related-mangas.md](../../related-mangas.md), [tracker-sync.md](../../tracker-sync.md), [ln-plugin-host.md](../ln-plugin-host.md)). The plan docs cross-link those rather than duplicating them.

The format these follow, and the rule for what earns a doc here, live in [.claude/rules/workflow.md](../../../.claude/rules/workflow.md) ("Roadmap & plans"). The forward-looking backlog is [ROADMAP.md](../../../ROADMAP.md).

## Foundation

- [Reikai → Mihon rebase overview](rebase-overview.md): why Reikai moved off Yōkai onto Mihon, the phased structure (P0-P9), the `// RK` patch convention, and the decisions behind it.
- [ViewModel migration](viewmodel-migration.md): taking Mihon's move off Voyager `ScreenModel` onto AndroidX `ViewModel` across Reikai's own screens too, phased so each cluster ships compiling, with the R8 risk proven up front. Deferred (attempted once, reverted, parked pending an upstream release).
- [Legacy Yōkai database import](legacy-yokai-import.md): recovering a user's manga + novel library when they update in place from a pre-rebase Yōkai build, instead of crashing on the now-incompatible shared `tachiyomi.db`.
- [FlareSolverr Cloudflare bypass integration](flaresolverr-integration.md): the optional bypass proxy (Solverr / Byparr / FlareSolverr) as a WebView fallback for Cloudflare-gated sources, proxy-mode not cookie-replay, with all mechanics in a net-new `FlareSolverrClient` and a minimal `// RK` detection island.

## Library & shell

- [Library screen carry (P2)](library-screen-carry.md): how Reikai's single-list hopper library, multi-select actions, and opt-in update-errors screen ride Mihon's data pipeline via a parallel `reikai.*` renderer plus `// RK` islands.
- [Library tabbed shell (Manga + Novels)](library-tabbed-shell.md): one Library and Browse surface for both manga and novels via a content-type chip, with novels routed through Mihon's existing views.
- [Library sort: global + per-category overrides](library-sort-overrides.md): with "Per-category setting for sort" on, one global sort every category follows unless explicitly overridden, via a manga `CUSTOMIZED` flag bit mirroring novels, override-aware reads, a shared effective-sort header, and a reset-to-global action.

## Manga

- [Manga details parity (P3)](manga-details-parity.md): reaching parity on Mihon's manga details screen: cover-accent backdrop, swipe-to-refresh, two-finger range select, and the merge / Manage-sources additions.
- [Merge-aware manga reader](merge-aware-manga-reader.md): read a merged manga straight through all its sources in the reader (unified list with per-source labels, cross-source prev/next, per-source side effects), via a thin Reikai layer over Mihon's reader. The reader-layer record; reader scope-by-context is covered by the merge system rebuild.
- [Merge system rebuild](merge-system-rebuild.md): the multi-source merge (manga + novels) rebuilt onto a persisted group identity (the `merge_group` + per-type member tables) instead of a per-call derivation from preferences, fixing silent membership corruption, unmerges lost on restore, two disagreeing group definitions, browse pre-merging by title, and a library scan per open. Supersedes the pref-era merge-engine records. In progress on feat/0.4.0: all phases done and on-device verified (including slice 5, the chip-row collapse). The broader parallel-component consolidation onto the Entry* seam is a separate deferred initiative, planned in [merge-component-consolidation.md](merge-component-consolidation.md).
- [Recommendations & related carousel (P6)](recommendations.md): five suggestion streams merged, taste-reranked, tracker-gated cross-recs, and a See-all bulk-add grid.
- [MangaDex enhanced source](md-enhanced-source.md): wrap the installed MangaDex extension with metadata, OAuth login, an MDList tracker, follows sync, and a settings hub, reusing the EXH enhancement machinery. Feature-complete; the rest of Phase 6 was evaluated and deliberately dropped.

## Adult sources

- [Adult-source (EXH) subsystem](exh-subsystem.md): built-in E-Hentai / ExHentai, enhanced-source metadata indexing + library tag search, metadata viewer, account uconfig sync, favorited-gallery update checker, and a one-way favorites backup. Ported from Komikku, re-typed onto Mihon, wired in via `// RK` islands; the lighter-slice descopes and the path to Komikku parity.
- [Adult-source browse parity](adult-browse-parity.md): why the built-in EH browse only loads its first page and shows bare rows, traced to the dropped `MetadataMangasPage` carrier + `EHentaiPagingSource` cursor + metadata rendering, and the two-piece port that reached Komikku parity. Shipped.
- [Library tag-search engine](library-tag-search.md): the structured tag query language (`namespace:tag`, wildcards, exclusion, exact, aliases) for the library search bar, a trimmed in-memory port of Komikku's `exh/search` with wildcards made to actually work.

## Light novels

- [Novel browse & sources](novel-browse.md): how LN plugins install, fold into the unified Browse, fan out across global search, and reach manga-browse parity per source.
- [Novel details screen](novel-details.md): the light-novel detail hub at manga parity (tinted backdrop, chapter list, downloads, overflow actions), built on a Voyager `NovelScreen` + `NovelDetailsScreenModel`.
- [Novel reader](novel-reader.md): WebView text canvas rendering plugin HTML inside native Compose chrome, with scroll resume, read-state sync, and per-novel display settings. Read-aloud (TTS) shipped with background playback and a lock-screen media notification.
- [Novel reader: tsundoku as the foundation](novel-reader-tsundoku.md): evaluation of tsundoku (a maintained, Apache-2.0 Mihon-fork novel reader) as the basis for a near-term seamless-transitions port (Option 1) and a long-term migration to its native reader folded into `ReaderActivity` (Option 3). Recommended, deferred.
- [Headless LN plugin host (QuickJS)](novel-plugin-host.md): why and how novel sources run in a headless QuickJS runtime instead of a WebView, so they work in the background, with polyfill completeness as the make-or-break constraint.
- [Novel categories & hopper](novel-categories.md): the Novels tab gains its own categories, the shared category hopper + jump-to-category sheet, a tab-aware Display sheet, and LN plugin update detection.
- [Novel background update job](novel-update-job.md): the WorkManager worker that refreshes favorited novels on a schedule, with per-category gating and a smart-update skip filter, the novel twin of Mihon's library updater.
- [Novel tracking](novel-tracking.md): bind novels to any of the seven trackers that can tell novels apart and sync reading progress, reusing Mihon's trackers, group-aware across merged sources.
- [Novel backup & restore](novel-backup.md): light novels ride the same backup file as manga (chapters, history, tracks, categories, id-remapped merges) plus an installed-sources record that reinstalls extensions and re-downloads plugins on restore.
- [Novel parity backlog (shipped)](novel-parity-backlog.md): the manga↔novel parity features (history, migration, library modes, reader, stats, browse) that make novels feel native, with per-item commit SHAs and the deliberate trims.
- [Novel migration redesign](novel-migration-redesign.md): covers and chapter-count signal on results, a manga-style source-selection pre-step, and a source-to-target comparison row. Shipped.

## Unified surfaces (manga + novel)

- [Unified Updates tab](unified-updates.md): one Updates feed interleaving manga + novel behind an All / Manga / Novels chip, with shared filters, by-category, and merge-aware group-by-series.
- [Unified reader (shared chrome)](unified-reader.md): manga and novel readers share one Compose chrome layer; `ReaderActivity` stays the View-based manga host (Option F), with the single-Compose-shell approach (Option A) explored and reverted.
- [Content layer architecture](content-layer-architecture.md): the deeper unification that extends the Entry* UI-leaf seam down into the ScreenModel behavior, a Reikai-owned shared behavior + UI layer over a neutral `Entry` vocabulary with thin Manga/Novel adapters (the manga model stays live and synced), a delete-and-manifest dead-file policy replacing keep-inert, and the tsundoku reader migration folded in as the final phase. Designed, not started; first surface is details.
- [Content layer: the details surface](content-layer-details-surface.md): the first surface cut over, one shared behavior + screen driving both manga and novel details over the neutral Entry* UI via a `MangaEntryAdapter` (reads the live `MangaScreenModel`) and a `NovelEntryAdapter` (dissolves `NovelDetailsScreenModel`), sequenced 0 to 5 with the manga body-UI migration isolated as the second checkpoint. Planned, not started.
- [Merge component consolidation](merge-component-consolidation.md): collapse the parallel manga/novel merge components (the two near-twin managers, and the per-ScreenModel group read/observe wiring) onto the shared Entry* seam, so a merge behavior is written once. The last fork the merge-system rebuild left. Planned, not started; deep-scouted.
- [Unified content UI (manga + novels + adult)](unified-content-ui.md): the shipped UI-leaf seam that the content layer builds on, collapse the near-duplicate presentation stacks into one Reikai-owned pixel layer rendering a content-agnostic UI model. P1-P6 (list surfaces, cover dialog, details screen) shipped; the forward architecture now lives in content-layer-architecture.md.
- [Manga/novel parity: drift fixes + twin collapse (round 2)](content-parity-drift-and-collapse.md): the next unification wave from a 2026-07-13 deep audit, fix the silent behavioural drifts in the still-twin surfaces (merged-novel read/bookmark propagation, browse toolbar, global-search sort, first-download prompt, migration spinner), then collapse browse / global search / library-settings / notes / migration onto the shared Entry* seam. Phases 1-7 done, minus the collapses assessed and declined.
- [Download-queue unification](download-queue-unification.md): manga (View) + novel (Compose) download queues merged onto one shared Compose component with a neutral row model, source grouping, the full move/cancel overflow menu, and a Reorder Mode for whole-source ordering (the reorder library can't do in-place header drag). Shipped.
- [Novel download storage re-key](novel-download-storage.md): novel downloads moved off unstable numeric-id paths + a wiped DB flag onto manga's stable-name folders + a disk-scan cache, so they survive reinstall / restore / storage-move, with rename-on-sync, temp-then-rename writes, and a one-time relocation migration. Sets up the download-subsystem unification (Road B).
