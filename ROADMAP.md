# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/workflow.md](.claude/rules/workflow.md) ("Roadmap & plans").

## Now

- **Adult-source parity round 2** `[L]` - shipped on branch `feat/exh-parity`, held for PR. Brings the adult-source subsystem to Komikku parity (import entry points, built-in sources, browse parity, rich details, page previews, update notification, badges, merged-group refresh). Parity is complete; the branch just needs to merge.

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

- **MangaUpdates similar-titles** `[S]` - also read the `category_recommendations` bucket, not just the human one; no UI change.
- **Comick source-native recommendations** `[S-M]` - feed Comick's own recs API into the carousel; restore the Comick id set + port `ComickPagingSource`. Self-contained.
- **MangaDex source-native similarity** `[L, gated]` - use MangaDex's `/manga/{id}/related` graph. A consumer of the MangaDex enhanced-source initiative below; lands when that does.

### MangaDex enhanced source

- **MangaDex enhanced source** `[L]` - port the `exh/md` subsystem: a `DelegatedHttpSource` wrapping the installed MangaDex extension with OAuth login, follows sync, the MDList tracker, a Settings hub, and the metadata model + info adapter. A general MangaDex feature (under `exh/` for historical reasons), so its own branch + plan doc. Unblocks the MangaDex similarity carousel (above) and the adult tag-chip MangaDex branch. Wanted: MangaDex is an actively-used source.

### Adult sources

- **Merged gallery sources drop chapters in the unified list** `[S-M]` - `ChapterAggregation` dedups by chapter number, so gallery sources (each numbering its primary chapter 1) collapse to one; scope it to skip gallery / EH-based sources.

## Parked / not building

One line each; revive note where relevant.

- **Full two-way EH favorites sync** (pull account -> library) - the only feature that would mutate the library from a remote source; the scoped one-way backup shipped instead. Revive only if account -> library mirroring is wanted.
  - **EH per-page add-path throttle** `[S]` bundles here - redundant with the shipped 3/sec rate limit for normal imports; only this feature's sustained walk exercises it.
- **Manga per-page chapter loading** - no manga source would feed a paged chapter list (the contract returns the full list in one call).
- **Dedicated LN trackers** (NovelUpdates / MiraiList / RanobeDB / Hardcover) - no sanctioned read+write API as of June 2026; recheck Hardcover if it leaves beta. See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel** - now feasible (trackers shipped) as an `[M]`; the source-native path stays infeasible (no plugin `getRelated`). Reconsider if wanted.
- **Batch recommendation search** - overlaps the existing taste-profile layer. Revive if manual multi-title discovery is wanted.
- **Upcoming / release calendar for novels** - LN sources rarely expose a reliable cadence; stays manga-only.
- **Novel sources enable/disable filter screen** - add a bulk-toggle screen if managing many LN sources gets painful.
- **Novel missing-chapter gap separators** - novel numbers are title-recognition-derived and source-order-sorted, so computed gaps would be mostly false.
- **Saved searches** (browse filter presets) - low value; the DB + serializer layer survives on `design/library-compose`.
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
