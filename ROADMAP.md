# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/workflow.md](.claude/rules/workflow.md) ("Roadmap & plans").

## Now

The **unified-content-UI + parity program** on `feat/unified-content-ui` (unmerged, ~140 commits) is essentially complete: round 1 and round-2 Phases 1-5 and 7 all shipped. Shipped detail is in CHANGELOG `[Unreleased]` and `Handoff.md`, not here. One open item remains:

- **Track-info Screen/Model collapse (round-2 Phase 6)** `[L]` - collapse `NovelTrackInfoDialog` onto the shared `TrackInfoDialog` Screen/Model stack; the leaf selectors are already shared, what differs is the Screen wrapper + the writer (`NovelTrackUpdater` vs direct `Tracker`/`BaseTracker`). Deferred so far; the next session's task. [Plan](docs/dev/plans/content-parity-drift-and-collapse.md).

## Next

- **Unify the download subsystem across manga and novels (Road B)** `[L]` - collapse the parallel novel download cache/provider into one shared disk-scan layer serving both types, keyed on shared primitives, so they can't drift (Tsundoku's single-subsystem model). The novel download re-key deliberately mirrored the manga scheme + cache shape so this is a code merge, not a data migration. Touches Mihon's shipped download files (`// RK`).
- Then the reader **tsundoku track** (seamless novel-reader transitions, later the native-reader migration), sequenced after the above; detail under Later -> Reader.

## Later

Backlog, grouped by area. Unordered within an area.

### Novels (manga <-> novel parity)

Core manga/novel parity has shipped (round 1 + round 2); the big collapses are done and the remaining twin (track-info, Phase 6) is in Now. What is left is smaller enhancements and polish:

- **Skeleton loading on the novel details page** `[S]` - placeholder skeletons while the first load resolves (like LNReader), instead of a bare spinner when opening a non-library novel. An enhancement, not a parity gap (manga also uses a plain spinner).

Opportunistic polish:
- Browse: Latest shortcut, Last-used, hide-in-library, per-row language, genre-tap-search.
- Tracking: start-date backfill, create-private-at-bind, friendlier Fill-from-tracker errors (no-entry-found on a 404 + null-message fallback).
- Updates / history: fast-scroll animation.
- Details: long-press-copy WebView URL, per-source scanlator filter for merged novels, novel tag-tap global search, novel long-press-favorite category shortcut.

### Details

From the 2026-07-04 Komikku parity audit (missing features + gestures on the details screen).

- **Header long-press menus + tap-source-to-browse** `[M]` - long-press the title / author / source for library search, global search and copy (today it only copies); tap the source name to open its browse. Flagship parity gap.
- **Per-chapter source label on merged entries** `[M]` - show which source each chapter came from in a merged series.
- **Per-group Preferred-sources override** `[S-M]` - override the global Preferred-sources ranking for a single merge group (decides which source's version of shared-numbered chapters wins), via a reorder in Manage sources. Low priority; `ChapterAggregation.aggregate` already takes the ranking as a parameter, so only a per-group order pref plus the reorder UI are new. The global ranking still covers the common case.
- **Details overflow polish** `[S]` - per-entry disable-auto-update, clear-data (downloads + cached chapters), open folder, jump to source settings.
- **AMOLED-aware adult tag-chip borders** `[S]` - weighted / pure-black-dark-mode borders on the adult gallery-info tag chips (copying metadata already works via the metadata viewer).

### Browse & sources

From the same audit.

- **Find-a-source search box** `[M]` - filter the sources list by name or extension when you have many.
- **Custom source categories** `[M]` - group installed sources under your own headers (assign each source to one or more categories) in the Sources list, beyond the default language grouping. Needs source-category storage.
- **Source-list & row polish** `[S]` - row badges (language flag / NSFW / extension name), a browse-toolbar incognito toggle, an NSFW-only filter, per-source data-saver exclude, a browse panorama toggle (the library already has panorama), hide latest / pin.

### Reader

- **Seamless chapter transitions in the novel reader (Option 1)** `[M]` - port tsundoku's infinite-scroll idea onto the current WebView reader: at a scroll threshold, append the prefetched next chapter behind a divider, track the chapter boundary (title / progress / mark-read / history / prefetch-next), and prune distant chapters. The manga webtoon reader has this; novels don't. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Tsundoku-based novel reader migration (Option 3)** `[XL]` - its own branch, later: replace the bespoke WebView + LNReader-core.js novel reader with tsundoku's native reader folded into Mihon's `ReaderActivity` (novels join the manga reader, native rendering, a maintained upstream to sync from, the full tsundoku feature set). Accepts the View-based reader for novels; recommended, deferred by choice. Start with a migration-planning `/scout`. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Auto Webtoon Mode (smart reading-mode detection)** `[S]` - when a series has no manual reading-mode set, auto-pick Webtoon for long-strip content (manhwa / manhua / webtoon) from its genre tags, plus generic name catch-alls and a short list of currently-live long-strip sources; everything else stays on the global default, and a manual per-series choice always wins. Port of Komikku's metadata-driven auto-webtoon (genre + source-name classification, not image/aspect-ratio detection). Default off, one Settings -> Reader toggle; manga-only (novels have no paged/long-strip axis). Net-new `MangaType` heuristic + a `// RK` branch in `getMangaReadingMode()`. Liveness of the source list checked via byparr (July 2026). From `unseensnick/Reikai#40`.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - the manga/novel/adult surfaces are now collapsed onto shared `Entry*` components (History/Updates rows, cover dialog, details screen, browse, global search, library settings, sort), so the structural unification is largely done. The remaining forward work is owning the pixels: a Reikai theme (color / typography / shape / component defaults through the single `TachiyomiTheme` -> `MaterialExpressiveTheme` entry point) layered over the shared components, seeding tokens in `DESIGN.md` first (brand in `PRODUCT.md`: quiet, dense, deliberate). Complementary to, not a replacement for, the structural work. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked / not building

One line each; revive note where relevant.

- **Full two-way EH favorites sync** (pull account -> library) - the only feature that would mutate the library from a remote source; the scoped one-way backup shipped instead. Revive only if account -> library mirroring is wanted.
  - **EH per-page add-path throttle** `[S]` bundles here - redundant with the shipped 3/sec rate limit for normal imports; only this feature's sustained walk exercises it.
- **Manga per-page chapter loading** - no manga source would feed a paged chapter list (the contract returns the full list in one call).
- **Auto-error a chapter stuck mid-download** `[S]` - a per-chapter stall timeout so a hung image download gives up faster than `callTimeout` x3 (~8 min worst case). Parked: the pause/resume fix covers the reported bug and stalls still self-resolve via `callTimeout`. Revive if a permanent stall (callTimeout never fires) turns up.
- **Per-chapter control in the download queue (expandable cards)** `[M]` - the unified queue collapsed to one card per series (drag / move-to-top / move-to-bottom / cancel act on the whole series), dropping per-chapter reorder + per-chapter cancel from the global queue. Parked: series-level control covers the real cases and per-chapter selection lives on the details screen. Revive by expanding a card to its chapters on tap; Mihon's per-chapter manga queue files (`DownloadAdapter` / `DownloadHolder` / the `download_single` menu) are still in the tree, so it is mostly wiring plus a novel equivalent.
- **Dedicated LN trackers** (NovelUpdates / MiraiList / RanobeDB / Hardcover) - no sanctioned read+write API as of June 2026; recheck Hardcover if it leaves beta. See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel** - now feasible (trackers shipped) as an `[M]`; the source-native path stays infeasible (no plugin `getRelated`). Reconsider if wanted.
- **Batch recommendation search** - overlaps the existing taste-profile layer. Revive if manual multi-title discovery is wanted.
- **CMK source-native recommendations (+ id-graph)** - stock CMK was pulled from the extension repos, so the recs port's id-set gate never fires (only clones with different ids remain). Revive if a first-party CMK source returns; the id-graph idea (suggest tracker binds from an entry's cross-links) rides the same API.
- **MD source-native similarity carousel** - its only data source (`api.similarmanga.com`, the TF-IDF `similar-manga` project) is frozen at 2025-05-27 and unmaintained; MD's official `/relation` endpoint returns exact relations (doujinshi / colored), not discovery, and tracker recs already cover popular titles. Dropped with the MD enhanced source (0.2.0); see [md-enhanced-source.md](docs/dev/plans/md-enhanced-source.md).
- **Serialize track-sheet edits (rapid edits clobber each other)** `[M]` - each field edit runs in its own coroutine (`TrackInfoDialog.kt`), so two quick edits (e.g. chapter then score) race on the same track row and the second wins, losing the first. A Mihon-wide race, worst on MDList. Parked: the per-track mutex fix touches shared tracker code, so it needs its own scoped pass. Revive standalone.
- **Upcoming / release calendar for novels** - LN sources rarely expose a reliable cadence; stays manga-only.
- **Hide the novel browse Latest chip** - considered gating it off like manga's `supportsLatest`, but the LN plugin API exposes no per-source latest capability to gate on: `showLatestNovels` is just a runtime flag each plugin honors or ignores, the registry manifest carries no listings field, and LNReader itself shows Latest unconditionally. A plugin that ignores the flag returns the same list as Popular (not an empty page), so the symptom is harmless. Every build option is poor (runtime probe, curated allow-list, or a flag only plugins we patch would set). Kept as-is.
- **Bulk novel-migration search tuning** (extra query, hide-unmatched, hide-without-updates, deep search, prioritize-by-chapters) `[M]` - these manga config options are gated off for novels for now: novel batch-migration is manual accept/override by design (no smart-title matching), so the auto-match knobs add little. Revive if novel migration matching gets painful.
- **Tracker-based merge-group healing for novels** - manga splits mis-grouped merge members by comparing tracker keys; novels use a metadata-only author guard that already self-repairs the real title-first mis-grouping on every resolution, so tracker healing would only add auto-splitting of manual merges (user-intentional). Gated even though novel tracking now ships. (The `NovelMergeManager` "tracking is deferred" docstring is stale; the decision holds.)
- **Saved searches** (browse filter presets) - low value; the DB + serializer layer survives on `design/library-compose`. The 2026-07-04 Komikku parity audit rates it the top browse gap, but the "low value" call stands unless reopened.
- **Per-source Feed** (latest / popular / saved-search rows as a source home) - depends on saved searches (parked above); parked together.
- **Restore-path onboarding** - the restore log already lists what couldn't reinstall. See [novel-backup.md](docs/dev/plans/novel-backup.md).
- **Auto-refresh-metadata toggle for novels** - no-op; novels return metadata + chapters in one call.
- **Dynamic launcher shortcuts** - cosmetic; Mihon ships a static `shortcuts.xml`.
- **Force side-nav rail, DOKI theme, in-app app-icon changer** - dropped (icon changer revivable once branded icon assets exist).
- **Drag-sort library, staggered grid, stats drill-down, EPUB export** - out of scope / out of plan.
- **Further adult-source wrappers** - the remaining candidate sites either need a base extension written first or expose too little structured metadata to justify a wrapper. Specifics in [adult-sources.md](docs/adult-sources.md).
- **isLewd metadata-id rewire** - the name/genre heuristic already recognizes the common adult sources; the delegated-id sets have no other consumer.
- **Backup source-ID remapper** - not needed; the built-in adult sources already register under every stock-extension id.
- **EH smart-search merge** (pick source, auto-find match, merge) - the pref-based merge already covers this; revive only for auto-match-on-source-pick.
- **Source image-compression proxy** `[M]` - the SY/Komikku `DataSaver` image resize/compress proxy, not a Mihon built-in; revive for cellular data-saving.
- **EXH developer tooling** - file logs, debug overlay, hidden debug menu; Mihon's logcat suffices, revive for deep on-device EXH debugging.
