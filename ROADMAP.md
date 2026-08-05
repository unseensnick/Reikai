# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## Now

- **Content layer architecture (manga/novel unification, deep seam)** `[XL]` - one Reikai-owned shared behavior + UI layer over a neutral `Entry` vocabulary with thin per-type adapters, extending the shipped Entry* UI-leaf seam down into ScreenModel behavior. Remaining: the reader migration; global search stays excluded. [Plan](docs/dev/plans/content-layer-architecture.md).

## Next

- **Fifth whole-system audit of the migrate and merge surfaces** `[M]` - a repeat end-to-end pass with round 4's fixes in place, not scoped to any diff, because every prior round found its defects inside the previous round's fixes. Realistic user paths only; findings map to structural fixes, not per-finding patches. Standing rules in [content-layer-migrate-surface.md](docs/dev/plans/content-layer-migrate-surface.md).
- **Aggregate a merge group over its library members only** `[M]` - only one of five unfavorite paths splits before it unfavorites, so a group can hold entries no longer in the library and both the chapter aggregation and the merged-unread badge SQL count them. Owner-ruled preserve-the-group-and-fix-the-reads; blocked on the tracker hand-out decision, and the badge SQL rides with it rather than being fixable alone.
- **Fix two Statistics miscounts** `[S]` - novel downloads never reach the Downloaded stat, and merged series count once per source instead of once (so the title count reads higher than the library). Both promised publicly in `unseensnick/Reikai#56`; detail in the 2026-07-23 stats-fixes audit note (local).
- **Unify the download subsystem across manga and novels (Road B)** `[L]` - collapse the parallel novel download cache/provider into one shared disk-scan layer serving both types, so they can't drift (Tsundoku's single-subsystem model). A code merge, not a data migration; touches Mihon's download files (`// RK`), sequenced within the content-layer program. [Plan](docs/dev/plans/content-layer-architecture.md).

## Later

Backlog, grouped by area. Unordered within an area.

### Novels (manga <-> novel parity)

Remaining manga/novel parity work, smaller enhancements and polish:

- **Skeleton loading on the novel details page** `[S]` - placeholder skeletons while the first load resolves (like LNReader), instead of a bare spinner when opening a non-library novel. An enhancement, not a parity gap (manga also uses a plain spinner).
- **Smart update (auto fetch-interval) for novels** `[M]` - give novels manga's per-entry update-interval prediction (the details "next update" action-row button plus a Set-interval dialog), so the novel action row matches manga's and Share can move to the overflow. Needs a `fetch_interval` / `next_update` schema migration on novels, the `FetchInterval` algorithm re-typed onto novel chapters, and the novel update job honouring it (algorithm reference: Mihon / tsundoku `FetchInterval`).

- **Novel update notification actions** `[S]` - a finished novel update offers no shade actions, while the manga one offers Mark as read, View chapters and Download. Novels support all three, so this is a straight level-up of the novel notifier's result notifications.

Opportunistic polish:
- Browse: Latest shortcut, hide-in-library, per-row language, genre-tap-search.
- Tracking: start-date backfill, friendlier Fill-from-tracker errors (no-entry-found on a 404 + null-message fallback).
- Updates / history: fast-scroll animation.
- Details: per-source scanlator filter for merged novels, novel tag-tap global search.

### Library

One library screen lists manga and novels together; what is left here is polish and performance on top of it.

- **Category reorder mode (both types)** `[S]` - a reorder mode on the edit-categories screen (a drag handle plus move-to-top / move-to-bottom per card, confirm or cancel), built once on the shared category screen model so it serves manga and novels. Uses the existing `sort` column, no schema work. [Plan](docs/dev/plans/category-schema-unification.md).
- **Denormalize the library count columns, measured first** `[M]` - both library views aggregate unread / read / total / bookmark counts per rebuild (`count(*)` and `sum(read)` in `libraryView.sq` and `novelLibraryView.sq`), and tsundoku instead maintains them as columns via triggers. This is not a novel parity gap: the two types aggregate identically. Treat it as a shared performance change that touches a Mihon view (so a `.sqm`, a `versionCode` bump and a sync liability), and measure that the aggregation is actually the cost before building it. Related: the library-jank memory; the per-swipe full reassembly (one candidate cause) was fixed 2026-08-02, so re-measure before building this.

### Details

From the 2026-07-04 Komikku parity audit (missing features + gestures on the details screen).

- **Header long-press menus + tap-source-to-browse** `[M]` - long-press the title / author / source for library search, global search and copy (today it only copies); tap the source name to open its browse. Flagship parity gap.
- **Per-chapter source label on merged entries** `[M]` - show which source each chapter came from in a merged series.
- **Details overflow polish** `[S]` - per-entry disable-auto-update, clear-data (downloads + cached chapters), open folder, jump to source settings.
- **AMOLED-aware adult tag-chip borders** `[S]` - weighted / pure-black-dark-mode borders on the adult gallery-info tag chips (copying metadata already works via the metadata viewer).

### Browse & sources

From the same audit.

- **Find-a-source search box** `[M]` - filter the sources list by name or extension when you have many.
- **Custom source categories** `[M]` - group installed sources under your own headers (assign each source to one or more categories) in the Sources list, beyond the default language grouping. Needs source-category storage.
- **Source-list & row polish** `[S]` - row badges (language flag / NSFW / extension name), a browse-toolbar incognito toggle, an NSFW-only filter, per-source data-saver exclude, a browse panorama toggle (the library already has panorama), hide latest / pin.

### Updates & history

- **Combined "Recents" view (grouped recent activity)** `[L]` - one title-centric feed merging recently read, recently updated and newly added, behind a preference that collapses the Updates and History tabs into a single Recents tab with modes inside it (off keeps today's two tabs). Per-title collapsing already ships as the Updates "Group by series" toggle, so the new parts are the combined feed, a newly-added query, and the conditional tab set; requested in `unseensnick/Reikai#57`, detail in the 2026-07-23 recents audit note (local).

### Reader

- **Finish merged read state outside the library** `[M]` - the deduplicated "read on any source" rule drives the library count, the details chapter list and the reader's chapter sheet, but reader next-chapter and history resume are still per-source, download-next targets the leading source, and bookmark plus downloaded state show whichever copy won the dedup. Also excludes gallery sources from the cross-source match, which currently can spread a read mark between unrelated galleries. [Plan](docs/dev/plans/merged-read-state.md).
- **Seamless chapter transitions in the novel reader (Option 1)** `[M]` - port tsundoku's infinite-scroll idea onto the current WebView reader: at a scroll threshold, append the prefetched next chapter behind a divider, track the chapter boundary (title / progress / mark-read / history / prefetch-next), and prune distant chapters. The manga webtoon reader has this; novels don't. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Tsundoku-based novel reader migration (Option 3)** `[XL]` - replace the bespoke WebView + LNReader-core.js novel reader with a native reader lifting tsundoku's `NovelViewer` text engine onto Reikai's existing novel domain via `ReaderChapter` / `Page` / `PageLoader` adapters (novel tables stay separate; merging novels into manga rows is ruled out on the `String` plugin-source-id cost). Native rendering, the full tsundoku feature set, a maintained upstream to sync from; recommended, deferred by choice, and starts with a migration-planning `/scout`. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Collapse the two reader settings sheets, and rework how the options are presented** `[M]` - manga and novels each carry their own sheet today, so every reader setting is added twice; collapsing them is also the moment to answer `unseensnick/Reikai#55` with grouping and progressive disclosure (the complaint is hunting for an option, not just sheet height). Sequencing against the content-layer reader phase is open. [Plan](docs/dev/plans/unified-reader.md).
- **Novel reader feature harvest from tsundoku** `[M]` - port tsundoku's viewer-agnostic reader extras: a content pipeline (user regex replacements, hide-chapter-title, force-lowercase, raw-HTML toggle), custom `file://` / `content://` fonts, and 4-way margins plus distinct paragraph spacing and indent. Portable to the current WebView reader now or a native reader later. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Native TTS with in-text highlight for novels** `[L]` - upgrade novel TTS to follow along in the text (per-chunk highlight) with clean cross-chapter handoff, matching tsundoku's `TtsController`; the current core.js TTS has no in-text follow. [Plan](docs/dev/plans/novel-reader-tsundoku.md).

### Novel sources & LN plugins

- **CustomNovelSource mirror mode (re-point a source at a mirror domain)** `[M]` - a custom-source layer that delegates to an already-installed extension or LN plugin while rewriting its base URL (tsundoku's `basedOnSourceId` + an OkHttp base-URL interceptor / `withSiteOverride`), to recover a source whose domain moved or died. Reikai has no way to re-point an installed source today.
- **Compiled-APK novel extensions (tsundoku / IReader repos)** `[XL]` - load the two APK novel-extension ecosystems alongside LN plugins: tsundoku's novel-extension type (a `tachiyomi.novelextension` feature flag on Mihon's extension format plus extra methods like `fetchPageText`) and IReader's extension repo. Requested in `unseensnick/Reikai#31`; starts with its own scout (the 2026-08-02 tsundoku source-system research is the groundwork).

### Downloads & updates

- **Novel download/update pacing controls** `[M]` - per-source throttle, update staggering, and a per-source override map for novel scrapers (tsundoku's `NovelDownloadPreferences`), a more complete anti-detection pacing layer than Reikai's current per-chapter backoff. Independent of Road B.

### Data & backup

- **Give backups a format version, and close the last novel sort-flag residue** `[S]` - `NovelRestorer.restoreCategories` still inserts a backup's category flags verbatim, so a category sorted by Downloaded or Tracker score before the category unification can restore as the other one (the global-sort preference residue closed itself when the key retired with a restore skip). Backups carry no format version, so a restorer cannot tell an untranslated old value from a correct new one; adding one is the prerequisite that makes it fixable. [Plan](docs/dev/plans/category-schema-unification.md).
- **Unify backup into one Entry-level emitter** `[M]` - collapse the twin manga/novel backup creators into one neutral emitter over `EntryId` so each backup field is written once for both content types, not by a per-type creator pair. Rides on the content-layer `EntryId` seam; the streamed create/validate/restore mechanism already lives once in the orchestrator, so this is the model-level half. [Plan](docs/dev/plans/content-layer-architecture.md).
- **Carry a merge group's source order and ranking override through a backup** `[S]` - a backup stores a group as an unordered set of `{url, source}` refs with no override flag, so a restored group comes back in ref order with the hand-set manage-sources ranking off. Ordering needs no format change (the creator reads the unordered membership map instead of the priority-ordered member query); the flag needs a new proto field on both group models. The restore side is already done: `materializeGroup` writes a group's members, order and flag in one operation, and the precedence question is settled (the backup is authoritative for the entries it names). Sequenced with the Entry-level emitter above, since both rework the same creators. [Plan](docs/dev/plans/merge-component-consolidation.md).

### Trackers

Dedicated LN trackers are shippable via WebView session-scraping (no official API needed), which overturns the earlier park for RanobeDB / NovelList.

- **Refresh a tracker's local row after a progress push** `[S]` - pushing progress from mark-as-read persists the row read *before* the push, so the local status still says "plan to read" until something refreshes it again, while the service already says reading. Affects both content types; upstream's shape, so it is a deliberate local fix.
- **WebView cookie/token tracker login** `[M]` - a shared WebView login flow that captures a service's session cookie or JWT (tsundoku's `TrackerWebViewLoginActivity`), the auth path all three novel trackers below need; Reikai today has only OAuth-deeplink and username/password login. Strip tsundoku's raw-cookie DEBUG logging on port.
- **RanobeDB tracker** `[M]` - a dedicated light-novel tracker (ranobedb.org): status, score, dates and delete, via a public JSON read API plus a reverse-engineered write path. Strongest of the three; port first.
- **NovelList tracker** `[M]` - novellist.co tracker: status, chapter progress and score via a JWT REST API (search needs no auth). Second.
- **NovelUpdates tracker** `[L]` - novelupdates.com tracker: highest demand but 100% HTML scraping plus a notes-field progress hack, no score or date sync; high ongoing maintenance. Port last or skip.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move Reikai's look off the stock Material 3 aesthetic (shape, typography, component styling, spacing, layout) across the shared `Entry*` surfaces, while keeping Mihon's existing theme system in Appearance settings intact: the user-selectable color themes, light/dark, AMOLED, and Theme-based-on-cover all stay, and the redesign renders under whichever the user picked. It owns component styling and layout through the `TachiyomiTheme` -> `MaterialExpressiveTheme` entry point, not the color-palette picker, and must preserve both the phone and tablet (two-pane) layouts Reikai inherited from Mihon. Seed tokens in `DESIGN.md` first (brand in `PRODUCT.md`: quiet, dense, deliberate). Exploratory. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked / not building

One line each; revive note where relevant.

- **Migrate off Voyager ScreenModel to AndroidX ViewModel** `[XL]` - the one unported upstream change (mihonapp/mihon#3594, mihon `c3b99aea0`); parked because it is unreleased and still crashes on open in its `private viewModel<T>()` screens, so porting now risks a large divergence against code upstream may revise. Revive once upstream ships it in a release and fixes its own copies (a fix is already in progress upstream). [Plan](docs/dev/plans/viewmodel-migration.md).
- **Rename the app package to `app.reikai`** `[M]` - drop the inherited `eu.kanade.tachiyomi` base and its suffixes for a clean `app.reikai` / `app.reikai.beta`, shipping one final old-package update whose startup screen walks people through backup and restore. Parked on install-base size (v0.3.0 has roughly 970 release-asset downloads): a hand migration silently strands anyone who does not open the app in time, against a purely cosmetic benefit, even though the shared `/storage/emulated/0/Reikai` folder means only custom covers would be lost (a copy-out step in the final build would close that; the old build's update check must also be frozen). Revive once telemetry reports a real active-user count and it comes back small.
- **Persisted merge-group reading-order table** `[M]` - a group-owned order table with a transactional recompute writer and validity fingerprint. Parked before Phase 3 of the merge rebuild: its original driver (the merge stitcher overwriting `sourceOrder`) is already fixed, reading order is recomputed in-memory cheaply, and cross-source resume is derivable from read-state. Revive if on-device use shows a real need (recompute cost, or a persisted order/scope the reader must page through). See [merge-system-rebuild.md](docs/dev/plans/merge-system-rebuild.md).
- **Merged "All" view can drop a manga chapter present in one source (watch item)** `[S]` - the novel cause is fixed; the manga variant is number-dedup dropping an unmatchable sibling or a false collision, both intended tradeoffs, so it needs a concrete repro to tell a real bug from working-as-designed. See [merge-system-rebuild.md](docs/dev/plans/merge-system-rebuild.md).
- **Library rebuilds in full on every download tick** `[S]` - neither download cache throttles its change signal, so each cache invalidation drives a whole library rebuild; throttling the tick where the library consumes it is the cheap fix. `LibraryScreenModel`'s favorites flow also recomputes the EXH tag + alt-title reads inside that same combine (hoisting them is riskier, it touches Mihon's core favorites flow). Revive if rebuild cost shows on device; the denormalized count columns under Library would cut the per-rebuild cost either way.
- **Cut the details screen's per-load-step recompositions (Compose-stability optimization)** `[M]` - the only lever for the `debugY2k` open-lag on huge (1000+) chapter lists: stop the ~6 per-open state emissions each recomposing the whole details screen. Parked because the lag is inherent debug-mode Compose, absent in release/preview, so the fix is uncertain stability work on the shared screen. Revive only if it also helps release; detail in the `reference_debug_details_lag_fold` memory.
- **Full two-way EH favorites sync** (pull account -> library) - the only feature that would mutate the library from a remote source; the scoped one-way backup shipped instead. Revive only if account -> library mirroring is wanted; the **EH per-page add-path throttle** `[S]` bundles here (redundant with the shipped 3/sec rate limit, which only this feature's sustained walk would exercise).
- **Manga per-page chapter loading** - no manga source would feed a paged chapter list (the contract returns the full list in one call).
- **Auto-error a chapter stuck mid-download** `[S]` - a per-chapter stall timeout so a hung image download gives up faster than `callTimeout` x3 (~8 min worst case). Parked: the pause/resume fix covers the reported bug and stalls still self-resolve via `callTimeout`. Revive if a permanent stall (callTimeout never fires) turns up.
- **Per-chapter control in the download queue (expandable cards)** `[M]` - the unified queue collapsed to one card per series (drag / move-to-top / move-to-bottom / cancel act on the whole series), dropping per-chapter reorder + per-chapter cancel from the global queue. Parked: series-level control covers the real cases and per-chapter selection lives on the details screen. Revive by expanding a card to its chapters on tap; Mihon's per-chapter manga queue files (`DownloadAdapter` / `DownloadHolder` / the `download_single` menu) are still in the tree, so it is mostly wiring plus a novel equivalent.
- **Refresh trackers as part of the library update** `[S]` - the manual "Refresh tracker data" action shipped; folding it into the scheduled library update was deliberately not done. A chapter update runs per source on a schedule, while this is one network call per bound tracker per entry against rate-limited services, so attaching it would multiply every update's remote traffic invisibly. Revive if the manual action turns out to be something you run often enough to want automated, ideally behind its own preference and its own interval.
- **Hardcover / MiraiList trackers** - still no sanctioned read+write API; recheck Hardcover if it leaves beta. (RanobeDB / NovelList / NovelUpdates moved to Later -> Trackers: shippable via WebView session-scraping.) See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel** - now feasible (trackers shipped) as an `[M]`; the source-native path stays infeasible (no plugin `getRelated`). Reconsider if wanted.
- **On-device novel translation (translation-engine ecosystem)** - tsundoku's pluggable translate stack (LibreTranslate / OpenAI / local Ollama / DeepL / Gemini / a custom-HTTP engine, plus a translate-on-download hook). Cool and possibly useful, but uncertain whether it gets real use; low priority, deliberately kept off the active backlog for now. Revive if on-device / AI novel translation is wanted.
- **Batch recommendation search** - overlaps the existing taste-profile layer. Revive if manual multi-title discovery is wanted.
- **CMK source-native recommendations (+ id-graph)** - stock CMK was pulled from the extension repos, so the recs port's id-set gate never fires (only clones with different ids remain). Revive if a first-party CMK source returns; the id-graph idea (suggest tracker binds from an entry's cross-links) rides the same API.
- **MD source-native similarity carousel** - its only data source (`api.similarmanga.com`, the TF-IDF `similar-manga` project) is frozen at 2025-05-27 and unmaintained; MD's official `/relation` endpoint returns exact relations (doujinshi / colored), not discovery, and tracker recs already cover popular titles. Dropped with the MD enhanced source (0.2.0); see [md-enhanced-source.md](docs/dev/plans/md-enhanced-source.md).
- **Serialize track-sheet edits (rapid edits clobber each other)** `[M]` - each field edit runs in its own coroutine (`EntryTrackInfoDialog.kt`, the shared manga+novel dialog), so two quick edits race on the same track row and the second wins. A Mihon-wide race, worst on MDList, now applying to both content types. Parked because the per-track mutex touches shared tracker code; revive standalone.
- **Content-type binary fetch for LN plugins** - auto-detecting a binary response and base64-transporting it would let a plugin read true binary bytes from a normal `fetch`, but it risks garbling a mislabeled non-UTF-8 (GBK / Shift-JIS) text source, and no current plugin fetches raw binary (they decode base64 / hex text via the shipped `Buffer` shim). Revive with an explicit opt-in binary mode if a real binary-fetch source appears. The rest of the LN host hardening (`Response.arrayBuffer()`, `Buffer`, `Blob`, `X-XSRF-TOKEN`, real `setTimeout` delays) shipped in 0.3.0.
- **No-code custom novel source (CSS-selector wizard)** `[L]` - add a whole novel site from JSON config with a per-step test-probe wizard, no plugin authoring (tsundoku's `CustomNovelSource` + `CustomSourceManager.testSource`, ~4000 lines with its editor screens). Parked 2026-08-02: large, orthogonal to the content-layer program, and unproven demand. Revive if hand-adding sites the plugin ecosystems lack becomes a real ask.
- **Upcoming / release calendar for novels** - LN sources rarely expose a reliable cadence; stays manga-only.
- **Hide the novel browse Latest chip** - considered gating it off like manga's `supportsLatest`, but the LN plugin API exposes no per-source latest capability to gate on (`showLatestNovels` is a runtime flag plugins honor or ignore, and LNReader itself shows Latest unconditionally). A plugin that ignores the flag returns Popular's list rather than an empty page, so the symptom is harmless. Every build option is poor (runtime probe, curated allow-list, patched-plugin flag); kept as-is.
- **Bulk novel-migration search tuning** (deep search, prioritize-by-chapters) `[M]` - partially superseded by the unified migration flow ([plan](docs/dev/plans/content-layer-migrate-surface.md)): its shared tuning sheet gives novels extra-query and the hide toggles; only the smart-matching half (deep search, prioritize-by-chapters) stays gated, since it runs on the manga smart-search engine. Revive by leveling a novel search engine up if matching gets painful.
- **Tune the auto-webtoon source-name catch-alls** `[S]` - the classifier takes Komikku's token lists verbatim, and only the two generic catch-alls are worth touching (they force webtoon mode on mixed-content aggregators and at least one page-format western comic host). Parked because the auto-pick is computed fresh each open and never stored, so identical token lists are exactly what makes a library read the same in Reikai and Komikku, and tuning them desyncs that. Revive if a real false positive annoys more than the divergence would.
- **Cross-app preference-key compatibility with Komikku** `[M]` - Reikai shares none of Komikku's sixteen `eh_*` preference keys, so a backup restored in either direction silently drops EH logins, saved searches and the rest of the adult-source settings (preferences back up by raw key, so adopting the upstream names would close the gap). Parked because renaming live keys resets existing users' settings without a migration, and it is unproven how many people move between the two apps. Revive if switching apps should be seamless.
- **Tracker-based merge-group healing for novels** - manga splits mis-grouped merge members by comparing tracker keys, but the merge rebuild made membership explicit and persisted, so nothing auto-groups a novel for healing to correct, and healing would only auto-split a group the user chose. The author guard that once re-evaluated on every resolution is retired to the one-time pref-to-group migration. See [merge-system-rebuild.md](docs/dev/plans/merge-system-rebuild.md).
- **Saved searches** (browse filter presets) - low value; the DB + serializer layer survives on `design/library-compose`. The 2026-07-04 Komikku parity audit rates it the top browse gap, but the "low value" call stands unless reopened. Requested as part of the Feed tab in `unseensnick/Reikai#54`; see [browse-feed-tab.md](docs/dev/plans/browse-feed-tab.md).
- **Per-source Feed and global Feed tab** (latest / popular / saved-search rows as a browse surface) - depends on saved searches (parked above); parked together. Staging and port scope in [browse-feed-tab.md](docs/dev/plans/browse-feed-tab.md).
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
