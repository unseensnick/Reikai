# Reikai Roadmap

Forward-looking plan for the Mihon rebase (branch `design/mihon-rebase`, which becomes `main` when the rebase ships). This is the single backlog: what is left, in what order, and what already landed.

Per-feature implementation and decision records live in [docs/dev/plans/](docs/dev/plans/). The format this file follows is defined in [.claude/rules/workflow.md](.claude/rules/workflow.md) ("Roadmap & plans"). Day-to-day session state lives in `Handoff.md` (gitignored).

## Status

| Phase / area | What | Status |
|---|---|---|
| P0-P1 | Mihon base + Reikai identity + source-api related-manga contract | done |
| P2 | Library screen carry: single-list + hopper, dynamic grouping, filter/sort, category sort order, update-errors | done |
| P3 | Manga details parity: merge / Manage-sources, private tracking, range select, cover-accent backdrop, recs carousel | done |
| P4 | Pref-based merge engine + preferred-source ranking + tracker-link mirroring + FlareSolverr | done |
| P5 | Light-novel vertical: domain/DB, QuickJS host, browse, details, reader, downloads, library, updates, merge, grouping, tracking, backup | done |
| P6 | Recommendations: engine, taste profile, tracker recs, See-all browse | done |
| P7 | Reader tweaks: configurable bottom bar, chapters sheet, cover tint, mark-read-on-skip, resume/preload | done |
| P8 | Settings / shell carry | dropped (Mihon covers it; the tabbed shell shipped under P9) |
| P9 | Category bulk-delete + unified Recents (folded into Updates + History) | done |
| Release | Signed release pipeline (AGP signing, preview/release workflows, in-app updater) | built; first-run verify pending (user) |
| Round 2 | Manga↔novel parity backlog + revived adult-source subsystem | mostly shipped; remainder below |

The rebase is functionally complete: the core sequence and the bulk of the manga↔novel parity backlog have shipped and are on-device verified. What remains is the **Next / Later** work below.

## Now

Nothing actively in progress.

## Next

Queued, roughly in priority order.

- **Release pipeline first-run verify**  `[S]`: create the `PREVIEW_REPO_TOKEN` fine-grained PAT (Contents: write on `unseensnick/Reikai-preview`) as a secret on `unseensnick/Reikai`, then confirm a push publishes a preview, a tag draft-publishes a release, and the in-app updater prompts on both. User action; the workflows are built.

## Later

Backlog, unordered.

- **Adult-source / EXH subsystem**  `[L]`: the full adult-source subsystem (E-Hentai / nHentai inverted branches, `exh.source` delegated sources, gallery metadata, search), deferred at P2. The basic lewd filter already shipped; this is the rest. Largest remaining item and multi-stage; run `/scout` before starting.

## Parked / not building

- **Dedicated LN trackers** (NovelUpdates / MiraiList / Novel Trackr / RanobeDB / Hardcover): not viable as of June 2026 (no sanctioned read+write API for on-device use). Re-check Hardcover only if it leaves beta with OAuth + allowlisting. See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel**: gated on novel trackers (mainstream trackers track LNs unreliably) and LN sources expose no related-title metadata; not worth building unless novel tracking proves out.
- **Upcoming / release calendar for novels**: LN sources rarely expose a reliable release cadence, so the feed would be mostly empty; the calendar stays manga-only.
- **Source filtering for novels** (dropped 2026-06-21): per-source browse filters and settings already exist; only a sources-list language filter was missing, low value with so few LN sources.
- **Saved searches** (browse filter presets): not Reikai-distinctive, and a full Compose rebuild for low value. The DB + serializer layer survives on `design/library-compose` if revived.
- **Restore-path onboarding**: the restore log already lists the extensions / plugins that couldn't be reinstalled, so a guided post-restore walkthrough is low value over the passive lines. Revive if restore confusion comes up. See [novel-backup.md](docs/dev/plans/novel-backup.md).
- **Auto-refresh-metadata toggle for novels** (manga `autoUpdateMetadata`): no-op for novels. The source parse returns metadata and chapters in one call (so a toggle saves no request, unlike manga's separate details fetch), and `mergeRefreshedNovel` already respects the edit-lock. Novels always refresh metadata during updates for free.
- **Dynamic launcher shortcuts** (`MangaShortcutManager`): a rewire against Mihon's data layer for a cosmetic nicety. Mihon ships only a static `shortcuts.xml`.
- **Y13** force side-nav rail, **Y17** DOKI theme, **Y18** in-app app-icon changer (dropped; Y18 revivable once Reikai-branded icon assets exist).
- **Y4** drag-sort, **Y5** staggered grid, **Y8** (duplicate of R16), **Y19** stats drill-down: out of scope.
- **EPUB export**: out of plan.

## Shipped

Terse done-log, grouped by area. Full detail in the linked plan docs.

### Foundation, identity & release
- Mihon base + Reikai identity (`eu.kanade.tachiyomi` + `.y2k`), source-api related-manga contract (P0-P1). See [rebase-overview.md](docs/dev/plans/rebase-overview.md).
- De-Mihon brand pass: Reikai logo, trimmed About links, donation/Support removed, trackers rebranded; repo meta + JDK 21. Icon design sources in `art/icon/`.
- README header logo + animated showcase WebP + reproduction kit (`docs/dev/readme-showcase.md`).
- Signed release pipeline: AGP-native signing, `release.yml` / `preview.yml`, in-app updater re-pointed at Reikai repos (`88ce9e674`, `dc695cefb`, `21bf270de`, `049645a41`).
- Minified-build startup-crash fix: the net-new `reikai.*` package added to the proguard keep list.
- Tier 0 duplication cleanup, 3 tiers, no behavior change (`6c27c5923`, `85ff3326d`, `f783979b5`).
- Mihon upstream syncs, caught up to `refs/mihon` `5f80403c5` (running ledger in the `upstream-sync` memory).

### Manga
- Library screen carry: single-list + hopper, dynamic grouping, filter/sort, category sort order, opt-in update-errors screen (P2). See [library-screen-carry.md](docs/dev/plans/library-screen-carry.md).
- Manga details parity: cover-accent backdrop, two-finger range select, swipe-to-refresh, unified Display sheet (P3). See [manga-details-parity.md](docs/dev/plans/manga-details-parity.md).
- Pref-based multi-source merge engine + preferred-source ranking + tracker-link mirroring + FlareSolverr (P4). See [manga-merge-engine.md](docs/dev/plans/manga-merge-engine.md).
- Recommendations / related carousel: five streams, taste rerank, tracker cross-recs, See-all browse (P6). See [recommendations.md](docs/dev/plans/recommendations.md).
- Reader tweaks: configurable bottom bar, chapters sheet, cover tint, mark-read-on-skip, resume/preload (P7).
- Category bulk-delete with deferred-undo (`5173d08b4`).

### Library shell (manga + novels)
- Tabbed shell hosting a Manga tab and a Novels tab, plus the repo / install / browse unification (P8/P9). See [library-tabbed-shell.md](docs/dev/plans/library-tabbed-shell.md).

### Light novels (P5 vertical)
- Headless QuickJS plugin host for background-capable novel sources. See [novel-plugin-host.md](docs/dev/plans/novel-plugin-host.md) and the handbook [ln-plugin-host.md](docs/dev/ln-plugin-host.md).
- Browse + global search + installable LN plugins. See [novel-browse.md](docs/dev/plans/novel-browse.md).
- Details screen at manga parity. See [novel-details.md](docs/dev/plans/novel-details.md).
- Reader (WebView text canvas + Compose chrome). See [novel-reader.md](docs/dev/plans/novel-reader.md).
- Reader engine extras round 2: read-aloud (TTS) with background playback + lock-screen media notification, bionic reading, remove-extra-spacing, tap-edges-to-scroll, swipe-between-chapters, auto-scroll, and a vertical progress seekbar; settings reorganized to General / Display / TTS tabs (`8a782ef55`, `b0134fe0e`, `e8453f0f0`, `0c918713d`). The remaining `core.js` extras stay off by design (volume-button paging, paged reading, battery/time + reading-% overlays, custom CSS/JS/themes, in-reader chapter drawer); rationale in [novel-reader.md](docs/dev/plans/novel-reader.md).
- Categories + hopper + tab-aware Display sheet. See [novel-categories.md](docs/dev/plans/novel-categories.md).
- Background update job (`NovelUpdateJob`). See [novel-update-job.md](docs/dev/plans/novel-update-job.md).
- Home-screen widget for manga + novel updates: a sectioned Glance widget added alongside Mihon's manga-only one. See [unified-updates.md](docs/dev/plans/unified-updates.md).
- Cross-source merge + dynamic grouping. See [novel-merge.md](docs/dev/plans/novel-merge.md).
- Tracking on AniList / MyAnimeList / MangaUpdates / Kitsu, group-aware (`5e8f53aca`). See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- Backup proto + installed-sources backup (`a6027edf2`). See [novel-backup.md](docs/dev/plans/novel-backup.md).

### Novel parity backlog
- History tab, source migration, per-novel notes, novels in Stats, reader orientation lock, keep-screen-on, incognito, downloaded-only mode, mark-read-on-skip, download retry + Wi-Fi-only, global-search long-press add, collapse-at-bottom grouping, category filter, bulk-download dropdown, working Last-read sort, source pinning + global-search chips. Per-item commit SHAs in [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Surgical novel writes: favorite / cover / chapter-flag / orientation changes route through `UpdateNovel` / `SetNovelChapterFlags` / `SetNovelViewerFlags` interactors (the novel twins of Mihon's), writing one column instead of the whole row. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Download-queue reorder + sort: drag-to-reorder the flat novel queue (persisted to `NovelDownloadStore`, survives restart) plus a Sort menu (upload date / chapter number); the unified queue's Sort hits manga and novels together. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Batch / library migration: one unified migration screen for 1..N novels (single from a novel's overflow, batch from library multi-select). Each row auto-searches on scroll and suggests a target to accept or override; lazy-materialize on pick, then Copy / Migrate with flags. Replaces the old single-only migrate UI. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Download settings parity: keep-last-N-read (delete-after-read slots), don't-delete-bookmarked, exclude-categories-from-delete, and download-ahead, all under Settings → Downloads. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Per-title novel update notifications: one grouped notification per updated novel, deep-linking into the novel via a new `SHORTCUT_NOVEL` intent. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).

### Unified surfaces
- Unified Updates tab: manga + novel interleaved, filters, by-category, group-by-series. See [unified-updates.md](docs/dev/plans/unified-updates.md).
- Unified reader: shared Compose chrome over the View-based manga reader (Option F direction); Phase 1 shipped. See [unified-reader.md](docs/dev/plans/unified-reader.md).
- History tab consolidated (manga + novel behind a content chip).

## Conventions

Branch `design/mihon-rebase`; edits to Mihon files fenced with `// RK -->` / `// RK <--`; net-new code in `reikai.*`; Injekt DI (no Koin); immutable `tachiyomi.domain` models; SQLDelight migrations are additive. Upstream Mihon changes are ported by hand from `refs/mihon/` (never `git merge`). Full detail in [CLAUDE.md](CLAUDE.md), [.claude/rules/](.claude/rules/), the [plan docs](docs/dev/plans/), and the memories.
