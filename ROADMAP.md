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

Recently shipped on top of the above: the **unified Updates tab** (manga + novel interleaved, filters, by-category filter, group-by-series), **novel library dynamic grouping**, and the **Mihon TachiyomiX 1.6 upstream sync** (the `memo` field + 1.6 extension loading).

## Active sequence (do in this order)

Two ordering rules: **the backup proto goes last** (it must serialize everything the earlier items add: history rows, tracker links, categories, merges, grouping), and the quick polish leads for momentum.

### 1. Novel grouping: collapse-at-bottom  `[S]`
Finish the dynamic-grouping feature shipped this cycle. Today the novel grouping passes `collapsedDynamicCategories = emptySet()` / `collapsedDynamicAtBottom = false` to the kernel, so collapsed groups don't sink.
- **Scope:** thread the novel session-collapse set + the existing `collapsedDynamicAtBottom` display pref into `NovelLibraryScreenModel.buildNovelDynamicGrouping`.
- **Done when:** with "move dynamic groups to bottom" on, a collapsed novel group sinks below the expanded ones, in both the tabbed and single-list views (on-device).

### 2. Novel History tab  `[L]`
The History half of the old "unified Recents" (Updates half already shipped). Full per-chapter parity, not a stub.
- **Scope:** a `novel_history` table (+ migration); `NovelHistory` domain model + repository + interactors (recent feed / upsert / delete / clear); a per-chapter read-time write-path in the novel reader (the analog of Mihon's `UpsertHistory` from `ReaderViewModel`); novels interleaved into the History tab behind an All / Manga / Novels chip, mirroring `ReikaiUpdatesScreen`.
- **Done when:** reading a novel chapter records history; the History tab shows novel entries (All interleaves by date); resume-from-history opens the right chapter; delete-entry and clear-all work; on-device verified.

### 3. Category bulk-delete  `[S]`  (= roadmap R5)
- **Scope:** multi-select + delete with undo on Mihon's Compose category screen, for both manga and novel category tabs.
- **Done when:** select several categories, delete, undo restores them; works on both tabs; on-device verified.

### 4. Novel source migration  `[M]`
- **Scope:** move a favorited novel from one source to another, carrying read/bookmark/progress state (and tracks, once #5 lands).
- **Done when:** a migrate action on novel details/library re-homes the novel to a chosen source with progress preserved; on-device verified.

### 5. Novel trackers  `[M]`  — AniList / MyAnimeList / MangaUpdates / Kitsu
Mirror LNReader's tracker set, reusing Mihon's existing tracker infrastructure (OAuth/token flows). No new tracker service.
- **Scope:** a novel tracking sheet on novel details that binds a novel to those four trackers and writes status / progress / score; novel-track persistence (a `novel_track` table + repo).
- **Done when:** a novel can be bound + updated on each of the four trackers and it round-trips; on-device verified.
- **Note:** dedicated LN trackers (NovelUpdates, MiraiList, Novel Trackr, RanobeDB, Hardcover) were researched (June 2026) and are NOT viable as on-device sync targets; see the `novel-tracking` memory. Hardcover is the only future watch item.

### 6. Novel backup proto (S9)  `[M]`  — last
- **Scope:** `BackupNovel` / `BackupNovelChapter` / `BackupNovelCategory` (plus history + track fields once #2 / #5 land) + a novel list on the Backup root proto; backup creator + restorer; novel merge/grouping prefs. Fence Mihon backup-file edits with `// RK`. Note `BackupManga`/`BackupChapter` already carry `memo` at proto 112/13 (from the TachiyomiX sync) — novel additions use new classes/numbers.
- **Done when:** a backup includes the novel library (favorites + chapters + categories + history + tracks + merges), and restoring on a fresh install reproduces it; on-device round-trip + an older pre-novel backup still restores.

## Parked / not building

- **Dedicated LN trackers** (NovelUpdates / MiraiList / Novel Trackr / RanobeDB / Hardcover): not viable as of June 2026 (no sanctioned read+write API for on-device use). Re-check Hardcover only if it leaves beta and ships OAuth + allowlisting. Detail in the `novel-tracking` memory.
- **Y13** force side-nav rail, **Y17** DOKI theme, **Y18** in-app app-icon changer (P8, dropped; revivable — Y18 is blocked on Reikai-branded icon assets).
- **Y4** drag-sort, **Y5** staggered grid, **Y8** (= R16, duplicate), **Y19** stats drill-down (out of scope).
- **EPUB export** (LNReader has it; out of plan).

## Historical plans

The per-phase / per-feature plans are archived at `C:\Users\unseensnick\.claude\plans\archive\` (49 files; the partial repo mirror under `plans/` was dropped). Notable ones:
- `rosy-gathering-walrus.md` — the original P0–P9 roadmap + rebase rationale (this file supersedes it).
- `harmonic-roaming-rocket.md` — P2 library carry; `manga-details-compose-port.md` / `sunny-growing-rainbow.md` — P3 details; `whimsical-crunching-aho.md` — P6 recommendations; `phase-7-implementation.md` — P5 novels domain.
- Recent feature slices use generated names (e.g. the 4-phase unified-Updates and S8 novel-merge plans); search the archive by content if needed.

## Conventions

Branch `design/mihon-rebase`; edits to Mihon files fenced with `// RK -->` / `// RK <--`; net-new code in `reikai.*`; Injekt DI (no Koin); immutable `tachiyomi.domain` models; SQLDelight migrations are additive. Upstream Mihon changes are ported by hand from `refs/mihon/` (never `git merge`). Full detail in `CLAUDE.md`, `.claude/rules/`, and the memories.
