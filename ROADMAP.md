# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/workflow.md](.claude/rules/workflow.md) ("Roadmap & plans").

## Now

**Unified content UI** on `feat/unified-content-ui` (unmerged): the History + Updates rows, the full-cover dialog, and the **details screen** (shared action row, info header, phone + tablet shell, toolbar, manga hide/unhide-chapters) are collapsed onto shared `Entry*` components. **P6 shipped** the shared edit-info editor + a non-destructive custom-info overlay for BOTH manga and novels: edits show across details + library + updates + history (display-only, so search/sort/grouping/merge use the source values), novels moved off their destructive in-row model onto a `custom_novel_info` overlay, the editor is themed from the cover, and its **Fill-from-tracker** button autofills title/author/artist/cover/description + genres from a bound tracker (8 trackers; a picker when several are bound). Verified device + minified. Detail: [the plan](docs/dev/plans/unified-content-ui.md); goal + rulings: Later -> UI & design.

## Next

After P6, a full-surface parity audit (2026-07-09) is closing the remaining manga/novel gaps in phases. Shipped on the branch: the silent-bug sweep (novel migration flags, failed-download notification, Updates delete-confirm, browse paging retry, sources Filter + Last-used) and the library tracker dimensions (novel filter / sort / group by tracker, plus a manga download-count sort). Next: duplicate-detection on novel add, then novel-browse bulk multi-select, then the `[S]` quick wins under Later -> Novels. Phase-by-phase status lives in `Handoff.md`.

- **Skip hidden chapters in the manga reader** `[M]` - once manga can hide/unhide chapters (the current details phase), the reader's next/previous still walks the full list from the database, so reading forward can still land on a hidden chapter. Make the manga reader honor the hidden set. Needs its own scout: the reader is the View-based holdout, separate from the Compose details screen. (The novel reader already skips hidden, since it navigates the filtered list.)
- **Independent library scroll position per content type** `[S]` - the manga and novel library views share one scroll offset (scrolling one moves the other); each should keep its own remembered scroll state. Reikai-specific (upstream is manga-only), so the fix is entirely on our side.

## Later

Backlog, grouped by area. Unordered within an area.

### Novels (manga <-> novel parity)

Ready to build (infrastructure exists):
- **Duplicate detection when adding a novel** `[S]` - wire `getDuplicateLibraryNovel` + `DuplicateNovelDialog` into the details / history add paths.
- **Categorized-display correctness for novels** `[S]` - per-category sort ignores the global toggle and never resets; branch `setSort` + a novel `ResetCategoryFlags`.
- **Mark same-numbered duplicate chapters read on novel completion** `[S]` - parity for merged novels.
- **Novel download recognition + readable folders** `[M]` - re-key novel downloads by stable names (source / title / chapter, like manga) with a disk scan so they survive reinstall / restore / storage-move, instead of unstable numeric ids plus a wiped DB flag (found on-device: after a reinstall the app no longer sees existing downloads, so re-download is a silent no-op). Fixes the opaque numeric download folders too; needs a one-time migration.
- **Novel updates refresh feedback** `[S]` - started / already-running snackbar plus a last-updated line (manga shows both; novel refresh is silent).
- **Tracker-based merge-group healing for novels** `[S-M]` - port `computeHealing` to `NovelMergeManager`.
- **Skeleton loading on the novel details page** `[S]` - show placeholder skeletons while the first load resolves (like LNReader), instead of a bare spinner when opening a new non-library novel.

Larger:
- **Global novel reader-defaults settings screen** `[M]` - a `SearchableSettings` novel-reader page the per-novel sheet falls back to; also unblocks settings search.
- **Novel library Behaviour settings** `[M]` - swipe actions + missing-chapter indicators for the novel list.
- **Bulk multi-select in novel browse + global search** `[M]` - a selection toolbar with bulk add-to-library, matching manga (which uses `BulkFavoriteScreenModel`); novel browse only single-adds today (2026-07-07 parity audit).

Opportunistic polish: Browse (Latest shortcut, Latest capability-guard so an unsupported source hides the chip, global-search progress, Last-used, hide-in-library, per-row language, genre-tap-search); Reader (Share + open-in-browser, always-on progress %); Downloads queue (pause/resume, per-row retry, move-to-top/bottom, per-series move/cancel); Tracking (start-date backfill, create-private-at-bind, hide trackers lacking novel search, friendlier Fill-from-tracker errors: no-entry-found on a 404 + null-message fallback); Updates/history (Novels-chip last-updated line, fast-scroll animation); Details (long-press-copy WebView URL, per-source scanlator filter for merged novels, per-category novel display settings `[M]`, novel tag-tap global search, novel long-press-favorite category shortcut).

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

- **Auto Webtoon Mode (smart reading-mode detection)** `[S]` - when a series has no manual reading-mode set, auto-pick Webtoon for long-strip content (manhwa / manhua / webtoon) from its genre tags, plus generic name catch-alls and a short list of currently-live long-strip sources; everything else stays on the global default, and a manual per-series choice always wins. Port of Komikku's metadata-driven auto-webtoon (genre + source-name classification, not image/aspect-ratio detection). Default off, one Settings -> Reader toggle; manga-only (novels have no paged/long-strip axis). Net-new `MangaType` heuristic + a `// RK` branch in `getMangaReadingMode()`. Liveness of the source list checked via byparr (July 2026). From `unseensnick/Reikai#40`.

### UI & design

- **Unified content UI + design refresh** `[L]` - collapse the three near-duplicate presentation stacks (manga, novels, adult) into one Reikai-owned pixel layer over a content-agnostic UI model. The main goal is manga↔novel feature parity and anti-divergence (a UI change to one type reaches the other), with de-duplication the mechanism; it also gives one place to move off stock Material 3. Domain models and ScreenModels stay per-type; readers stay separate. History + Updates rows and the cover dialog shipped; the details screen is next (the large one). Small parity closes surfaced by the 2026-07-07 audit are folded into each surface as it is collapsed; the larger parity items are the standalone entries under Novels and Details above. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked / not building

One line each; revive note where relevant.

- **Full two-way EH favorites sync** (pull account -> library) - the only feature that would mutate the library from a remote source; the scoped one-way backup shipped instead. Revive only if account -> library mirroring is wanted.
  - **EH per-page add-path throttle** `[S]` bundles here - redundant with the shipped 3/sec rate limit for normal imports; only this feature's sustained walk exercises it.
- **Manga per-page chapter loading** - no manga source would feed a paged chapter list (the contract returns the full list in one call).
- **Auto-error a chapter stuck mid-download** `[S]` - a per-chapter stall timeout so a hung image download gives up faster than `callTimeout` x3 (~8 min worst case). Parked: the pause/resume fix covers the reported bug and stalls still self-resolve via `callTimeout`. Revive if a permanent stall (callTimeout never fires) turns up.
- **Dedicated LN trackers** (NovelUpdates / MiraiList / RanobeDB / Hardcover) - no sanctioned read+write API as of June 2026; recheck Hardcover if it leaves beta. See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel** - now feasible (trackers shipped) as an `[M]`; the source-native path stays infeasible (no plugin `getRelated`). Reconsider if wanted.
- **Batch recommendation search** - overlaps the existing taste-profile layer. Revive if manual multi-title discovery is wanted.
- **CMK source-native recommendations (+ id-graph)** - stock CMK was pulled from the extension repos, so the recs port's id-set gate never fires (only clones with different ids remain). Revive if a first-party CMK source returns; the id-graph idea (suggest tracker binds from an entry's cross-links) rides the same API.
- **MD source-native similarity carousel** - its only data source (`api.similarmanga.com`, the TF-IDF `similar-manga` project) is frozen at 2025-05-27 and unmaintained; MD's official `/relation` endpoint returns exact relations (doujinshi / colored), not discovery, and tracker recs already cover popular titles. Dropped with the MD enhanced source (0.2.0); see [md-enhanced-source.md](docs/dev/plans/md-enhanced-source.md).
- **Serialize track-sheet edits (rapid edits clobber each other)** `[M]` - each field edit runs in its own coroutine (`TrackInfoDialog.kt`), so two quick edits (e.g. chapter then score) race on the same track row and the second wins, losing the first. A Mihon-wide race, worst on MDList. Parked: the per-track mutex fix touches shared tracker code, so it needs its own scoped pass. Revive standalone.
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
