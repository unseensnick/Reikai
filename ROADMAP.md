# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/workflow.md](.claude/rules/workflow.md) ("Roadmap & plans").

## Now

- **Unify the download subsystem across manga and novels (Road B)** `[L]` - collapse the parallel novel download cache/provider into one shared disk-scan layer serving both types, keyed on shared primitives, so they can't drift (Tsundoku's single-subsystem model). The novel download re-key deliberately mirrored the manga scheme + cache shape so this is a code merge, not a data migration. Touches Mihon's shipped download files (`// RK`).

## Next

- The reader **tsundoku track** (seamless novel-reader transitions, later the native-reader migration); detail under Later -> Reader.

## Later

Backlog, grouped by area. Unordered within an area.

### Novels (manga <-> novel parity)

Remaining manga/novel parity work, smaller enhancements and polish:

- **Skeleton loading on the novel details page** `[S]` - placeholder skeletons while the first load resolves (like LNReader), instead of a bare spinner when opening a non-library novel. An enhancement, not a parity gap (manga also uses a plain spinner).

Opportunistic polish:
- Browse: Latest shortcut, Last-used, hide-in-library, per-row language, genre-tap-search.
- Tracking: start-date backfill, friendlier Fill-from-tracker errors (no-entry-found on a 404 + null-message fallback).
- Updates / history: fast-scroll animation.
- Details: long-press-copy WebView URL, per-source scanlator filter for merged novels, novel tag-tap global search, novel long-press-favorite category shortcut.

### Library

Novels are a first-class library type, but the library tab is still a binary Manga/Novels toggle.

- **"All" library chip (interleave manga + novels)** `[M]` - add an All option to the library content-type chip that shows both types in one view, beside the current Manga and Novels tabs. The item seam already exists (both models emit `List<LibraryItem>`, novels disguised as negative-id items, and the shared grid routes taps + selection by id sign), so All is a merge of the two `getItemsForCategory` outputs at `LibraryTab`'s `active*` fall-through, needing no new shared composable and no domain merge. Three pieces sit above the seam: (1) a unified category axis across the forked manga/novel category namespaces (the category-schema unification below), (2) a cross-type sort key surfaced onto `LibraryItem` so a merged list can be re-ordered (each model sorts its own half with a different comparator today), and (3) per-item action dispatch by id sign, replacing the whole-screen `isNovels` switch in the bottom bar, dialogs, continue-reading and refresh routing.
- **Unify the category schema (content_type discriminator)** `[M-L]` - retire the forked `novel_categories` table into the shared `categories` table with a `content_type` column (manga / novel / universal type-0), tsundoku's model adapted to Reikai's separate entry tables (the two join tables stay). Enables the tabbed All view and a coherent shared category structure, and drops a parallel repo/model stack. Migration must preserve the novel-order encoding.
- **Trigger-maintained library count columns for novels** `[M]` - denormalize unread / download / total counts onto the novel row via chapter and history triggers (tsundoku's `mangas.sq` pattern), so library filter and sort read them directly instead of aggregating on every rebuild.

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
- **Tsundoku-based novel reader migration (Option 3)** `[XL]` - its own branch, later: replace the bespoke WebView + LNReader-core.js novel reader with a native reader that lifts tsundoku's `NovelViewer` text engine onto Reikai's existing novel domain via `ReaderChapter` / `Page` / `PageLoader` adapters. Merging novels into manga rows is ruled out (the `String` plugin-source-id cost): keep the separate novel tables and feed the shared reader-side types from `NovelChapter`. Native rendering, the full tsundoku feature set, a maintained upstream to sync from. Accepts the View-based reader for novels; recommended, deferred by choice. Start with a migration-planning `/scout`. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Novel reader feature harvest from tsundoku** `[M]` - port tsundoku's viewer-agnostic reader extras: a content pipeline (user regex replacements, hide-chapter-title, force-lowercase, raw-HTML toggle), custom `file://` / `content://` fonts, and 4-way margins plus distinct paragraph spacing and indent. Portable to the current WebView reader now or a native reader later. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Native TTS with in-text highlight for novels** `[L]` - upgrade novel TTS to follow along in the text (per-chunk highlight) with clean cross-chapter handoff, matching tsundoku's `TtsController`; the current core.js TTS has no in-text follow. [Plan](docs/dev/plans/novel-reader-tsundoku.md).
- **Auto Webtoon Mode (smart reading-mode detection)** `[S]` - when a series has no manual reading-mode set, auto-pick Webtoon for long-strip content (manhwa / manhua / webtoon) from its genre tags, plus generic name catch-alls and a short list of currently-live long-strip sources; everything else stays on the global default, and a manual per-series choice always wins. Port of Komikku's metadata-driven auto-webtoon (genre + source-name classification, not image/aspect-ratio detection). Default off, one Settings -> Reader toggle; manga-only (novels have no paged/long-strip axis). Net-new `MangaType` heuristic + a `// RK` branch in `getMangaReadingMode()`. Liveness of the source list checked via byparr (July 2026). From `unseensnick/Reikai#40`.

### Novel sources & LN plugins

- **CustomNovelSource mirror mode (re-point a source at a mirror domain)** `[M]` - a custom-source layer that delegates to an already-installed extension or LN plugin while rewriting its base URL (tsundoku's `basedOnSourceId` + an OkHttp base-URL interceptor / `withSiteOverride`), to recover a source whose domain moved or died. Reikai has no way to re-point an installed source today.
- **No-code custom novel source (CSS-selector wizard)** `[L]` - add a whole novel site from JSON config (popular / latest / search / details / chapters / content selectors, generated numeric chapter-URL patterns, pagination, POST search, status + date mapping) with a per-step test-probe wizard, no plugin authoring (tsundoku's `CustomNovelSource` + `CustomSourceManager.testSource`). A net-new source-authoring path beyond LN plugins.

### Downloads & updates

- **Novel download/update pacing controls** `[S-M]` - per-source throttle, update staggering, and a per-source override map for novel scrapers (tsundoku's `NovelDownloadPreferences`), a more complete anti-detection pacing layer than Reikai's current per-chapter backoff. Independent of Road B.

### Trackers

Dedicated LN trackers are shippable via WebView session-scraping (no official API needed), which overturns the earlier park for RanobeDB / NovelList.

- **WebView cookie/token tracker login** `[M]` - a shared WebView login flow that captures a service's session cookie or JWT (tsundoku's `TrackerWebViewLoginActivity`), the auth path all three novel trackers below need; Reikai today has only OAuth-deeplink and username/password login. Strip tsundoku's raw-cookie DEBUG logging on port.
- **RanobeDB tracker** `[M]` - a dedicated light-novel tracker (ranobedb.org): status, score, dates and delete, via a public JSON read API plus a reverse-engineered write path. Strongest of the three; port first.
- **NovelList tracker** `[M]` - novellist.co tracker: status, chapter progress and score via a JWT REST API (search needs no auth). Second.
- **NovelUpdates tracker** `[L]` - novelupdates.com tracker: highest demand but 100% HTML scraping plus a notes-field progress hack, no score or date sync; high ongoing maintenance. Port last or skip.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - the manga/novel/adult surfaces are now collapsed onto shared `Entry*` components (History/Updates rows, cover dialog, details screen, browse, global search, library settings, sort), so the structural unification is largely done. The remaining forward work is owning the pixels: a Reikai theme (color / typography / shape / component defaults through the single `TachiyomiTheme` -> `MaterialExpressiveTheme` entry point) layered over the shared components, seeding tokens in `DESIGN.md` first (brand in `PRODUCT.md`: quiet, dense, deliberate). Complementary to, not a replacement for, the structural work. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked / not building

One line each; revive note where relevant.

- **Full two-way EH favorites sync** (pull account -> library) - the only feature that would mutate the library from a remote source; the scoped one-way backup shipped instead. Revive only if account -> library mirroring is wanted.
  - **EH per-page add-path throttle** `[S]` bundles here - redundant with the shipped 3/sec rate limit for normal imports; only this feature's sustained walk exercises it.
- **Manga per-page chapter loading** - no manga source would feed a paged chapter list (the contract returns the full list in one call).
- **Auto-error a chapter stuck mid-download** `[S]` - a per-chapter stall timeout so a hung image download gives up faster than `callTimeout` x3 (~8 min worst case). Parked: the pause/resume fix covers the reported bug and stalls still self-resolve via `callTimeout`. Revive if a permanent stall (callTimeout never fires) turns up.
- **Per-chapter control in the download queue (expandable cards)** `[M]` - the unified queue collapsed to one card per series (drag / move-to-top / move-to-bottom / cancel act on the whole series), dropping per-chapter reorder + per-chapter cancel from the global queue. Parked: series-level control covers the real cases and per-chapter selection lives on the details screen. Revive by expanding a card to its chapters on tap; Mihon's per-chapter manga queue files (`DownloadAdapter` / `DownloadHolder` / the `download_single` menu) are still in the tree, so it is mostly wiring plus a novel equivalent.
- **Hardcover / MiraiList trackers** - still no sanctioned read+write API; recheck Hardcover if it leaves beta. (RanobeDB / NovelList / NovelUpdates moved to Later -> Trackers: shippable via WebView session-scraping.) See [novel-tracking.md](docs/dev/plans/novel-tracking.md).
- **Novel recommendations / related carousel** - now feasible (trackers shipped) as an `[M]`; the source-native path stays infeasible (no plugin `getRelated`). Reconsider if wanted.
- **On-device novel translation (translation-engine ecosystem)** - tsundoku's pluggable translate stack (LibreTranslate / OpenAI / local Ollama / DeepL / Gemini / a custom-HTTP engine, plus a translate-on-download hook). Cool and possibly useful, but uncertain whether it gets real use; low priority, deliberately kept off the active backlog for now. Revive if on-device / AI novel translation is wanted.
- **Batch recommendation search** - overlaps the existing taste-profile layer. Revive if manual multi-title discovery is wanted.
- **CMK source-native recommendations (+ id-graph)** - stock CMK was pulled from the extension repos, so the recs port's id-set gate never fires (only clones with different ids remain). Revive if a first-party CMK source returns; the id-graph idea (suggest tracker binds from an entry's cross-links) rides the same API.
- **MD source-native similarity carousel** - its only data source (`api.similarmanga.com`, the TF-IDF `similar-manga` project) is frozen at 2025-05-27 and unmaintained; MD's official `/relation` endpoint returns exact relations (doujinshi / colored), not discovery, and tracker recs already cover popular titles. Dropped with the MD enhanced source (0.2.0); see [md-enhanced-source.md](docs/dev/plans/md-enhanced-source.md).
- **Serialize track-sheet edits (rapid edits clobber each other)** `[M]` - each field edit runs in its own coroutine (`EntryTrackInfoDialog.kt`, the shared manga+novel dialog), so two quick edits (e.g. chapter then score) race on the same track row and the second wins, losing the first. A Mihon-wide race, worst on MDList; now applies to both content types. Parked: the per-track mutex fix touches shared tracker code, so it needs its own scoped pass. Revive standalone.
- **Content-type binary fetch for LN plugins** - auto-detecting a binary response by Content-Type and base64-transporting it would let a plugin read true binary bytes from a normal `fetch`, but it risks garbling a mislabeled non-UTF-8 (GBK / Shift-JIS) text source, and no current novel plugin fetches raw binary (they decode base64 / hex text via the `Buffer` shim, which shipped). Revive with an explicit opt-in binary mode if a real binary-fetch source appears. `Response.arrayBuffer()`, `Buffer`, `Blob`, `X-XSRF-TOKEN`, real `setTimeout` delays, and the rest of the LN host / parsing hardening shipped in 0.3.0.
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
