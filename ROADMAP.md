# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/workflow.md](.claude/rules/workflow.md) ("Roadmap & plans").

## Now

- **MD enhanced source** `[L]` (in progress, branch `feat/enhanced-source`) - port the `exh/md` subsystem to wrap the installed MD extension. Phases 0-2 shipped and on-device verified (delegated wrap + metadata-enriched details + the reference-matched gallery-info card: author, status, description, star rating, namespaced Demographic / Content Rating / Tags). Remaining: OAuth + MDList tracker (3), follows sync (4), settings hub (5), similar + aggregators (6). [plan](docs/dev/plans/md-enhanced-source.md).

## Next

Nothing queued. The `[S]` items under Later -> Novels are the best promote candidates.

## Later

Backlog, grouped by area. Unordered within an area.

### Novels (manga <-> novel parity)

Ready to build (infrastructure exists):
- **Duplicate detection when adding a novel** `[S]` - wire `getDuplicateLibraryNovel` + `DuplicateNovelDialog` into the details / history add paths.
- **Novel migration carry-flags** `[S]` - remove-downloads flag + carry `viewerFlags` / `chapterFlags` to the target.
- **Categorized-display correctness for novels** `[S]` - per-category sort ignores the global toggle and never resets; branch `setSort` + a novel `ResetCategoryFlags`.
- **Expose novel tracking in library filter / sort / group** `[S]` - tracker-status filter, tracker-score sort, group-by-track-status.
- **Mark same-numbered duplicate chapters read on novel completion** `[S]` - parity for merged novels.
- **Failed novel download error notification** `[S]` - mirror `DownloadNotifier.onError` in `NovelDownloadNotifier`.
- **Novel updates refresh polish** `[S]` - started / already-running snackbar; update-row cover opens details.
- **Tracker-based merge-group healing for novels** `[S-M]` - port `computeHealing` to `NovelMergeManager`.

Larger:
- **Global novel reader-defaults settings screen** `[M]` - a `SearchableSettings` novel-reader page the per-novel sheet falls back to; also unblocks settings search.
- **Novel library Behaviour settings** `[M]` - swipe actions + missing-chapter indicators for the novel list.

Opportunistic polish: Browse (Latest shortcut, global-search progress, Last-used, hide-in-library, per-row language, genre-tap-search); Reader (Share + open-in-browser, always-on progress %); Downloads queue (pause/resume, per-row retry, move-to-top/bottom, per-series move/cancel); Tracking (start-date backfill, create-private-at-bind, hide trackers lacking novel search); Updates/history (Novels-chip last-updated line, fast-scroll animation); Details (long-press-copy WebView URL, per-source scanlator filter for merged novels, per-category novel display settings `[M]`).

### Recommendations

- **MD source-native similarity** `[L, gated]` - use MD's `/manga/{id}/related` graph. A consumer of the MD enhanced source (in Now); lands with its Phase 6 (similar + aggregators).

MangaUpdates similar-titles shipped (see CHANGELOG `[Unreleased]`); CMK source-native recs is blocked (see Parked).

### Details

From the 2026-07-04 Komikku parity audit (missing features + gestures on the details screen).

- **Header long-press menus + tap-source-to-browse** `[M]` - long-press the title / author / source for library search, global search and copy (today it only copies); tap the source name to open its browse. Flagship parity gap.
- **Edit entry info** `[L]` - a local editor for title / author / description / tags / status / cover (a TachiyomiSY/Komikku feature; base Mihon has custom-cover editing only).
- **Per-chapter source label on merged entries** `[M]` - show which source each chapter came from in a merged series.
- **Per-group Preferred-sources override** `[S-M]` - override the global Preferred-sources ranking for a single merge group (decides which source's version of shared-numbered chapters wins), via a reorder in Manage sources. Low priority; `ChapterAggregation.aggregate` already takes the ranking as a parameter, so only a per-group order pref plus the reorder UI are new. The global ranking still covers the common case.
- **Details overflow polish** `[S]` - per-entry disable-auto-update, clear-data (downloads + cached chapters), open folder, jump to source settings.
- **AMOLED-aware adult tag-chip borders** `[S]` - weighted / pure-black-dark-mode borders on the adult gallery-info tag chips (copying metadata already works via the metadata viewer).

### Browse & sources

From the same audit.

- **Find-a-source search box** `[M]` - filter the sources list by name or extension when you have many.
- **Custom source categories** `[M]` - group installed sources under your own headers (assign each source to one or more categories) in the Sources list, beyond the default language grouping. Needs source-category storage.
- **Source-list & row polish** `[S]` - row badges (language flag / NSFW / extension name), a browse-toolbar incognito toggle, an NSFW-only filter, per-source data-saver exclude, a browse panorama toggle (the library already has panorama), hide latest / pin.

### UI & design

- **Unified content UI + design refresh** `[L]` - collapse the three near-duplicate presentation stacks (manga, novels, adult) into one Reikai-owned pixel layer over a content-agnostic UI model, killing the manga↔novel duplication and giving one place to move off stock Material 3. Domain models and ScreenModels stay per-type; readers stay separate. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked / not building

One line each; revive note where relevant.

- **Full two-way EH favorites sync** (pull account -> library) - the only feature that would mutate the library from a remote source; the scoped one-way backup shipped instead. Revive only if account -> library mirroring is wanted.
  - **EH per-page add-path throttle** `[S]` bundles here - redundant with the shipped 3/sec rate limit for normal imports; only this feature's sustained walk exercises it.
- **Manga per-page chapter loading** - no manga source would feed a paged chapter list (the contract returns the full list in one call).
- **Dedicated LN trackers** (NovelUpdates / MiraiList / RanobeDB / Hardcover) - no sanctioned read+write API as of June 2026; recheck Hardcover if it leaves beta. See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel** - now feasible (trackers shipped) as an `[M]`; the source-native path stays infeasible (no plugin `getRelated`). Reconsider if wanted.
- **Batch recommendation search** - overlaps the existing taste-profile layer. Revive if manual multi-title discovery is wanted.
- **CMK source-native recommendations (+ id-graph)** - the upstream recs port targets CMK's own API, but stock CMK was pulled from the extension repos; only clones with different APIs and source ids remain, so the id-set gate never fires. Revive if a stable first-party CMK source returns; the id-graph idea (auto-bind trackers from the entry's cross-links, as a "suggested" not silent binding) rides the same API.
- **Upcoming / release calendar for novels** - LN sources rarely expose a reliable cadence; stays manga-only.
- **Novel sources enable/disable filter screen** - add a bulk-toggle screen if managing many LN sources gets painful.
- **Novel missing-chapter gap separators** - novel numbers are title-recognition-derived and source-order-sorted, so computed gaps would be mostly false.
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
