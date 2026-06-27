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
| Round 2 | Manga↔novel parity backlog + revived adult-source subsystem | shipped (last standalone item parked) |

The rebase is functionally complete: the core sequence and the manga↔novel parity backlog have shipped and are on-device verified. The parity & adult-system audit has run (report `PARITY-EXH-AUDIT.md`, local / gitignored); its EXH findings are filed into **Next** (two small fixes), **Later** (backlog parity), and **Parked** (two skips), with the manga↔novel catalog triage in progress.

## Now

Nothing actively in progress (the audit triage is filing work into Next / Later below).

## Next

Queued, roughly in priority order.

- **Exclude adult galleries from library updates** `[S]`: EH / ExH / Pururin / nHentai galleries default to `ALWAYS_UPDATE`, so every library refresh re-fetches each saved gallery (E-Hentai rate-limit / ban risk); their real updates are already handled by the dedicated `EHentaiUpdateWorker`. Port Komikku's `LIBRARY_UPDATE_EXCLUDED_SOURCES` + a `// RK` `filterNot` in `LibraryUpdateJob.addMangaToQueue`. The static EH / ExH / Pururin ids are the easy win (about ten lines, one file pair); Komikku also appends the dynamic nHentai ids in `handleSourceLibrary`, so full nHentai coverage is coupled to the "Dynamic delegated-source id lists" item below (do that first, or ship the static three now and add nHentai later). Highest-value finding from the adult-system audit.
- **Suppress the stock E-Hentai extension when built-in EH is on** `[S]`: a user who installs the stock E-Hentai extension alongside our built-in EH sources sees duplicate sources. Port Komikku's `BlacklistedSources` (`BLACKLISTED_EXTENSIONS` + `BLACKLISTED_EXT_SOURCES`) so the stock extension is hidden while built-in EH is active. From the adult-system audit.
- **Publish a fresh preview + release-pipeline first-run verify**  `[S]`: the preview prune bug is now FIXED (`f2a20eb67`, prune by build number not date), so kick a manual build, `gh workflow run preview.yml --ref design/mihon-rebase --repo unseensnick/Reikai`, and confirm r352 lands on `unseensnick/Reikai-preview` and sticks (the prune no longer eats the newest). Then the broader verify: a tag draft-publishes a release and the in-app updater prompts on both. The `PREVIEW_REPO_TOKEN` PAT already works; builds publish; this is now just the manual trigger + check.

## Later

Backlog, unordered. The manga↔novel parity backlog (audit catalog A) is added here as it is triaged.

### Adult / EXH parity (from the 2026-06-27 audit)
- **EXH library search engine** `[M]`: namespace / wildcard / exclude / exact / alias search over the library (`exh/search`: `SearchEngine`, `Namespace`, wildcards, `QueryComponent`). The browse-side `namespace:tag` autocomplete is already ported; this is the library-search half.
- **Rich EXH metadata rendering** `[M]`: port `SourceTagsUtil` plus the three surfaces that depend on it: namespaced tappable tag chips on manga details, a per-source gallery info block above the description, and the EH-specific browse list row (language flag / rating / page count). Presentation only; the `*DescriptionAdapter` composables are live in Komikku, not dead.
- **EXH gallery import entry points** `[S]`: `InterceptActivity` (share or open a gallery link to import it) and `BatchAddScreen` (paste many gallery URLs to bulk-import); both drive the already-ported `GalleryAdder`.
- **Dynamic delegated-source id lists** `[M]`: port `currentDelegatedSources` / `handleSourceLibrary`, which compute the nHentai / metadata / lanraragi id sets; speeds `isLewd` and unlocks richer nHentai handling.
- **Fuller GalleryAdder add path** `[S]`: chapter fetch / sync, the retry + NotFound loop, and `pickSource` filtering by enabled languages / disabled sources (our add path is currently trimmed).
- **EXH gallery-update progress notification** `[S]`: richer content / styling to match Komikku's update-checker notification.

### Manga ↔ novel parity (from the 2026-06-27 audit)

Ready to build, the infrastructure already exists (good candidates to promote to **Next**):
- **Duplicate detection when adding a novel** `[S]`: wire the existing `getDuplicateLibraryNovel` + `DuplicateNovelDialog` into the details and history add-to-library paths; manga checks for a duplicate, novel adds blind.
- **Novel migration carry-flags** `[S]`: add a remove-downloads flag and carry the per-novel reader / chapter flags (`viewerFlags` / `chapterFlags`) to the target, completing the migration redesign.
- **Categorized-display correctness for novels** `[S]`: novel per-category sort ignores the global `categorized_display` toggle and never resets on toggle-off; branch `setSort` and add a novel `ResetCategoryFlags` (a real correctness gap, not just polish).
- **Expose novel tracking in library filter / sort / group** `[S]`: tracker-status filter, tracker-score sort, and group-by-track-status, all stale-descoped before novel tracking shipped.
- **Mark same-numbered duplicate chapters read on novel completion** `[S]`: parity for merged novels (manga's `markDuplicateReadChapterAsRead`).
- **Failed novel download error notification** `[S]`: a failed novel chapter download is silent today; mirror `DownloadNotifier.onError` in `NovelDownloadNotifier`.
- **Novel updates refresh polish** `[S]`: a started / already-running snackbar on manual novel refresh, and make the update-row cover open novel details.
- **Tracker-based merge-group healing for novels** `[S-M]`: port `computeHealing` to `NovelMergeManager` to auto-separate mistaken same-title merges, now that novel tracking provides the keys.

Larger initiatives:
- **Global novel reader-defaults settings screen** `[M]`: novels expose typography / theme / gestures only inside the per-novel reader sheet; add a `SearchableSettings` novel-reader page the sheet falls back to, and localize its hardcoded labels (which also blocks settings search). The single biggest catalog-A gap and the audit's only high-severity parity item.
- **Novel library Behaviour settings** `[M]`: novels have no Behaviour surface; add swipe actions (bookmark / mark-read / download) and missing-chapter indicators for the novel library list.

Low-value polish, do opportunistically:
- Browse: source-row Latest shortcut, global-search progress indicator, Last-used section, hide-in-library toggle, per-row language sub-label, genre-tap-to-search.
- Reader: Share + open-in-browser actions, always-on progress percent.
- Downloads queue: pause / resume, per-row retry, move-to-top / bottom, per-series move / cancel.
- Tracking: start-date backfill on bind, create-private-at-bind-time, hide trackers lacking a real novel search.
- Updates / history: last-updated line on the Novels chip, fast-scroll row animation (also a manga regression on the unified history screen).
- Details: long-press-copy the WebView URL, per-source scanlator filter for merged novels; per-category novel display settings `[M]`.

## Parked / not building

- **Manga per-page chapter loading** (parked 2026-06-27): give manga the paged chapter list novels already have (a "Page n / N" bar + `NovelPageSelectorSheet`, fetched lazily per page). Parked because no manga source would feed it. Novel paging is driven by the source plugin: an lnreader plugin can return chapters page-by-page and exposes its own opt-in toggle (e.g. NovelFire's "Page Mode", default off, which sets `totalPages > 1`); Reikai's details screen just reacts to that. Manga's source contract (`getChapterList` returning the full `List<SChapter>` in one call) is fixed and shared byte-for-byte with Mihon, so manga extensions never paginate and there is no toggle to add. The feature would page a list the source already returns complete, buying nothing, and page-scoping Mihon's manga path (sort, filter, mark-all-read, download-all, next-chapter, tracker sync) is real `[M]` work plus `// RK` patch surface for paper parity.

- **Dedicated LN trackers** (NovelUpdates / MiraiList / Novel Trackr / RanobeDB / Hardcover): not viable as of June 2026 (no sanctioned read+write API for on-device use). Re-check Hardcover only if it leaves beta with OAuth + allowlisting. See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel** (revisit after the 2026-06-27 audit): originally gated on novel trackers, which have since shipped, so the tracker-recs + taste-rerank path is now feasible (drive `RecommendationsFetcher` off `GetNovelTracks`; novels carry the same `trackerId` / `remoteId`). The source-native related path stays infeasible (the LN plugin contract has no `getRelatedMangaList` equivalent). A `[M]` build worth reconsidering if novel recs are wanted; was parked only because trackers were missing.
- **Upcoming / release calendar for novels**: LN sources rarely expose a reliable release cadence, so the feed would be mostly empty; the calendar stays manga-only.
- **Source filtering for novels** (dropped 2026-06-21): per-source browse filters and settings already exist; only a sources-list language filter was missing, low value with so few LN sources.
- **Novel sources enable/disable filter screen**: novels can disable a source (it dims in the Sources list and drops out of global search, re-enable by long-press), but unlike manga there is no dedicated sources-filter screen to bulk-toggle enable/disable. Add one (reached from the Sources filter icon on the Novels chip) if managing many LN sources gets painful.
- **Novel missing-chapter gap separators** (dropped 2026-06-26): manga computes gaps from a reliable sequential chapter number; novel chapter numbers are source-order-indexed and title-recognition-derived (LNReader plugins often supply none, so the number is recognized from the chapter title), and the default novel chapter sort is source order, not number, so computed gaps would be mostly false. The hardcoded `missingChapterCount = 0` in `NovelScreen` is correct.
- **Saved searches** (browse filter presets): not Reikai-distinctive, and a full Compose rebuild for low value. The DB + serializer layer survives on `design/library-compose` if revived.
- **Restore-path onboarding**: the restore log already lists the extensions / plugins that couldn't be reinstalled, so a guided post-restore walkthrough is low value over the passive lines. Revive if restore confusion comes up. See [novel-backup.md](docs/dev/plans/novel-backup.md).
- **Auto-refresh-metadata toggle for novels** (manga `autoUpdateMetadata`): no-op for novels. The source parse returns metadata and chapters in one call (so a toggle saves no request, unlike manga's separate details fetch), and `mergeRefreshedNovel` already respects the edit-lock. Novels always refresh metadata during updates for free.
- **Dynamic launcher shortcuts** (`MangaShortcutManager`): a rewire against Mihon's data layer for a cosmetic nicety. Mihon ships only a static `shortcuts.xml`.
- **Y13** force side-nav rail, **Y17** DOKI theme, **Y18** in-app app-icon changer (dropped; Y18 revivable once Reikai-branded icon assets exist).
- **Y4** drag-sort, **Y5** staggered grid, **Y8** (duplicate of R16), **Y19** stats drill-down: out of scope.
- **EPUB export**: out of plan.
- **Full two-way E-Hentai favorites sync** (pull account -> library): the scoped one-way backup (push add + opt-in remote remove) shipped instead (see Shipped → Phase 5b). The full `FavoritesSyncHelper` (download the account's favorites and add/remove library entries to mirror them, with an `eh_favorites` snapshot table, conflict handling, and library-screen patches) was deliberately not built: it is the only EXH feature that would mutate the library from a remote source. Revive only if account -> library mirroring is wanted.
- **EXHMigrations backup source-ID remapper** (investigated 2026-06-27, not needed): Komikku remaps old stock-extension source ids on backup restore. Not needed for Reikai: EH / ExH already resolve because `AndroidSourceManager` registers a built-in `EHentai` for every stock-extension language id in `EHENTAI_EXT_SOURCES` / `EXHENTAI_EXT_SOURCES` (about 36 ids, more complete than Komikku's two-id remap); Tsumino / HBrowse are not shipped; the nHentai old-id churn predates any Reikai-relevant backup (the `.yokai` -> `.y2k` path and current Mihon imports both carry current ids); and we have no SavedSearch / Feed restorers (two of Komikku's four call sites). Worth a one-line comment by `EHENTAI_EXT_SOURCES` so it is not re-discovered as a gap.
- **Per-source DataSaver image compression** (`exh/util/DataSaver.kt`): a general Komikku image-proxy feature (BANDWIDTH_HERO / wsrv.nl backends rewriting page-image URLs), not adult-specific; out of scope on the Mihon base.
- **Manga surfaces confirmed not-applicable to novels** (audit 2026-06-27, recorded so they are not re-flagged as gaps): local badge / local titles stat, lewd filter, per-entry fetch-interval (filter, edit control, release-period restriction, upcoming calendar), E-Ink page-flash, save-as-CBZ / split-tall-images, EnhancedTracker one-tap / auto-bind, excluded-scanlators + adult-search-metadata backup, and the per-chapter `memo` / `lastModifiedAt` / `version` backup columns novels don't have. All inapplicable to text content or already parity-equal; the backup and stats areas came back fully intentional / NA.
- **Adult / EXH enhanced sources — Hitomi.la, 3Hentai, Luscious, HentaiNexus** (part of the adult / EXH subsystem): **Hitomi.la** and **3Hentai** have no stock Keiyoushi extension, so enhancing them means writing or sourcing the base extension first, a larger lift. **Luscious** (GraphQL; tags come back as flat text and the per-tag category is fetched then discarded by the extension's DTO) and **HentaiNexus** (single-language, detail tags collapse into one flat genre, pages are encrypted) expose too little structured metadata to justify a wrapper. Revisit individually; no date set.

## Shipped

Terse done-log, grouped by area. Full detail in the linked plan docs.

### Foundation, identity & release
- Mihon base + Reikai identity (`eu.kanade.tachiyomi` + `.y2k`), source-api related-manga contract (P0-P1). See [rebase-overview.md](docs/dev/plans/rebase-overview.md).
- De-Mihon brand pass: Reikai logo, trimmed About links, donation/Support removed, trackers rebranded; repo meta + JDK 21. Icon design sources in `art/icon/`.
- README header logo + animated showcase WebP + reproduction kit (`docs/dev/readme-showcase.md`).
- Signed release pipeline: AGP-native signing, `release.yml` / `preview.yml`, in-app updater re-pointed at Reikai repos (`88ce9e674`, `dc695cefb`, `21bf270de`, `049645a41`).
- Preview pipeline prune fix: previews had stopped publishing (stuck at r295) because the prune sorted by `createdAt` (all identical after a history-rewrite) and deleted the just-published release; now prunes by build number (`f2a20eb67`). Old Yōkai-era `r6xxx` nightlies on the main repo were also cleaned up.
- Commit-standard enforcement: a `commit-msg` git hook (`.githooks/commit-msg`) rejects non-compliant messages; the standard now allows explicit `owner/repo#N` refs (`2801b2ae1`).
- Minified-build startup-crash fix: the net-new `reikai.*` package added to the proguard keep list.
- Tier 0 duplication cleanup, 3 tiers, no behavior change (`6c27c5923`, `85ff3326d`, `f783979b5`).
- Mihon upstream syncs, caught up to `refs/mihon` `a82ccea6f` (incl. Voyager 1.x->2.x, Gradle 9.6.1; running ledger in the `upstream-sync` memory).

### Manga
- Library screen carry: single-list + hopper, dynamic grouping, filter/sort, category sort order, opt-in update-errors screen (P2). See [library-screen-carry.md](docs/dev/plans/library-screen-carry.md).
- Manga details parity: cover-accent backdrop, two-finger range select, swipe-to-refresh, unified Display sheet (P3). See [manga-details-parity.md](docs/dev/plans/manga-details-parity.md).
- Pref-based multi-source merge engine + preferred-source ranking + tracker-link mirroring + FlareSolverr (P4). See [manga-merge-engine.md](docs/dev/plans/manga-merge-engine.md).
- Recommendations / related carousel: five streams, taste rerank, tracker cross-recs, See-all browse (P6). See [recommendations.md](docs/dev/plans/recommendations.md).
- Reader tweaks: configurable bottom bar, chapters sheet, cover tint, mark-read-on-skip, resume/preload (P7).
- Merge-aware reader: read a merged manga through all its sources (unified list + per-source labels, cross-source prev/next, per-source side effects) via a thin Reikai layer over Mihon's reader (`4c811bae7`, `977ddf8a0`, `3ba35b67d`). See [merge-aware-manga-reader.md](docs/dev/plans/merge-aware-manga-reader.md).
- Category bulk-delete with deferred-undo (`5173d08b4`).

### Library shell (manga + novels)
- Tabbed shell hosting a Manga tab and a Novels tab, plus the repo / install / browse unification (P8/P9). See [library-tabbed-shell.md](docs/dev/plans/library-tabbed-shell.md).

### Light novels (P5 vertical)
- Headless QuickJS plugin host for background-capable novel sources. See [novel-plugin-host.md](docs/dev/plans/novel-plugin-host.md) and the handbook [ln-plugin-host.md](docs/dev/ln-plugin-host.md).
- Browse + global search + installable LN plugins. See [novel-browse.md](docs/dev/plans/novel-browse.md).
- Details screen at manga parity. See [novel-details.md](docs/dev/plans/novel-details.md).
- Reader (WebView text canvas + Compose chrome). See [novel-reader.md](docs/dev/plans/novel-reader.md).
- Reader engine extras round 2: read-aloud (TTS) with background playback + lock-screen media notification, bionic reading, remove-extra-spacing, tap-edges-to-scroll, swipe-between-chapters, auto-scroll, and a vertical progress seekbar; settings reorganized to General / Display / TTS tabs (`8a782ef55`, `b0134fe0e`, `e8453f0f0`, `0c918713d`). The remaining `core.js` extras stay off by design (volume-button paging, paged reading, battery/time + reading-% overlays, custom CSS/JS/themes, in-reader chapter drawer); rationale in [novel-reader.md](docs/dev/plans/novel-reader.md).
- Reader chrome + chapter-list parity (round 3): a jump-to-chapter sheet reusing manga's `MangaChapterListItem` (read dot, date, bookmark, download button; merged novels show per-source labels in the unified order), a manga-style orientation picker, top-bar bookmark + WebView, seekbar percent labels, even bottom-bar spacing, translucent chrome. Plus a fix so WebView / Share open the novel's page, not the source homepage (`bc3d21ed2`, `762704ec1`). See [novel-reader.md](docs/dev/plans/novel-reader.md).
- Categories + hopper + tab-aware Display sheet. See [novel-categories.md](docs/dev/plans/novel-categories.md).
- Background update job (`NovelUpdateJob`). See [novel-update-job.md](docs/dev/plans/novel-update-job.md).
- Home-screen widget for manga + novel updates: a sectioned Glance widget added alongside Mihon's manga-only one (`3b1f11a3e`). See [unified-updates.md](docs/dev/plans/unified-updates.md).
- Cross-source merge + dynamic grouping. See [novel-merge.md](docs/dev/plans/novel-merge.md).
- Tracking on AniList / MyAnimeList / MangaUpdates / Kitsu, group-aware (`5e8f53aca`). See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- Backup proto + installed-sources backup (`a6027edf2`). See [novel-backup.md](docs/dev/plans/novel-backup.md).

### Novel parity backlog
- History tab, source migration, per-novel notes, novels in Stats, reader orientation lock, keep-screen-on, incognito, downloaded-only mode, mark-read-on-skip, download retry + Wi-Fi-only, global-search long-press add, collapse-at-bottom grouping, category filter, bulk-download dropdown, working Last-read sort, source pinning + global-search chips. Per-item commit SHAs in [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Surgical novel writes: favorite / cover / chapter-flag / orientation changes route through `UpdateNovel` / `SetNovelChapterFlags` / `SetNovelViewerFlags` interactors (the novel twins of Mihon's), writing one column instead of the whole row. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Download-queue reorder + sort: drag-to-reorder the flat novel queue (persisted to `NovelDownloadStore`, survives restart) plus a Sort menu (upload date / chapter number); the unified queue's Sort hits manga and novels together. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Batch / library migration: one unified migration screen for 1..N novels (single from a novel's overflow, batch from library multi-select). Each row auto-searches on scroll and suggests a target to accept or override; lazy-materialize on pick, then Copy / Migrate with flags. Replaces the old single-only migrate UI. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Migration redesign: covers + a chapter-count regression signal, a source-selection pre-step (Selected / Available, reorderable priority, `novelMigrationSources` pref), a source-to-target comparison row with manga-style per-row actions (one-tap Accept + overflow menu), a browse-style cover-grid override picker, and Copy / Migrate as distinct bottom actions. See [novel-migration-redesign.md](docs/dev/plans/novel-migration-redesign.md) (`722db903a`, `ebe4684a7`, `e8a24d888`, `d202dfde4`, `15e947cb6`).
- Merge-aware migration (manga + novel): migrating a merged entry keeps the merge, the new source taking the old one's place (Migrate swaps it out, Copy adds alongside), for manual and same-title auto groups alike, via the shared `MergeGroupAlgebra`. Novel migration also carries tracker links now, matching manga. Manga edit fenced `// RK` (`b458ef910`).
- Migrate-merge source picker (manga + novel): a pre-step to choose which source(s) of a merged series to migrate (the rest stay put); auto-skipped when nothing in the selection is merged. New `NovelMigrationSourcePickScreen` / `MangaMigrationSourcePickScreen`; details + library entry points rerouted (`75ac19b85`).
- Download settings parity: keep-last-N-read (delete-after-read slots), don't-delete-bookmarked, exclude-categories-from-delete, and download-ahead, all under Settings → Downloads. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Per-title novel update notifications: one grouped notification per updated novel, deep-linking into the novel via a new `SHORTCUT_NOVEL` intent. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Novel tracking private listing: `private` column on `novel_tracks` (migration 25) threaded through `NovelTrack`, the mapper / insert, and the `DbTrack` conversion (plus backup proto), with `NovelTrackUpdater.setRemotePrivate` and `allowPrivate` so the shared Tracking sheet shows "Track privately" for capable trackers (Kitsu / AniList / Bangumi) (`9da2408c5`).
- Reader brightness + colour filter: novel reader Display settings gain custom brightness + a colour filter (R/G/B/opacity + blend mode), the manga reader's controls but novel-specific (own `NovelPreferences` keys + a separate overlay flow). Reuses the shared `ReaderContentOverlay` (mounted over the WebView, below the chrome, touch-transparent); positive brightness drives the window, negative dims via the overlay. Grayscale/invert skipped (low value for text). On-device verified (`f88c232e8`).
- Migration carries cover + notes: `NovelMigrationFlag` gains COVER + NOTES; `MigrateNovelUseCase` copies the custom-cover file (negated-id slot) + bumps `coverLastModified` and carries notes, gated by their flags and shown only when applicable across the batch (`Novel.hasCustomCover` helper, the novel twin of `MigrateMangaUseCase`). Target-source picker deliberately left out (`dc833202f`).
- Restore skip-if-newer: `novels` gains `version` + `is_syncing` columns and an `update_novel_version` trigger (migration 26, verbatim port of `mangas.sq`), plus a `BackupNovel` proto field, so restoring an older backup keeps a newer local novel instead of overwriting it (the novel twin of `MangaRestorer`'s version merge). Dropped `last_modified_at`: novel restore has no `sortByNew`, so nothing reads it (`25bdb0a07`).
- Round 2 parity sweep: novel default-category for new novels (`78332b1a8`), add-to-library button on novel history rows (`f940551f1`), novel source enable/disable (`41a480054`), novel chapter "downloaded" filter (`54ec4fb3e`), and novel update-error tracking + per-category manual update (shared All / Manga / Novels Update-errors screen, fixes the Novels-chip refresh that fired the manga job) (`385224377`).

### Adult / EXH subsystem (phases 1-4 + 5a + 5b)
Ported from `refs/komikku`, re-typed onto Mihon's models. Committed on `design/mihon-rebase`, not yet pushed; on-device verified on emulator-5554.
- Phase 1: delegation core (`EnhancedHttpSource` / `DelegatedHttpSource`) + gallery-metadata store (`search_metadata` / `search_tags` / `search_titles`, migration 23) + the 4 free enhanced sources (nHentai, Pururin, 8Muses, LANraragi) + URL import (`a105d5ab3`, `e6807a43f`, `10ef6caf7`).
- Phase 2: built-in E-Hentai / ExHentai source (anonymous browse + read, full gallery filters, gallery-version chapters); Settings → Advanced "Enable adult sources" toggle; ExHentai WebView login; E-Hentai settings screen + server-profile sync (uconfig) (`1a072568f`, `c8d939d2b`, `ab6325aae`, `9868c4ae1`, `bc288cff1`, `add58456a`).
- Phase 3: E-Hentai tag autocomplete (full EHTags catalogue), library search by gallery tags, Compose-native gallery metadata viewer (`04467c276`, `52348af35`, `b6bbc417a`).
- Phase 4: three net-new enhanced wrappers (HentaiFox, AsmHentai, Koharu/SchaleNetwork) that re-parse each site's gallery details into namespaced tags; plus a fix to match delegated sources by source name so R8-minified factory extensions wrap (also repairs nHentai/LANraragi). Scoped down from six (Luscious/HentaiNexus/3Hentai parked). On-device verified (`896c440cc`, `db45bc176`, `4aa67b83e` + the SchaleNetwork wrapper).
- Phase 5a: E-Hentai favorited-gallery update checker. A WorkManager job re-checks each favorited EH gallery for a newer version and reconciles the version chain locally (full disk-backed `EHentaiUpdateHelper` + `MemAutoFlushingLookupTable` replacing the in-session stub, merging chapters / read state / history / categories), with a "Gallery update checker" settings group. On-device verified: launch, settings render, scheduling; deep version-reconciliation is a faithful port not yet live-triggered.
- Phase 5b (scoped): E-Hentai favorites account backup (one-way push + opt-in remote remove), not the full two-way sync. Favoriting a gallery adds it to the account; deleting locally keeps it on the account unless you tick "Also remove from E-Hentai favorites" in a DeletableTracker-style confirm; a "Back up all favorites now" job pushes the existing library (throttled). New `EHentai.fetchFavorites`/`addFavorite`/`removeFavorites`, `EhFavoritesBackupJob`, a `// RK` island in `MangaScreenModel`/`MangaScreen` + the confirm dialog, and a "Favorites backup" settings group. Account push/remove needs an ExHentai login to verify live (user-side); compile + non-account paths verified.
- EXH settings + presentation polish: E-Hentai promoted to its own top-level Settings category (gated by the adult-sources pref, with a traced EH-favicon `EhAssets.EhLogo` icon) matching Komikku's placement (`398152600`); the built-in EH/ExH source rows show that logo instead of the blank placeholder (`bd1fa1c2f`); and the four missing EXH settings surfaced in `SettingsEhScreen`: Incognito (real fix via `EH_PACKAGE` + a `// RK` `GetIncognitoState` map), Language filtering, Front-page categories, and the updater-statistics dialog (`35501c13e`). On-device verified.

### Unified surfaces
- Unified Updates tab: manga + novel interleaved, filters, by-category, group-by-series. See [unified-updates.md](docs/dev/plans/unified-updates.md).
- Unified reader: shared Compose chrome over the View-based manga reader (Option F direction); Phase 1 shipped. See [unified-reader.md](docs/dev/plans/unified-reader.md).
- History tab consolidated (manga + novel behind a content chip).

## Conventions

Branch `design/mihon-rebase`; edits to Mihon files fenced with `// RK -->` / `// RK <--`; net-new code in `reikai.*`; Injekt DI (no Koin); immutable `tachiyomi.domain` models; SQLDelight migrations are additive. Upstream Mihon changes are ported by hand from `refs/mihon/` (never `git merge`). Full detail in [CLAUDE.md](CLAUDE.md), [.claude/rules/](.claude/rules/), the [plan docs](docs/dev/plans/), and the memories.
