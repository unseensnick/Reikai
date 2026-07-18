# Content layer architecture

## Goal

One Reikai-owned content layer, shared behavior and shared UI over a neutral `Entry` vocabulary, that serves manga and light novels so a feature is written once and reaches both. It extends the shipped Entry* UI-leaf seam ([unified-content-ui.md](unified-content-ui.md)) down into the ScreenModel behavior, replaces the current dead-Mihon-file policy with one a solo+AI workflow can trust, and folds the reader unification in as its final phase. The end state: novels stop being a re-typed copy of the manga stack, and there are no dead files an edit can silently land in.

## Why

Two problems with one root cause: the shared seam is drawn too high (UI only) and too informally (ad-hoc reroutes, no enforcement).

- **Duplication is the daily cost.** The light-novel port copy-adapted the manga stack, so `NovelDetailsScreenModel` (~1300 lines) is largely `MangaScreenModel` re-typed onto `Novel`. The two details ScreenModels are roughly 70-75% congruent, and the multi-source merge methods (`selectSource`, `splitSources`, `reorderSources`, `removeSourcesFromLibrary`, and the rest) are line-for-line twins. The shipped Entry* work shared the UI leaves but left this behavior forked, so every manga/novel change is two edits, and the two sides drift.
- **The sync/bury hazard.** There are four distinct integration patterns against Mihon today: in-place delegation (the details screen, `// RK` islands calling Entry* components), inert-leaf tombstones (`MangaCoverDialog`, `TrackInfoDialog`, `GlobalSearchCardRow`, `DownloadQueueScreenModel`, marked `// RK: inert`), whole-screen reroutes that left Mihon composables dead **and unmarked** (`HistoryScreen`, `UpdatesScreen`, `SourcesTab`, `MigrateSourceTab`, bypassed by `ReikaiHistoryScreen`/`ReikaiUpdatesScreen`/`reikaiSourcesTab`/`reikaiMigrateSourceTab`), and pure novel twins with no Mihon equivalent. Nothing flags an off-render-path Mihon file that changed upstream, so a synced fix can land on a dead file and do nothing, and an edit can be made against a file that is never rendered.
- **Solo+AI needs a different shape.** One obvious place to write shared behavior, no dead-file traps, and a seam that fails loudly at compile time when upstream drifts.

## Approach

Three layers, one hard ownership rule.

- **Engines stay split and unequal.** Mihon's manga engine (`Manga` model, its repositories and source/library/download machinery) stays upstream-tracked and minimally patched. Reikai's novel engine (`Novel` model, plugin host, novel repositories) stays fully Reikai-owned. They are never merged; merging them would re-type Mihon's whole stack and sever upstream flow.
- **Adapters are the only seam.** A `MangaEntryAdapter` reads Mihon's `MangaScreenModel`, which stays **live** and on the render path, so the manga engine keeps syncing and is never bypassed. A `NovelEntryAdapter` reads the novel engine, and `NovelDetailsScreenModel` dissolves into that adapter plus the shared behavior. The adapter is the single point of Mihon coupling; a renamed upstream field breaks it at compile time, not in a pixel hunt.
- **The content layer is Reikai-owned and holds the shared behavior plus the Entry* UI**, written once and driving both content types over the `Entry` interface and a set of typed capability slots.

**The shared behavior spine** (both types): chapters list, selection (toggle / all / invert / range), filter / sort / display, download actions, mark read / previous / bookmark / delete, tracking with tracker autofill, categories, cover dialog, custom-info edit (already routed through the neutral `EntryEditInfoUi`), hide / unhide chapters, the whole merge / multi-source subsystem (already sharing `MergeGroupRepository` at the domain layer), favorite / duplicate, and refresh.

**Capability slots** are typed, not nullable fields, so the shared model never rots into nullable soup. Each mapper fills only what applies.

- Manga: page previews, related-manga carousel, gallery / adult metadata, E-Hentai account, and scanlator filtering. Scanlator filtering is modeled as a **content-type-gated** capability rather than a manga-forever one: novels can light it up later (translator groups) once the novel schema carries a group column. It is not a free port from tsundoku, whose novels inherit the manga `scanlator` field only because tsundoku makes novels be manga rows, a design Reikai rejects.
- Novel: paged-source page selector and the plugin-installer lifecycle.

**Reconciliations the interface must handle**, surfaced by comparing the two ScreenModels: the source id is `String` for novels and `Long` for manga (the `Entry` carries a neutral id, and the novel side already mints negative synthetic `Long` ids for its color cache, so there is precedent); the download-state shape differs (novels derive a downloaded-id set from the disk cache, manga carry per-item `DownloadManager` status), which is resolved together with the download-subsystem unification (Road B); the merged-display field is named differently per side; filter granularity differs (one `Long`-flag call for novels, three TriState calls for manga); and the cross-source "same chapter" predicate differs (novel `>= 0.0` vs manga `isRecognizedNumber`).

**Dead-file policy** (the change to the sync workflow):

- A pure-UI Mihon file that is fully replaced by a shared component is **deleted**, not kept inert. The three-way diff base still lives in the `refs/mihon` clone, so a retained dead copy buys nothing but a misedit target. This supersedes the "copy, never move, mark `// RK: inert`" rule for pure-UI files.
- An engine file (a ScreenModel, repository, or the source manager) stays **live** and minimally patched, adapted at the seam. These are the real sync surface and are never taken off the render path.
- One tracked **off-path manifest** lists every Mihon path deleted or rerouted plus its replacement, and a sync-time script diffs those paths across each upstream's sync range and fails loudly if one changed. This is the enforcement the current process lacks, and it generalizes to two upstreams (Mihon plus tsundoku, once the reader migrates).

**Sequencing** is one surface at a time, fully cut over and verified on-device (debug and minified) before the next: details first (the keystone adapter, and the cleanest), then library, then browse, then migrate and global search. Each surface extracts the shared behavior first, then folds the novel ScreenModel onto it, then deletes the replaced pure-UI Mihon files and updates the manifest. Extracting the shared behavior before dissolving the novel model is what keeps each surface incremental and never gambles the whole surface on one big adapter.

**The reader is the final phase, folded into this program** rather than a separate branch. Reikai adopts tsundoku's native reader ([novel-reader-tsundoku.md](novel-reader-tsundoku.md) Option 3): novels move off the WebView plus vendored `core.js` onto tsundoku's native text reader inside Mihon's `ReaderActivity`, joining the manga reader and becoming syncable from tsundoku as a second upstream. It is the largest and riskiest piece, so it is sequenced last and kicked off with its own migration scout. The shared reader chrome ([unified-reader.md](unified-reader.md)) is the interim state; tsundoku is the endgame. The manga image viewers still stay Mihon and are not decoupled.

## Key files

Nothing is built yet, so these are the target homes and the current twins to collapse.

- Target seam: `reikai/presentation/details/` already holds the shared Entry* UI (`EntryDetailsScaffold`, `EntryInfoBox`, `EntryToolbar`, `EntryActionRow`, `entryInfoItems`) and is where the shared behavior interface (`Entry`, the capability slots) and the two adapters (`MangaEntryAdapter`, `NovelEntryAdapter`) land.
- Details twins to collapse: `MangaScreenModel` (stays live, adapted) and `NovelDetailsScreenModel` (dissolves).
- Later surfaces: the library (`LibraryScreenModel` plus the novel library model), browse (`BrowseSourceScreenModel` plus `NovelBrowseScreenModel`), and migration / global search (the `mihon.feature.migration` screens plus their `NovelMigration*` / `NovelGlobalSearch*` twins).
- Sync enforcement: the off-path manifest and its sync-time check live alongside the ledger in [upstream-sync.md](../upstream-sync.md), which also carries the superseded inert-file rule to update.
- Reader phase: `ReaderActivity` (the manga host novels join), the current novel reader under `reikai/presentation/novel/reader/`, and the tsundoku reference in `refs/tsundoku`.

## Status

Designed 2026-07-18, not started. Grounded by an integration-pattern analysis of the Reikai-versus-Mihon duplication and a feasibility comparison of the two details ScreenModels (~70-75% congruent, with a small bounded set of per-type capabilities). This is a deliberate refactor sprint, explicitly exempted from the no-standalone-refactor rule by the project owner because the current per-type duplication is not sustainable for a solo developer. First surface: details.

## Decisions & tradeoffs

- **Push the seam to behavior, not just UI.** The shipped Entry* work shared the UI leaves but left the ScreenModels forked, which is the bulk of the duplication. The content layer owns the behavior too.
- **Adapter over the live `MangaScreenModel`; the novel ScreenModel dissolves.** The hard rule is to never reimplement Mihon's core spine (read / download / filter / sort / selection) in the shared layer. Doing so is the maximal-dedup option but it forks Mihon's manga behavior and turns every upstream change into a hand-port forever, a permanent sync tax a solo developer cannot carry. So the manga model stays live and syncing, and only the seam adapter is Reikai code, compile-caught on drift.
- **Divergent bits are typed capability slots**, never nullable fields or per-type forks.
- **Replaced pure-UI Mihon files are deleted, not kept inert.** A solo+AI workflow cannot afford dead-file misedit traps; the `refs/mihon` clone holds the diff base, and the off-path manifest plus sync script make a buried upstream change impossible. Engine files stay live. This supersedes the keep-inert rule for pure-UI files.
- **The reader unifies via tsundoku, folded into this program but sequenced last** on its own scout, with tsundoku accepted as a second upstream and a View-based novel reader accepted in exchange for native rendering and a maintained upstream to sync from.
- **Scanlator filtering is a content-type-gated capability**, manga-only at first and generalizable to novels later; it is deliberately not taken as a tsundoku port, since tsundoku only gets it through novels-as-manga-rows.
- **Migration is incremental and verified per surface**, details first as the keystone. Each surface is independently shippable and on-device verified before the next.
