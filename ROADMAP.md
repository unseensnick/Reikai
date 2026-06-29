# Reikai Roadmap

Forward-looking plan for Reikai on the Mihon base (the rebase has shipped; `main` is the Mihon-based main). This is the single backlog: what is left, in what order, and what already landed.

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

The rebase is functionally complete: the core sequence and the manga↔novel parity backlog have shipped and are on-device verified. The parity & adult-system audit has run and is fully triaged (report `PARITY-EXH-AUDIT.md`, local / gitignored): its findings are filed into **Next**, **Later** (the manga↔novel and adult/EXH parity backlogs), and **Parked**. Both audit work items already shipped (adult galleries excluded from library updates, on-device verified; stock E-Hentai extension suppressed, regression-verified).

## Now

Nothing actively in progress.

## Next

Nothing queued.

## Later

Backlog, unordered. The manga↔novel parity backlog (audit catalog A) is added here as it is triaged.

### Adult / EXH parity (from the 2026-06-27 audit)
Reikai ships a lighter slice of Komikku's adult-source subsystem; the items below, plus the parked two-way favorites sync, are the gap to Komikku parity. Candidate **post-release-cut initiative**: widen the EXH port toward Komikku parity so it is on par (while still Reikai's lighter-by-default flavor), since the current trims may read as a dealbreaker to adult-source users. The user-facing scope and the gaps are documented in [docs/adult-sources.md](docs/adult-sources.md) ("What's not here yet").

- **EXH library search engine** `[M]`: namespace / wildcard / exclude / exact / alias search over the library (`exh/search`: `SearchEngine`, `Namespace`, wildcards, `QueryComponent`). The browse-side `namespace:tag` autocomplete is already ported; this is the library-search half.
- **Rich EXH metadata rendering** `[M]`: port `SourceTagsUtil` plus the three surfaces that depend on it: namespaced tappable tag chips on manga details, a per-source gallery info block above the description, and the EH-specific browse list row (language flag / rating / page count). Presentation only; the `*DescriptionAdapter` composables are live in Komikku, not dead.
- **EXH gallery import entry points** `[S]`: `InterceptActivity` (share or open a gallery link to import it) and `BatchAddScreen` (paste many gallery URLs to bulk-import); both drive the already-ported `GalleryAdder`.
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
- **Metadata / lanraragi delegated-id lists + `isLewd` speedup** (parked 2026-06-27): the reusable `currentDelegatedSources` map (id -> delegate) already shipped with the library-update fix and drives the nHentai exclusion. The remaining piece, deriving the metadata / lanraragi id sets and rewiring `MangaLewd.isLewd` to an `isMetadataSource` check instead of its name + genre heuristic, is parked: `isLewd` is a deliberate name/genre heuristic that already recognizes the common adult sources by name, the id sets have no other consumer, and pointing the lewd filter at the EXH metadata path it intentionally avoids is a behavioral change with marginal benefit. Revive if a concrete consumer for the delegated metadata id sets appears.
- **Manga surfaces confirmed not-applicable to novels** (audit 2026-06-27, recorded so they are not re-flagged as gaps): local badge / local titles stat, lewd filter, per-entry fetch-interval (filter, edit control, release-period restriction, upcoming calendar), E-Ink page-flash, save-as-CBZ / split-tall-images, EnhancedTracker one-tap / auto-bind, excluded-scanlators + adult-search-metadata backup, and the per-chapter `memo` / `lastModifiedAt` / `version` backup columns novels don't have. All inapplicable to text content or already parity-equal; the backup and stats areas came back fully intentional / NA.
- **Adult / EXH enhanced sources — Hitomi.la, 3Hentai, Luscious, HentaiNexus** (part of the adult / EXH subsystem): **Hitomi.la** and **3Hentai** have no stock Keiyoushi extension, so enhancing them means writing or sourcing the base extension first, a larger lift. **Luscious** (GraphQL; tags come back as flat text and the per-tag category is fetched then discarded by the extension's DTO) and **HentaiNexus** (single-language, detail tags collapse into one flat genre, pages are encrypted) expose too little structured metadata to justify a wrapper. Revisit individually; no date set.

## Shipped

Terse done-log, grouped by area. Full detail in the linked plan docs.

### Foundation, identity & release
- Mihon base + Reikai identity (`eu.kanade.tachiyomi` + `.y2k`), source-api related-manga contract (P0-P1). See [rebase-overview.md](docs/dev/plans/rebase-overview.md).
- De-Mihon brand pass: Reikai logo, trimmed About links, donation/Support removed, trackers rebranded; repo meta + JDK 21. Icon design sources in `art/icon/`.
- README header logo + animated showcase WebP + reproduction kit (`docs/dev/readme-showcase.md`).
- Signed release pipeline: AGP-native signing, `release.yml` / `preview.yml`, in-app updater re-pointed at Reikai repos (`a4ee2c401`, `1f7aecac4`, `09d04cd0b`, `1c68490da`).
- Preview pipeline prune fix: previews had stopped publishing (stuck at r295) because the prune sorted by `createdAt` (all identical after a history-rewrite) and deleted the just-published release; now prunes by build number (`e1960e06e`). Old Yōkai-era `r6xxx` nightlies on the main repo were also cleaned up.
- Commit-standard enforcement: a `commit-msg` git hook (`.githooks/commit-msg`) rejects non-compliant messages; the standard now allows explicit `owner/repo#N` refs (`7f4649d65`).
- Minified-build startup-crash fix: the net-new `reikai.*` package added to the proguard keep list.
- Tier 0 duplication cleanup, 3 tiers, no behavior change (`6c27c5923`, `85ff3326d`, `f783979b5`).
- Mihon upstream syncs, caught up to `refs/mihon` `a82ccea6f` (incl. Voyager 1.x->2.x, Gradle 9.6.1; running ledger in the `upstream-sync` memory).

### Manga
- Library screen carry: single-list + hopper, dynamic grouping, filter/sort, category sort order, opt-in update-errors screen (P2). See [library-screen-carry.md](docs/dev/plans/library-screen-carry.md).
- Manga details parity: cover-accent backdrop, two-finger range select, swipe-to-refresh, unified Display sheet (P3). See [manga-details-parity.md](docs/dev/plans/manga-details-parity.md).
- Pref-based multi-source merge engine + preferred-source ranking + tracker-link mirroring + FlareSolverr (P4). See [manga-merge-engine.md](docs/dev/plans/manga-merge-engine.md).
- Recommendations / related carousel: five streams, taste rerank, tracker cross-recs, See-all browse (P6). See [recommendations.md](docs/dev/plans/recommendations.md).
- Reader tweaks: configurable bottom bar, chapters sheet, cover tint, mark-read-on-skip, resume/preload (P7).
- Merge-aware reader: read a merged manga through all its sources (unified list + per-source labels, cross-source prev/next, per-source side effects) via a thin Reikai layer over Mihon's reader (`d30cce03d`, `4bd3ca823`, `5b9f6d778`). See [merge-aware-manga-reader.md](docs/dev/plans/merge-aware-manga-reader.md).
- Category bulk-delete with deferred-undo (`9a320598e`).

### Library shell (manga + novels)
- Tabbed shell hosting a Manga tab and a Novels tab, plus the repo / install / browse unification (P8/P9). See [library-tabbed-shell.md](docs/dev/plans/library-tabbed-shell.md).

### Light novels (P5 vertical)
- Headless QuickJS plugin host for background-capable novel sources. See [novel-plugin-host.md](docs/dev/plans/novel-plugin-host.md) and the handbook [ln-plugin-host.md](docs/dev/ln-plugin-host.md).
- Browse + global search + installable LN plugins. See [novel-browse.md](docs/dev/plans/novel-browse.md).
- Details screen at manga parity. See [novel-details.md](docs/dev/plans/novel-details.md).
- Reader (WebView text canvas + Compose chrome). See [novel-reader.md](docs/dev/plans/novel-reader.md).
- Reader engine extras round 2: read-aloud (TTS) with background playback + lock-screen media notification, bionic reading, remove-extra-spacing, tap-edges-to-scroll, swipe-between-chapters, auto-scroll, and a vertical progress seekbar; settings reorganized to General / Display / TTS tabs (`6bd65d9be`, `4271172fc`, `9cde12b5f`, `705fa80bf`). The remaining `core.js` extras stay off by design (volume-button paging, paged reading, battery/time + reading-% overlays, custom CSS/JS/themes, in-reader chapter drawer); rationale in [novel-reader.md](docs/dev/plans/novel-reader.md).
- Reader chrome + chapter-list parity (round 3): a jump-to-chapter sheet reusing manga's `MangaChapterListItem` (read dot, date, bookmark, download button; merged novels show per-source labels in the unified order), a manga-style orientation picker, top-bar bookmark + WebView, seekbar percent labels, even bottom-bar spacing, translucent chrome. Plus a fix so WebView / Share open the novel's page, not the source homepage (`23ed2a2e4`, `d8faad579`). See [novel-reader.md](docs/dev/plans/novel-reader.md).
- Categories + hopper + tab-aware Display sheet. See [novel-categories.md](docs/dev/plans/novel-categories.md).
- Background update job (`NovelUpdateJob`). See [novel-update-job.md](docs/dev/plans/novel-update-job.md).
- Home-screen widget for manga + novel updates: a sectioned Glance widget added alongside Mihon's manga-only one (`b2e4d1cb8`). See [unified-updates.md](docs/dev/plans/unified-updates.md).
- Cross-source merge + dynamic grouping. See [novel-merge.md](docs/dev/plans/novel-merge.md).
- Tracking on AniList / MyAnimeList / MangaUpdates / Kitsu, group-aware (`7c56e07eb`). See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- Backup proto + installed-sources backup (`3c52d4c97`). See [novel-backup.md](docs/dev/plans/novel-backup.md).

### Novel parity backlog
- History tab, source migration, per-novel notes, novels in Stats, reader orientation lock, keep-screen-on, incognito, downloaded-only mode, mark-read-on-skip, download retry + Wi-Fi-only, global-search long-press add, collapse-at-bottom grouping, category filter, bulk-download dropdown, working Last-read sort, source pinning + global-search chips. Per-item commit SHAs in [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Surgical novel writes: favorite / cover / chapter-flag / orientation changes route through `UpdateNovel` / `SetNovelChapterFlags` / `SetNovelViewerFlags` interactors (the novel twins of Mihon's), writing one column instead of the whole row. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Download-queue reorder + sort: drag-to-reorder the flat novel queue (persisted to `NovelDownloadStore`, survives restart) plus a Sort menu (upload date / chapter number); the unified queue's Sort hits manga and novels together. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Batch / library migration: one unified migration screen for 1..N novels (single from a novel's overflow, batch from library multi-select). Each row auto-searches on scroll and suggests a target to accept or override; lazy-materialize on pick, then Copy / Migrate with flags. Replaces the old single-only migrate UI. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Migration redesign: covers + a chapter-count regression signal, a source-selection pre-step (Selected / Available, reorderable priority, `novelMigrationSources` pref), a source-to-target comparison row with manga-style per-row actions (one-tap Accept + overflow menu), a browse-style cover-grid override picker, and Copy / Migrate as distinct bottom actions. See [novel-migration-redesign.md](docs/dev/plans/novel-migration-redesign.md) (`bf5bd4f8f`, `94f7ef09d`, `8cb547a71`, `b66e3efd0`, `0ce7c95c1`).
- Merge-aware migration (manga + novel): migrating a merged entry keeps the merge, the new source taking the old one's place (Migrate swaps it out, Copy adds alongside), for manual and same-title auto groups alike, via the shared `MergeGroupAlgebra`. Novel migration also carries tracker links now, matching manga. Manga edit fenced `// RK` (`ae0dbc191`).
- Migrate-merge source picker (manga + novel): a pre-step to choose which source(s) of a merged series to migrate (the rest stay put); auto-skipped when nothing in the selection is merged. New `NovelMigrationSourcePickScreen` / `MangaMigrationSourcePickScreen`; details + library entry points rerouted (`dd0173b22`).
- Download settings parity: keep-last-N-read (delete-after-read slots), don't-delete-bookmarked, exclude-categories-from-delete, and download-ahead, all under Settings → Downloads. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Per-title novel update notifications: one grouped notification per updated novel, deep-linking into the novel via a new `SHORTCUT_NOVEL` intent. See [novel-parity-backlog.md](docs/dev/plans/novel-parity-backlog.md).
- Novel tracking private listing: `private` column on `novel_tracks` (migration 25) threaded through `NovelTrack`, the mapper / insert, and the `DbTrack` conversion (plus backup proto), with `NovelTrackUpdater.setRemotePrivate` and `allowPrivate` so the shared Tracking sheet shows "Track privately" for capable trackers (Kitsu / AniList / Bangumi) (`62070e5e7`).
- Reader brightness + colour filter: novel reader Display settings gain custom brightness + a colour filter (R/G/B/opacity + blend mode), the manga reader's controls but novel-specific (own `NovelPreferences` keys + a separate overlay flow). Reuses the shared `ReaderContentOverlay` (mounted over the WebView, below the chrome, touch-transparent); positive brightness drives the window, negative dims via the overlay. Grayscale/invert skipped (low value for text). On-device verified (`cf7941723`).
- Migration carries cover + notes: `NovelMigrationFlag` gains COVER + NOTES; `MigrateNovelUseCase` copies the custom-cover file (negated-id slot) + bumps `coverLastModified` and carries notes, gated by their flags and shown only when applicable across the batch (`Novel.hasCustomCover` helper, the novel twin of `MigrateMangaUseCase`). Target-source picker deliberately left out (`2593e131c`).
- Restore skip-if-newer: `novels` gains `version` + `is_syncing` columns and an `update_novel_version` trigger (migration 26, verbatim port of `mangas.sq`), plus a `BackupNovel` proto field, so restoring an older backup keeps a newer local novel instead of overwriting it (the novel twin of `MangaRestorer`'s version merge). Dropped `last_modified_at`: novel restore has no `sortByNew`, so nothing reads it (`6be6efe1c`).
- Round 2 parity sweep: novel default-category for new novels (`db116e592`), add-to-library button on novel history rows (`658be0feb`), novel source enable/disable (`bf538dabf`), novel chapter "downloaded" filter (`143abf9cf`), and novel update-error tracking + per-category manual update (shared All / Manga / Novels Update-errors screen, fixes the Novels-chip refresh that fired the manga job) (`f28259d00`).

### Adult / EXH subsystem (phases 1-4 + 5a + 5b)
Ported from `refs/komikku`, re-typed onto Mihon's models. On `main`; on-device verified on emulator-5554.
- Phase 1: delegation core (`EnhancedHttpSource` / `DelegatedHttpSource`) + gallery-metadata store (`search_metadata` / `search_tags` / `search_titles`, migration 23) + the 4 free enhanced sources (nHentai, Pururin, 8Muses, LANraragi) + URL import (`08d9f2bfa`, `24a51cb03`, `87abe5245`).
- Phase 2: built-in E-Hentai / ExHentai source (anonymous browse + read, full gallery filters, gallery-version chapters); Settings → Advanced "Enable adult sources" toggle; ExHentai WebView login; E-Hentai settings screen + server-profile sync (uconfig) (`41e85aac8`, `fc73b2adc`, `29d05b931`, `497b8d9f1`, `1f9ec4cab`, `692c98200`).
- Phase 3: E-Hentai tag autocomplete (full EHTags catalogue), library search by gallery tags, Compose-native gallery metadata viewer (`2e3a1a892`, `15a3a4832`, `b6655ae08`).
- Phase 4: three net-new enhanced wrappers (HentaiFox, AsmHentai, Koharu/SchaleNetwork) that re-parse each site's gallery details into namespaced tags; plus a fix to match delegated sources by source name so R8-minified factory extensions wrap (also repairs nHentai/LANraragi). Scoped down from six (Luscious/HentaiNexus/3Hentai parked). On-device verified (`0633f75a2`, `01e027dab`, `846e7e85d` + the SchaleNetwork wrapper).
- Phase 5a: E-Hentai favorited-gallery update checker. A WorkManager job re-checks each favorited EH gallery for a newer version and reconciles the version chain locally (full disk-backed `EHentaiUpdateHelper` + `MemAutoFlushingLookupTable` replacing the in-session stub, merging chapters / read state / history / categories), with a "Gallery update checker" settings group. On-device verified: launch, settings render, scheduling; deep version-reconciliation is a faithful port not yet live-triggered.
- Phase 5b (scoped): E-Hentai favorites account backup (one-way push + opt-in remote remove), not the full two-way sync. Favoriting a gallery adds it to the account; deleting locally keeps it on the account unless you tick "Also remove from E-Hentai favorites" in a DeletableTracker-style confirm; a "Back up all favorites now" job pushes the existing library (throttled). New `EHentai.fetchFavorites`/`addFavorite`/`removeFavorites`, `EhFavoritesBackupJob`, a `// RK` island in `MangaScreenModel`/`MangaScreen` + the confirm dialog, and a "Favorites backup" settings group. Account push/remove needs an ExHentai login to verify live (user-side); compile + non-account paths verified.
- EXH settings + presentation polish: E-Hentai promoted to its own top-level Settings category (gated by the adult-sources pref, with a traced EH-favicon `EhAssets.EhLogo` icon) matching Komikku's placement (`f80cfc5b3`); the built-in EH/ExH source rows show that logo instead of the blank placeholder (`6a4422c23`); and the four missing EXH settings surfaced in `SettingsEhScreen`: Incognito (real fix via `EH_PACKAGE` + a `// RK` `GetIncognitoState` map), Language filtering, Front-page categories, and the updater-statistics dialog (`90060670e`). On-device verified.
- Hide the stock E-Hentai extension while built-in EH is on (`4a4c7c0bb`): the built-in EH/ExHentai sources register under the same ids as the legacy `eu.kanade.tachiyomi.extension.all.ehentai` extension, so installing that extension would shadow (in the source map) and duplicate (in the extensions list) the built-in ones. Adds `BlacklistedSources`; `AndroidSourceManager` skips the stock EH source ids and `ExtensionManager` filters the package from the installed/available/untrusted lists, both reactive on the hentai gate. Second fix from the audit. Defensive: the stock E-Hentai extension is not in Keiyoushi (verified in `refs/keiyoushi-extensions-source`), so it only triggers on a sideloaded / legacy install; on-device regression verified (built-in EH intact + singular, extensions list intact, gate toggles reactively), the suppression path itself not reproducible without that extension.
- Skip adult galleries in the library update sweep (`1a0c8b35e`): E-Hentai / ExHentai / Pururin / nHentai entries default to `ALWAYS_UPDATE`, so every refresh re-fetched each saved gallery (rate-limit / ban risk); their real updates already run through `EHentaiUpdateWorker`. Adds `LIBRARY_UPDATE_EXCLUDED_SOURCES` (all EH/ExH language ids + Pururin) and a `// RK` `filterNot` in `LibraryUpdateJob`; nHentai's id varies by extension version, so it is derived at runtime from a ported `currentDelegatedSources` map (`AndroidSourceManager.nHentaiDelegatedSourceIds`). First fix from the parity & adult-system audit. On-device verified: a favorited E-Hentai gallery (source EH_SOURCE_ID) is dropped from a library update before the restriction logic, so it appears in neither the skip log nor `library_update_errors` (0 rows under airplane mode, where an unexcluded 0-chapter ALWAYS_UPDATE entry would have errored), while the other library entries process normally.

### Unified surfaces
- Unified Updates tab: manga + novel interleaved, filters, by-category, group-by-series. See [unified-updates.md](docs/dev/plans/unified-updates.md).
- Unified reader: shared Compose chrome over the View-based manga reader (Option F direction); Phase 1 shipped. See [unified-reader.md](docs/dev/plans/unified-reader.md).
- History tab consolidated (manga + novel behind a content chip).

## Conventions

Branch `main`; edits to Mihon files fenced with `// RK -->` / `// RK <--`; net-new code in `reikai.*`; Injekt DI (no Koin); immutable `tachiyomi.domain` models; SQLDelight migrations are additive. Upstream Mihon changes are ported by hand from `refs/mihon/`. Full detail in [CLAUDE.md](CLAUDE.md), [.claude/rules/](.claude/rules/), the [plan docs](docs/dev/plans/), and the memories.
