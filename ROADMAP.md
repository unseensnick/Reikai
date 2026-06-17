# Reikai Roadmap

Forward-looking plan for the Mihon rebase (branch `design/mihon-rebase`, which becomes `main` when the rebase ships). This is the single source for what's left and in what order. Day-to-day session state lives in `Handoff.md`; durable architecture, decisions, and lessons live in the `.claude` memories; historical per-phase plans are archived (see the end). Supersedes the old `rosy-gathering-walrus.md` master plan.

## Status snapshot (June 2026)

The rebase from Yōkai onto Mihon is nearly complete. The light-novel vertical (P5) is the last in-flight phase; everything else is done, dropped, or folded into the sequence below.

| Phase | What | Status |
|---|---|---|
| P0–P1 | Mihon base + Reikai identity + source-api related-manga contract | ✅ done |
| P2 | Library screen carry: single-list + hopper, dynamic grouping (Group tab), filter/sort, category sort order, update-errors screen | ✅ done |
| P3 | Manga details carry: merge / Manage-sources UI, private tracking, two-finger range select, cover-accent backdrop, recommendations carousel | ✅ done |
| P4 | Pref-based merge engine + preferred-source ranking + tracker-link mirroring | ✅ done |
| P5 | Light-novel vertical (domain/DB, QuickJS host, browse, details, reader, downloads, library, background updates, merge, grouping, unified Updates) | 🔶 in progress — see Active sequence |
| P6 | Recommendations: engine, taste profile, tracker recs, See-all browse | ✅ done |
| P7 | Reader tweaks: configurable bottom bar, chapters sheet, cover tint, mark-read-on-skip, resume/preload | ✅ done |
| P8 | Settings / shell carry | ❌ dropped (Mihon already covers it) |
| P9 | Category bulk-delete + unified Recents | 🔶 folded into Active sequence |

Recently shipped on top of the above: the **unified Updates tab** (manga + novel interleaved, filters, by-category filter, group-by-series), **novel library dynamic grouping** (now with the collapsed-group sink + collapse-all parity, Active #1 below), the **Tier 0 duplication cleanup** (below), and the **Mihon upstream syncs** (TachiyomiX 1.6 `memo` + 1.6 extension loading, then the memo-transform / chapter-order follow-ups). Fix: Browse → Extensions novel-plugin search now filters on the Novels chip.

## Tier 0: duplication cleanup ✅ done (2026-06-16)

Completed and pushed: Tier 1 (`6c27c5923`), Tier 2 (`85ff3326d`), Tier 3 (`f783979b5`). A 3-tier consolidation of genuine intra-Reikai copy-paste (found by a deep-dup audit), no behavior change, gated by unit tests + on-device. What landed:

- **Tier 1:** `MergeGroupAlgebra.computeMerge` + the collapse parsers (`parseMergeKeys`/`parseUnmergedPairs`/generic `splitByUnmergedPairs`); `NovelStatusCode.toStringRes` (the 3 novel display sites; the `StatusDropdown` picker keeps its distinct `unknown_status` label); `normalizeTrackerScore(raw, max)` + `String.toTagKey()` (the 5 taste fetchers + the ranker); `NovelRepository.getFavoritedKeysAsFlow()`.
- **Tier 2:** shared category tri-state helpers in `reikai.presentation.category` (consumed by the library + Updates filter dialogs); `NovelUpdateJob.categoryGate()`.
- **Tier 3:** `refreshNovelFromSource` in `reikai.data.novel`, shared by `NovelUpdateJob.checkNovel` and `NovelDetailsScreenModel.refreshNovel`; the browse-open `fetchAndSync` insertOrGet path stays separate. (One micro error-path normalization: a failed manual refresh no longer re-walks the old last page, matching the background job.)

**Leave alone (intentional Mihon-clones / already shared, audited and confirmed):** novel browse cells, source/merge badges, merge-source chips, manage-sources dialogs, `NovelCategoryScreenModel`, the two notifiers, the 5 tracker-fetcher shells + their `mapStatus` tables, the 4 recs providers' HTTP shape, recs grid items, the foreground-service idiom, SQLDelight-mandated mapper param lists, the flag-bit layouts (`NovelLibrarySort`/`NovelChapterFlags`), negative-id encoding, and the already-shared `MergeGroupAlgebra`/`LibraryDynamicGrouping`/`ContentTypeFilterChips`/`reikaiSortCategories`/`matchesCategoryFilter`.

## Active sequence (do in this order)

Two ordering rules: **the backup proto goes last** (it must serialize everything the earlier items add: history rows, tracker links, categories, merges, grouping), and the quick polish leads for momentum. The Tier 0 cleanup above is done, so these slices build on the shared helpers rather than extending copy-paste.

### 1. Novel grouping: collapse-at-bottom  ✅ done (`e5c0ecee6`)
Threaded the novel session-collapse set + the `collapsedDynamicAtBottom` pref through `buildState` into `buildNovelDynamicGrouping` (a 3-way inner combine on the main flow, so a collapse toggle rebuilds and re-sinks live); dropped the redundant post-build collapse stamp/collector. Also fixed `toggleAllCategoriesCollapsed` to key dynamic groups by `headerKey` (real categories by id), mirroring `LibraryScreenModel`, so the hopper "collapse all" collapses + sinks dynamic groups too. Verified on-device (Z Fold) in both the single-list and tabbed views.

### 2. Novel library: include/exclude category filter  `[S]`
The novel library filter sheet only has downloaded/unread/started/completed/bookmarked. Manga also has an include/exclude **category** filter, and the shared `CategoryFilter` math is already wired into the novel Updates feed (`reikai/domain/category/CategoryFilter.kt`), just not the novel library.
- **Scope:** add the category include/exclude row to `NovelLibrarySettingsDialog` and apply `matchesCategoryFilter` in `NovelLibraryScreenModel`, reusing the existing helper.
- **Done when:** including/excluding a category in the novel library filter narrows the grid the way manga does, in both the tabbed and single-list views; on-device verified.

### 3. Novel details: bulk-download dropdown  `[S]`
Manga details has a toolbar download dropdown (next 1/5/10/25, all unread, all) via `DownloadDropdownMenu`. `NovelToolbar` has no download action, so "download all / next N" is unreachable except by manual multi-select.
- **Scope:** add the download dropdown to `NovelToolbar`, wired to a `DownloadAction` handler in `NovelDetailsScreenModel` that queues the next N / all unread / all chapters via `NovelDownloadManager`.
- **Done when:** the novel details toolbar offers download next 1/5/10/25, all unread, and all, matching manga; on-device verified.

### 4. Fix the dead "Last read" novel sort  `[S]`
The novel library `LastRead` sort reads `novels.last_read_at`, but nothing ever writes that column (the reader writes only `last_text_progress` + `read`), so the sort is a silent no-op. The read-time write-path overlaps the History tab (#5): land the minimal version here, then build the history feed on top.
- **Scope:** stamp `novels.last_read_at` when a novel chapter is read (in the reader `saveProgress` / mark-read path), the same hook the History tab needs.
- **Done when:** reading a novel chapter updates its `last_read_at`; the novel library `LastRead` sort orders by most-recently-read; on-device verified.

### 5. Novel History tab  `[L]`
The History half of the old "unified Recents" (Updates half already shipped). Full per-chapter parity, not a stub. Builds on #4's read-time write-path (shared hook).
- **Scope:** a `novel_history` table (+ migration); `NovelHistory` domain model + repository + interactors (recent feed / upsert / delete / clear); a per-chapter read-time write-path in the novel reader (the analog of Mihon's `UpsertHistory` from `ReaderViewModel`); novels interleaved into the History tab behind an All / Manga / Novels chip, mirroring `ReikaiUpdatesScreen`.
- **Done when:** reading a novel chapter records history; the History tab shows novel entries (All interleaves by date); resume-from-history opens the right chapter; delete-entry and clear-all work; on-device verified.

### 6. Category bulk-delete  `[S]`  (= roadmap R5)
- **Scope:** multi-select + delete with undo on Mihon's Compose category screen, for both manga and novel category tabs.
- **Done when:** select several categories, delete, undo restores them; works on both tabs; on-device verified.

### 7. Novel source migration  `[M]`
- **Scope:** move a favorited novel from one source to another, carrying read/bookmark/progress state (and tracks, once #8 lands).
- **Done when:** a migrate action on novel details/library re-homes the novel to a chosen source with progress preserved; on-device verified.

### 8. Novel trackers  `[M]`  — AniList / MyAnimeList / MangaUpdates / Kitsu
Mirror LNReader's tracker set, reusing Mihon's existing tracker infrastructure (OAuth/token flows). No new tracker service.
- **Scope:** a novel tracking sheet on novel details that binds a novel to those four trackers and writes status / progress / score; novel-track persistence (a `novel_track` table + repo).
- **Done when:** a novel can be bound + updated on each of the four trackers and it round-trips; on-device verified.
- **Note:** dedicated LN trackers (NovelUpdates, MiraiList, Novel Trackr, RanobeDB, Hardcover) were researched (June 2026) and are NOT viable as on-device sync targets; see the `novel-tracking` memory. Hardcover is the only future watch item.

### 9. Novel backup proto (S9)  `[M]`  — last
- **Scope:** `BackupNovel` / `BackupNovelChapter` / `BackupNovelCategory` (plus history + track fields once #5 / #8 land) + a novel list on the Backup root proto; backup creator + restorer; novel merge/grouping prefs. Fence Mihon backup-file edits with `// RK`. Note `BackupManga`/`BackupChapter` already carry `memo` at proto 112/13 (from the TachiyomiX sync) — novel additions use new classes/numbers.
- **Done when:** a backup includes the novel library (favorites + chapters + categories + history + tracks + merges), and restoring on a fresh install reproduces it; on-device round-trip + an older pre-novel backup still restores.

## Signed release pipeline (Mihon AGP signing)  `[M]`

Independent of the LN sequence above (infra, do whenever). The rebase carried over only `build_check.yml` (PR build) and dropped Yōkai's `build_push.yml` release workflow, so release builds are currently a stopgap: debug-signed via the `// RK` island in `app/build.gradle.kts` (`signingConfig = signingConfigs.getByName("debug")`), which ties in-place upgrades to whatever machine built them. Reikai already has its own keystore plus the four signing secrets (`SIGNING_KEY` / `ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`) on the `unseensnick/Reikai` repo from the Yōkai era.

**Decision:** adopt Mihon's AGP-native signing (upstream `6552ffe31` "Sign APK with AGP", deferred from the 2026-06-17 dep sync) rather than restoring Yōkai's post-build `null2264/actions/android-signer` step, since Mihon now maintains the AGP path and it sheds the external action.

- **Scope (two pieces):**
  - (a) Signing config in `app/build.gradle.kts`, `// RK`-fenced: adapt Mihon's block, swap the `GITHUB_REPOSITORY_OWNER == "mihonapp"` CI gate to `unseensnick` (reading the four secrets from env), and keep the owner-agnostic local `keystore.properties` branch so a signed release can also be built locally with the same key; add `keystore.properties` to `.gitignore`. The existing release line then picks up the real key automatically.
  - (b) A release workflow, successor to `main`'s `build_push.yml`, adapted to the Mihon base (no product flavors; signed `assembleRelease` outputs are now `app-<abi>-release.apk`): `workflow_dispatch` build, then publish per-ABI `reikai-*.apk` (+ sha256) to the Releases tab.
- **Scout first:** the Mihon release surface (APK output paths, ABI-split naming, the `-Pinclude-telemetry` / `-Penable-updater` flags) before writing the workflow.
- **Done when:** a `workflow_dispatch` release builds a signed APK with the keystore and publishes per-ABI `reikai-*.apk` to the Releases tab; it installs over an existing `.y2k` install (signature matches); and a local `assembleRelease` with `keystore.properties` present is also signed. Reference: the upstream-sync memory records the decision and the secret-name mapping.

## Parity backlog (later)

Lower-priority manga↔novel parity gaps, recorded so they aren't lost. Pull into the active sequence as momentum allows; none block the items above.

- **Novels in the Stats screen  `[S]`:** `StatsScreenModel` injects only `GetLibraryManga`, so the novel library is invisible in every stat. Add novel counts (titles, chapters read, completed) via a `// RK` graft.
- **Per-entry Notes for novels  `[S]`:** novel details passes `notes = ""` / `onEditNotes = {}` to `ExpandableMangaDescription`; wire a real notes field (manga has per-title notes).
- **Reader keep-screen-on  `[S]`:** the novel reader's `keepScreenOn` is only a JS var in the HTML builder; add the Android `FLAG_KEEP_SCREEN_ON` window flag driven by the existing reader pref (manga does this in `ReaderActivity`).
- **Small polish batch  `[S]`:** make novel global-search source headers tappable to open that source's full browse (manga has this); give the novel update result notification per-title entries + deep-links (currently one "N novels" line); add the missing novel settings twins (delete-after-N-slots, don't-delete-bookmarked, download-exclude-categories, download-ahead, auto-refresh-metadata).
- **Novel reader engine extras  `[M]`/`[L]`:** LNReader's bundled `core.js` supports TTS, page-mode (vs scroll), auto-scroll, bionic reading, tap-to-scroll, volume-button paging, custom CSS/JS/themes, an in-reader chapter drawer, and a progress seekbar, all hard-wired off in `NovelReaderHtmlBuilder`. **TTS** is the only high-interest one (and is gated by the headless-host polyfill concern, see the `ln-host-polyfill-parity` memory); the rest are low value. This matches the old Yōkai reader (also limited), so it is unported-LNReader-parity, not a regression.

## Parked / not building

- **Dedicated LN trackers** (NovelUpdates / MiraiList / Novel Trackr / RanobeDB / Hardcover): not viable as of June 2026 (no sanctioned read+write API for on-device use). Re-check Hardcover only if it leaves beta and ships OAuth + allowlisting. Detail in the `novel-tracking` memory.
- **Y13** force side-nav rail, **Y17** DOKI theme, **Y18** in-app app-icon changer (P8, dropped; revivable — Y18 is blocked on Reikai-branded icon assets).
- **Y4** drag-sort, **Y5** staggered grid, **Y8** (= R16, duplicate), **Y19** stats drill-down (out of scope).
- **EPUB export** (LNReader has it; out of plan).
- **Novel recommendations / related carousel:** gated on novel trackers (getting the mainstream trackers to track LNs at all is unreliable) and LN sources don't expose related-title metadata, so it is not worth building unless novel tracking proves out.
- **Upcoming / release calendar for novels:** LN sources rarely expose a reliable release cadence, so an upcoming feed would be mostly empty; the calendar stays manga-only.
- **Saved searches** (browse filter presets): Tachiyomi/J2K-lineage power-user feature (name and store a per-source query+filter combo, re-applied in one tap). Not Reikai-distinctive, and was View/XML on the old browse screen, so closing it is a full Compose rebuild for low value. The DB + serializer layer (`saved_search` table + `FilterSerializer`) survives on `design/library-compose` if ever revived.
- **Dynamic launcher shortcuts** (`MangaShortcutManager`): J2K-lineage convenience that filled the long-press-app-icon menu with recent manga/sources. Built on the old Recents presenter + Conductor controllers + `PreferencesHelper`, so it's a rewire against Mihon's data layer, not a port, for a cosmetic nicety. Mihon ships only a static `shortcuts.xml`.

## Historical plans

The per-phase / per-feature plans are archived at `C:\Users\unseensnick\.claude\plans\archive\` (49 files; the partial repo mirror under `plans/` was dropped). Notable ones:
- `rosy-gathering-walrus.md` — the original P0–P9 roadmap + rebase rationale (this file supersedes it).
- `harmonic-roaming-rocket.md` — P2 library carry; `manga-details-compose-port.md` / `sunny-growing-rainbow.md` — P3 details; `whimsical-crunching-aho.md` — P6 recommendations; `phase-7-implementation.md` — P5 novels domain.
- Recent feature slices use generated names (e.g. the 4-phase unified-Updates and S8 novel-merge plans); search the archive by content if needed.

## Conventions

Branch `design/mihon-rebase`; edits to Mihon files fenced with `// RK -->` / `// RK <--`; net-new code in `reikai.*`; Injekt DI (no Koin); immutable `tachiyomi.domain` models; SQLDelight migrations are additive. Upstream Mihon changes are ported by hand from `refs/mihon/` (never `git merge`). Full detail in `CLAUDE.md`, `.claude/rules/`, and the memories.
