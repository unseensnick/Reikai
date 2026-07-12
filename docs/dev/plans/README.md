# Implementation & decision records

One markdown per substantial Reikai feature or initiative: a developer-facing record of what was built and why. These describe current behavior and the decisions behind it, not the chronology of how a plan evolved.

These are distinct from the user-facing architecture references in [`docs/`](../../) and [`docs/dev/`](../) (for example [multi-source.md](../../multi-source.md), [related-mangas.md](../../related-mangas.md), [tracker-sync.md](../../tracker-sync.md), [ln-plugin-host.md](../ln-plugin-host.md)). The plan docs cross-link those rather than duplicating them.

The format these follow, and the rule for what earns a doc here, live in [.claude/rules/workflow.md](../../../.claude/rules/workflow.md) ("Roadmap & plans"). The forward-looking backlog is [ROADMAP.md](../../../ROADMAP.md).

## Foundation

- [Reikai → Mihon rebase overview](rebase-overview.md): why Reikai moved off Yōkai onto Mihon, the phased structure (P0-P9), the `// RK` patch convention, and the decisions behind it.
- [Legacy Yōkai database import](legacy-yokai-import.md): recovering a user's manga + novel library when they update in place from a pre-rebase Yōkai build, instead of crashing on the now-incompatible shared `tachiyomi.db`.

## Library & shell

- [Library screen carry (P2)](library-screen-carry.md): how Reikai's single-list hopper library, multi-select actions, and opt-in update-errors screen ride Mihon's data pipeline via a parallel `reikai.*` renderer plus `// RK` islands.
- [Library tabbed shell (Manga + Novels)](library-tabbed-shell.md): one Library and Browse surface for both manga and novels via a content-type chip, with novels routed through Mihon's existing views.

## Manga

- [Manga details parity (P3)](manga-details-parity.md): reaching parity on Mihon's manga details screen: cover-accent backdrop, swipe-to-refresh, two-finger range select, and the merge / Manage-sources additions.
- [Manga merge engine (P4)](manga-merge-engine.md): pref-based multi-source manga merge (group algebra, distinct-count trunk + float-keyed chapter pooling, copy-on-write tracker mirroring) plus the FlareSolverr Cloudflare escape hatch.
- [Merge-aware manga reader](merge-aware-manga-reader.md): read a merged manga straight through all its sources in the reader (unified list with per-source labels, cross-source prev/next, per-source side effects), via a thin Reikai layer over Mihon's reader.
- [Recommendations & related carousel (P6)](recommendations.md): five suggestion streams merged, taste-reranked, tracker-gated cross-recs, and a See-all bulk-add grid.
- [MangaDex enhanced source](md-enhanced-source.md): wrap the installed MangaDex extension with metadata, OAuth login, an MDList tracker, follows sync, and a settings hub, reusing the EXH enhancement machinery. Phased plan; Phases 0-2 shipped (delegated wrap + enriched details), 3-6 remain.

## Adult sources

- [Adult-source (EXH) subsystem](exh-subsystem.md): built-in E-Hentai / ExHentai, enhanced-source metadata indexing + library tag search, metadata viewer, account uconfig sync, favorited-gallery update checker, and a one-way favorites backup. Ported from Komikku, re-typed onto Mihon, wired in via `// RK` islands; the lighter-slice descopes and the path to Komikku parity.
- [Adult-source browse parity](adult-browse-parity.md): why the built-in EH browse only loads its first page and shows bare rows, traced to the dropped `MetadataMangasPage` carrier + `EHentaiPagingSource` cursor + metadata rendering, and the planned two-piece port to reach Komikku parity. Planned.
- [Library tag-search engine](library-tag-search.md): the structured tag query language (`namespace:tag`, wildcards, exclusion, exact, aliases) for the library search bar, a trimmed in-memory port of Komikku's `exh/search` with wildcards made to actually work.

## Light novels

- [Novel browse & sources](novel-browse.md): how LN plugins install, fold into the unified Browse, fan out across global search, and reach manga-browse parity per source.
- [Novel details screen](novel-details.md): the light-novel detail hub at manga parity (tinted backdrop, chapter list, downloads, overflow actions), built on a Voyager `NovelScreen` + `NovelDetailsScreenModel`.
- [Novel reader](novel-reader.md): WebView text canvas rendering plugin HTML inside native Compose chrome, with scroll resume, read-state sync, and per-novel display settings. LNReader's engine extras are deliberately off as Yōkai-parity (TTS gated by the host-polyfill concern).
- [Novel reader: tsundoku as the foundation](novel-reader-tsundoku.md): evaluation of tsundoku (a maintained, Apache-2.0 Mihon-fork novel reader) as the basis for a near-term seamless-transitions port (Option 1) and a long-term migration to its native reader folded into `ReaderActivity` (Option 3). Recommended, deferred.
- [Headless LN plugin host (QuickJS)](novel-plugin-host.md): why and how novel sources run in a headless QuickJS runtime instead of a WebView, so they work in the background, with polyfill completeness as the make-or-break constraint.
- [Novel categories & hopper](novel-categories.md): the Novels tab gains its own categories, the shared category hopper + jump-to-category sheet, a tab-aware Display sheet, and LN plugin update detection.
- [Novel background update job](novel-update-job.md): the WorkManager worker that refreshes favorited novels on a schedule, with per-category gating and a smart-update skip filter, the novel twin of Mihon's library updater.
- [Novel cross-source merge](novel-merge.md): how merged novels pool chapters across sources via the shared `MergeGroupAlgebra`, title-first stitching, a per-source switcher, and split / dissolve.
- [Novel tracking](novel-tracking.md): bind novels to AniList / MyAnimeList / MangaUpdates / Kitsu and sync reading progress, reusing Mihon's trackers, group-aware across merged sources.
- [Novel backup & restore](novel-backup.md): light novels ride the same backup file as manga (chapters, history, tracks, categories, id-remapped merges) plus an installed-sources record that reinstalls extensions and re-downloads plugins on restore.
- [Novel parity backlog (shipped)](novel-parity-backlog.md): the manga↔novel parity features (history, migration, library modes, reader, stats, browse) that make novels feel native, with per-item commit SHAs and the deliberate trims.
- [Novel migration redesign](novel-migration-redesign.md): covers and chapter-count signal on results, a manga-style source-selection pre-step, and a source-to-target comparison row. Shipped.

## Unified surfaces (manga + novel)

- [Unified Updates tab](unified-updates.md): one Updates feed interleaving manga + novel behind an All / Manga / Novels chip, with shared filters, by-category, and merge-aware group-by-series.
- [Unified reader (shared chrome)](unified-reader.md): manga and novel readers share one Compose chrome layer; `ReaderActivity` stays the View-based manga host (Option F), with the single-Compose-shell approach (Option A) explored and reverted.
- [Unified content UI (manga + novels + adult)](unified-content-ui.md): collapse the three near-duplicate presentation stacks into one Reikai-owned pixel layer rendering a content-agnostic UI model, killing the manga↔novel duplication and giving one place to move off stock Material 3. Planned.
- [Download-queue unification](download-queue-unification.md): manga (View) + novel (Compose) download queues merged onto one shared Compose component with a neutral row model, source grouping, the full move/cancel overflow menu, and a Reorder Mode for whole-source ordering (the reorder library can't do in-place header drag). In progress.
- [Novel download storage re-key](novel-download-storage.md): novel downloads moved off unstable numeric-id paths + a wiped DB flag onto manga's stable-name folders + a disk-scan cache, so they survive reinstall / restore / storage-move, with rename-on-sync, temp-then-rename writes, and a one-time relocation migration. Sets up the download-subsystem unification (Road B).
