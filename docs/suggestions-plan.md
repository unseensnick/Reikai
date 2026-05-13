# Suggestions / Related-Mangas — porting plan

Working notes for adding Komikku-style related-manga suggestions to Yōkai-Y2K, plus a Y2K-original personalization layer that re-ranks (and adds to) the result pool using the user's tracker library, with explicit anti-echo-chamber controls.

**Status:** Phases 1–6 are done. Phase 6.5 (full-screen browse) is next, then Phase 7 (sliders) and Phase 8 (i18n).

## Scope split

The feature has two layers, advertised separately in the README. The Komikku layer ports the feature as-is; the Y2K layer is fork-original and toggleable — turning both Y2K toggles off gives behaviour identical to a clean Komikku port.

| Layer | README section | What it is |
|---|---|---|
| Source-native related mangas | From Komikku | New `getRelatedMangaList` extension point on the source API. |
| Keyword-search fallback | From Komikku | Title-split → per-keyword `getSearchManga` → dedupe. Default for sources without native support. |
| Tracker-backed recommendations | From Komikku | AniList / MAL / MangaUpdates similar/related endpoints. |
| **Active candidate injection** | **Unique to Yōkai-Y2K** | Adds taste-driven candidates from tag-search + tracked-favorites' related lists. |
| **Taste-profile reranking** | **Unique to Yōkai-Y2K** | Re-orders the combined pool by similarity to your library. |
| **Serendipity / anti-echo** | **Unique to Yōkai-Y2K** | Reserved exploration slots, tag-diversity cap, tag-novelty boost — keeps recommendations from collapsing into "more of the same." |
| **Personalization settings** | **Unique to Yōkai-Y2K** | Per-mechanism toggles + weight sliders. |

---

## Implementation status & decisions

Updated after Phases 1–2 landed. Decisions captured here are the ones that diverged from the original spec or settled a previously-open question.

### Phase status

| Phase | Status | Commit anchor (on `feat/related-mangas`) |
|---|---|---|
| 1. Source-API contract + keyword fallback | ✅ done | `54fa8d9b3` |
| 2. UI carousel on manga details | ✅ done (+ stability follow-ups) | `fb5fed2f8` → `fd9be3ca6` |
| 3. Tracker-backed recommendations | ✅ done | `7edda7726` → `1161fe37a` |
| 4. Taste profile | ✅ done | `0a6e42d66` → `5f8f8c6c8` |
| 5. Active candidate injection (tag-search + cross-rec + HttpSource baseline + cross-pool dedup) | ✅ done | `ebfe5a48b` → `4936c53f9` |
| 6. Rerank + anti-echo | ✅ done | this cycle |
| 6.5. Dedicated full-screen browse view ("See all" surface) | ⏸ planned — lands after Phase 6 | |
| 7. Settings UI (personalization sliders) | ⏸ not started | |
| 8. i18n + `docs/related-mangas.md` | ⏳ partial | Doc covers Phases 1–5; CHANGELOG bullets land per phase; non-English string translations still ride upstream Moko Resources cycle |

Branch is forked off `feat/tracker-sync-grouped`. Phases 1–3 form a usable Komikku-equivalent baseline on their own.

### Phase 1 decisions

- **Source-API signature: ported Komikku's streaming callback verbatim** — not the flat `List<SManga>` or bucketed `List<Pair<String, List<SManga>>>` alternatives this doc originally proposed. The signature is:

  ```kotlin
  suspend fun getRelatedMangaList(
      manga: SManga,
      exceptionHandler: (Throwable) -> Unit,
      pushResults: suspend (Pair<String, List<SManga>>, Boolean) -> Unit,
  )
  ```

  Extensions override only `fetchRelatedMangaList(manga): List<SManga>` and set `supportsRelatedMangas = true`; the base class handles streaming and the search fallback. This is the right shape for incremental UI population — the carousel can fill in as each keyword search returns.

- **Errors route via `exceptionHandler`**, not via an internal Kermit log. Komikku swallows sub-task errors into a log; we surface them to the caller's handler. Side benefit: no kermit dep needed in `source/api`'s commonMain.

- **Dropped Komikku's `manga.originalTitle` branch** in the keyword-search fallback. Yokai's `SManga` has no `originalTitle` field. Only `manga.title` is split.

- **`QuerySanitizer` ported as a stand-alone utility** at `source/api/src/commonMain/kotlin/eu/kanade/tachiyomi/util/QuerySanitizer.kt`. Yokai didn't have it.

- **Coroutines core in `source/api` commonMain** is already transitively available via `koin-core` (see `Page.kt` for prior use of `kotlinx.coroutines.flow`). No gradle changes needed.

### Phase 2 decisions

- **Carousel uses RecyclerView + FlexibleAdapter**, not Compose. Manga details screen is still Conductor + ViewBinding (per CLAUDE.md). New classes:
  - `RelatedMangaCardItem` / `RelatedMangaCardHolder` / `RelatedMangaCardAdapter` under `ui/manga/related/`
  - `OnRelatedMangaClickListener` interface implemented by `MangaDetailsController`
  - `related_manga_card_item.xml` for the real card, `related_manga_skeleton_card.xml` + `drawable/skeleton_placeholder.xml` for the loading placeholder

- **Layout stability is achieved differently from Komikku's Compose impl.** Komikku puts placeholder cards inline in the same `LazyRow` as real items so the container is permanently fixed-size; Yokai uses two siblings (skeleton `LinearLayout` + RecyclerView) anchored at the same position. To get the same stability:

  1. **`VISIBLE`/`INVISIBLE` swap between skeleton and recycler, never `GONE`.** Both children always occupy the 230dp slot once the section is visible. `GONE` causes the layout to collapse and pushes siblings around.
  2. **`Barrier` (`related_mangas_bottom_barrier`) references both** with `barrierDirection="bottom"`. `start_reading_button` (and `chapter_layout` in landscape) anchors to the barrier, not directly to the recycler. Without this the button slides into the skeleton row when the recycler is GONE.
  3. **Adapter populated before `recycler.visibility = VISIBLE`**, closing the one-frame "visible but empty" gap.
  4. **`recycler.itemAnimator = null`** so streamed batch appends don't animate.
  5. **Section-level `GONE`** is fine and still used for the "source has no suggestions, hide the whole section" state.

- **Click navigation creates a DB row.** `MangaDetailsPresenter.toLocalManga(sManga, sourceId)` mirrors `GlobalSearchPresenter.networkToLocalManga` — looks up by `(url, source)`, inserts if missing, returns the local `Manga`. The carousel then routes through `router.pushController(MangaDetailsController(local, true).withFadeTransaction())`.

- **Pool cap of 30** is hard-coded as `RELATED_MANGAS_LIMIT` in `MangaDetailsPresenter.companion`. Deduplication uses a `LinkedHashSet<SManga>` keyed by `url` (within a single source), protected by a `Mutex` since `pushResults` is invoked concurrently from per-keyword sub-tasks.

- **Per-presenter cache.** `relatedMangasFetched: Boolean` is a one-shot guard; re-attaches/POP_ENTER don't re-fetch. New `MangaDetailsController` instances (e.g. after switching source via chip) get a fresh presenter and re-fetch.

- **Trigger from `onAttach`.** Idempotent — `presenter.fetchRelatedMangasFromSource()` is safe to call on every attach.

- **Source chips got the same memoization treatment** (related fix, not new feature):
  - `(mangaId, relatedMangaIds, mangaManualUnmerges)` memo key on `MangaHeaderHolder`.
  - `mangaManualUnmerges` is part of the key because `removeFromGroup` only writes to the pref and never mutates the in-memory `relatedMangaIds` — without including the pref, chip long-press → "Remove from group" would leave the chip on screen.
  - `setSourceChips` only `GONE`s the row when chips truly aren't needed (single-source manga); during fetch the scroll view stays at its XML-default `GONE` until chips are added, then flips to `VISIBLE` once and stays there.

### Phase 3 decisions

- **Three tracker endpoints, all public.** AniList GraphQL, MyAnimeList via Jikan v4 REST (the official MAL API has no recommendations endpoint — Komikku does the same), MangaUpdates v1 community recommendations. Each runs without auth — no tracker sign-in required. The MU `category_recommendations` ("similar") variant Komikku also exposes is deferred; community only for Phase 3.

- **Komikku's by-id-or-search dispatch ported verbatim.** Each tracker first looks up the user's existing track entry for the manga (via `GetTrack.awaitAllByMangaId` from the `domain/` interactor — **not** `TrackRepository` directly). If found, fetch recommendations by `media_id` for that tracker; otherwise resolve the id via a title search and then fetch recommendations. Means tracker recommendations work even for untracked manga.

- **Single carousel, not separate sections.** Komikku has a dedicated Recommends *screen* with one section per tracker; Yokai has a single inline carousel on manga details. Tracker batches feed the same `pushResults` callback the source-native and keyword-search results use, sharing the existing `LinkedHashSet` dedup + 30-item cap. Tracker URLs and source URLs are in distinct URL spaces so URL-based dedup doesn't false-collide.

- **`RECOMMENDS_SOURCE = -1L` sentinel + Global Search routing.** Each pool entry is wrapped in `RelatedMangaCandidate(sourceId, manga)` (in [`ui/manga/related/`](../app/src/main/java/eu/kanade/tachiyomi/ui/manga/related/RelatedMangaCandidate.kt)). Source-native results get `sourceId = catalogueSource.id`; tracker results get `RECOMMENDS_SOURCE`. `MangaDetailsController.onRelatedMangaClick` now takes the full item and branches: source-origin clicks resolve via `toLocalManga` as before; tracker-origin clicks call `globalSearch(item.manga.title)` (mirrors Komikku's SmartSearch behavior). Tracker URLs would never resolve to an installed extension, so opening one via `toLocalManga` would create an orphan DB row — sentinel routing avoids that.

- **Package location: `data/recommendation/`, not `data/track/recommendation/`.** These fetchers consume three external APIs that happen to share endpoints with trackers but use no tracker auth, write nothing back to `manga_sync`, and don't depend on `TrackService` lifecycle. Coupling them under `data/track/` would inherit upstream tracker-class refactor risk for no benefit. Mirrors Komikku's `exh/recs/sources/` placement (also outside `data/track/`).

- **`NetworkHelper.client`, not per-tracker authed clients.** The shared OkHttp client is fine — endpoints are public. Pulling each tracker's `Api.client` would attach OAuth interceptors that we don't want firing on public recommendation URLs.

- **`@Serializable` DTOs, not raw JSON parsing.** Komikku uses `JsonObject` / `jsonArray` traversal everywhere; Yokai's existing tracker code uses Kotlinx Serialization data classes throughout. Followed Yokai convention. The shared `Json` instance has `ignoreUnknownKeys = true` + `explicitNulls = false` so DTOs only model the fields we extract.

- **One-shot per presenter, no Phase 3 caching layer.** Same `relatedMangasFetched` guard as Phase 2 — switching source via chip creates a fresh presenter and re-runs all three trackers. Caching across screen rotations / app restarts is deferred unless a future phase needs it.

- **Cross-pool title-dedup not added.** A manga could appear in source-native AND in a tracker's recommendations under different URLs. Accepted; matches Komikku (they don't dedupe across since their sections are separate anyway). The 30-cap bounds the visual noise.

- **Tracker slot reservation + round-robin.** Surfaced during verification: source-native + keyword-search return enough hits to fill the 30-cap before the slower tracker endpoints respond, so a naive `take(30)` over the merged `LinkedHashSet` left tracker entries with zero visible slots. Then, with a flat reserve, whichever tracker pushed first would eat the whole reserve (MAL/Jikan typically returns 90+ items and beats AniList by ~150ms, MU by ~600ms, so MU got squeezed out). Fix: `mergeForDisplay` in `MangaDetailsPresenter` reserves up to `RELATED_MANGAS_TRACKER_RESERVE = 12` slots for tracker-origin entries and round-robins those slots across trackers (~4 each for the three trackers; empty trackers cede their share). Either side of the source/tracker split cedes unfilled capacity to the other. `RelatedMangaCandidate.trackerName` carries the tracker label (set from the `pushResults` bucket label, which is "AniList" / "MyAnimeList" / "MangaUpdates" for tracker pushes and null for source pushes).

### Phase 4 plan (brainstormed pre-implementation)

The original plan doc treated the taste profile as one mechanism ("fetch tracker library, compute tags"). Brainstorm with the user surfaced enough nuance that pinning the shape *before* coding is worth the upfront work. Decisions below.

#### Two layers, layered

The taste profile draws from two complementary signals. Both produce the same `TrackedEntry` row shape and feed the same compute formula:

| Layer | Source | Cost | Coverage |
|---|---|---|---|
| A — Local | `manga_sync ⋈ mangas` join (existing tables) | Zero — live SQL query, no network | Currently-in-library manga that have at least one Track row |
| B — Remote | Per-tracker authed library API call, results persisted to a new DB table | ~3–5 API calls per voluntary refresh | Everything the user has ever rated/tracked on each enabled tracker, including manga they've since removed from the library |

User's "rate-then-remove" workflow (rate a finished series, remove from library to keep the library clean, then read something new) **deletes the local Track rows** — confirmed via `MangaDetailsController.onMangaDeleted` → `presenter.confirmDeletion()` → `deleteTrack.awaitForMangaAll(mangaId)` — so Layer A alone is insufficient for that workflow. Layer B reads from the trackers' own source-of-truth and catches everything Layer A misses. Layer A still earns its keep for currently-reading manga (always-fresh, free).

Composition at compute time: union A's rows and B's rows, dedup within-tracker by `(tracker, remoteId)`. Don't dedup cross-tracker for v1 — see open questions.

#### Trackers in Layer B's scope

Three public cloud trackers: AniList, MyAnimeList, Kitsu. MangaUpdates / Shikimori / Bangumi were originally in scope but dropped — the maintainer doesn't have accounts on them and won't ship code they can't verify against a real library. Their toggles are also removed from the settings screen for now (rather than greyed-out) to avoid surfacing options that can never be enabled. Self-hosted services (Komga / Kavita / Suwayomi) are also out of scope — their tag taxonomies depend on user-uploaded metadata and don't carry a canonical genre vocabulary that's useful for tag-based recommendation. If a user requests one of the dropped trackers later, adding it is mechanical (one fetcher + one toggle + one `LIBRARY_TRACKERS` entry).

Rolled out as a sequence of small commits, each independently shippable:

| Step | Trackers added | Rationale |
|---|---|---|
| Phase 4 core | framework only (Layer A live, B scaffolding) | A alone produces a working taste profile for currently-reading manga; verifiable via logcat probe |
| Phase 4.1 | AniList | Easiest API (1 GraphQL query returns the lot), most users have it |
| Phase 4.2 | MyAnimeList | Common pairing with AniList |
| Phase 4.3 | Kitsu | Catches manga that exist only on Kitsu (e.g. "The Beginning After the End" — confirmed gap) |
| ~~Phase 4.4~~ | ~~MangaUpdates~~ | Dropped — maintainer doesn't use it, can't test the library pull. Phase 3 public recommendations endpoint is unaffected. |
| ~~Phase 4.5~~ | ~~Shikimori + Bangumi~~ | Dropped — same reason. Niche audiences, untestable for the maintainer. |

Each tracker implements the same `TrackerLibraryFetcher` contract. Adding one is mechanical once the framework is in place — same DTO + status mapping + score normalization shape.

#### Storage: new SQLDelight table in the existing DB

```sql
-- data/src/commonMain/sqldelight/tachiyomi/data/tracker_library_cache.sq
CREATE TABLE tracker_library_cache (
    tracker TEXT NOT NULL,        -- "AniList" / "MyAnimeList" / etc.
    remote_id INTEGER NOT NULL,   -- tracker media id
    title TEXT NOT NULL,
    score REAL NOT NULL,          -- 0..1 normalized, -1 if unrated
    status INTEGER NOT NULL,      -- ordinal of TrackStatus enum
    tags TEXT NOT NULL,           -- comma-separated, lowercased + trimmed
    fetched_at INTEGER NOT NULL,  -- epoch millis, per-row
    PRIMARY KEY (tracker, remote_id)
);
CREATE INDEX tracker_library_cache_tracker_idx ON tracker_library_cache(tracker);
```

Same DB as the rest of Yokai's app data (`mangas`, `manga_sync`, `categories`, …). Riding Yokai's existing backup pipeline means the user's cached library survives device restores — chosen explicitly over filesDir JSON for this reason. `MAX(fetched_at) WHERE tracker = ?` gives per-tracker last-refresh for the settings display; no separate metadata table.

Refresh is atomic per tracker (transaction: `DELETE WHERE tracker = ?` then bulk `INSERT`) so a partial-failure scenario leaves the previous data intact.

#### Cache invalidation: durable, no time-based TTL

The original plan said "24h TTL". Replaced with a tiered approach that's friendlier to rate limits:

1. **Manual refresh button** (primary) — user clicks "Refresh now" in settings, all logged-in + enabled trackers fetched sequentially.
2. **Event-driven incremental updates** — when the user binds / updates a tracker entry *via Yokai's own UI*, the corresponding row in the cache is updated in place (no API call needed). Piggybacks on the existing `InsertTrack` / `UpdateTrack` interactors.
3. **Optional auto-refresh interval** — single setting "Auto-refresh tracker library" with values `[Never, 7 days, 30 days]`, default **Never**. Conservative default; users who want a fail-safe can opt in.
4. **Button cooldown** — refresh button disables for 60 s after press to prevent panic-spam.
5. **Soft staleness hint** — "Last refresh: 12 days ago" shown in settings as info, not enforcement.

Rate-limit picture in practice: a full-fan-out refresh is ~3–5 API hits total (AniList returns the entire 150-entry library in a single GraphQL `MediaListCollection` query; MAL / MU / Kitsu are 1–3 paginated calls each). The danger isn't one fetch — it's fetches stacking up, so the defense is making refreshes deliberate.

Additional rate-limit guards:
- Sequential per-tracker fetch (not parallel) — easier abort semantics
- Honor `Retry-After` on 429 — log + skip that tracker, continue with others
- No background WorkManager job, no app-launch refresh
- Per-tracker enable toggle (separate from Phase 3's recommendation-source toggles)

#### Normalization

- **Scores:** AniList → divide by the user's per-account `scoreFormat` max (POINT_100 / POINT_10 / POINT_10_DECIMAL / POINT_5 / POINT_3, fetched alongside the library). MAL / MU / Shikimori / Bangumi → fixed scales, divide accordingly. Kitsu → variable, fetch per user. All normalized to 0..1; unrated entries stored as `-1` so the compute formula can distinguish "rated low" from "no rating".
- **Status:** unified enum `TrackStatus { COMPLETED, READING, ON_HOLD, PLAN_TO_READ, DROPPED, UNKNOWN }`. Per-tracker status integers map in at fetch time.
- **Tags:** lowercase + trim, no fuzzy matching for v1. Log raw distribution on first fetch so a tracker-specific normalization map can be added later if cross-tracker taxonomy mismatch ("Sci-Fi" vs "Science Fiction") proves actionable.

#### Settings UI (extends what Phase 3 added)

Settings → Library → Recommendations:
- Tracker-backed recommendations [master, Phase 3, exists]
- Recommendation sources [Phase 3, exists]: AniList / MyAnimeList / MangaUpdates toggles
- **— Taste profile — [NEW Phase 4 section header]**
- Pull library from these trackers — three per-tracker toggles, default off, greyed when not logged in. AniList / MyAnimeList / Kitsu. (MangaUpdates / Shikimori / Bangumi dropped — see scope note above.)
- Auto-refresh tracker library — int list `[Never, 7 days, 30 days]`, default Never
- Refresh now — button, 60 s cooldown after press
- Last refresh: per-tracker line summary

#### What Phase 4 explicitly defers

These were considered and pushed to later phases or open questions:
- **Cross-tracker dedup** (same manga on AniList + MAL). Within-tracker dedup is automatic via the `(tracker, remoteId)` PK. Cross-tracker is harder — AniList exposes `Media.idMal` as the cleanest cross-ref, Kitsu has its own mapping endpoint, MU/Shikimori/Bangumi don't have direct cross-refs. Triple-counting an entry may even be the *correct* behavior (the user cares enough to track on two services → stronger signal). Revisit if observation shows it's a problem.
- **Importing from MAL XML export** (the file format malscraper produces). Doesn't carry genres, so it still needs API enrichment; doesn't save real work over Option B's direct GraphQL path; only useful for users who refuse to grant library-read scope, which we'll address as a separate import flow if requested.
- **Cross-tracker taxonomy normalization map.** Start exact match. Log raw tag distribution and build the map empirically if mismatches matter.

### Phase 5 decisions

- **Single orchestrator class for both sub-mechanisms.** `TasteCandidateFetcher` at [data/recommendation/](../app/src/main/java/eu/kanade/tachiyomi/data/recommendation/TasteCandidateFetcher.kt) mirrors Phase 3's `RecommendationsFetcher` shape — fans out tag-search and cross-recommendation flows concurrently, routes errors through the same `exceptionHandler`. Reuses Phase 4's `GetTrackedEntries.await()` + `ComputeTasteProfile(entries).topTags(n)` instead of recomputing.
- **Cross-rec resolves favorite → SManga via search-then-pick-first.** No fuzzy match; if the first search hit is wrong, results are off-topic but harmless. The user's accepted behavior is "falls back gracefully when a favorite isn't on the current source — that favorite is just skipped." Constants `FAVORITE_SCORE_THRESHOLD = 0.8` and `MAX_FAVORITES = 5` bound the call count.
- **HttpSource baseline ported, reverted, then re-ported.** First attempt (`2aa8063fa`) used Komikku's exact override (`override val supportsRelatedMangas: Boolean get() = true` plus a `fetchRelatedMangaList` calling `relatedMangaListRequest` → `relatedMangaListParse` chain). Verification on ~6 sources returned `related=0` for every cross-rec call, so the baseline was reverted in `cf1f5140e`. The Keiyoushi extensions repo audit later showed ~377 extensions ship overrides for the related-mangas hooks (Madara × 342, Iken × 12, GalleryAdults × 8, plus 15 standalone) — the original verification sample missed them. Re-ported in `15f6493ce` with the same shape, using Y2K's idiomatic `awaitSuccess()` instead of Komikku's blocking `execute()`.
- **Cross-rec user toggle: removed, then restored.** Dropped in `cf1f5140e` because the gate was structurally closed and the toggle did nothing. Restored in `d5f77183b` once the baseline re-port made cross-rec actually fire. Toggle defaults on; gates AND with `supportsRelatedMangas` so users can disable cross-rec without disabling tag-search.
- **Cross-pool dedup keys on normalized title.** Parallel `HashSet<String>` alongside the URL-keyed accumulator in `fetchRelatedMangasFromSource`. Normalization is minimal: lowercase + trim + collapse internal whitespace (`MangaDetailsPresenter.normalizeTitleForDedup`). First-arriving entry wins — natural preference for source-origin (faster) and source-tap routes directly to the reader.

### Phase 6 decisions

- **Ranker lives in `app/data/recommendation/`, not `domain/`.** The handoff doc originally recommended `domain/`. Reverted to `app/` to match the `TasteCandidateFetcher` / `RecommendationsFetcher` precedent (both consume `RelatedMangaCandidate` + `SManga`, which live in `app/`). No module-graph changes; no abstract interface needed. The ranker is still pure compute — no I/O, not `suspend` — so the "use-case" character of the original recommendation is preserved.
- **Anti-echo scope: library-URL match only.** The ranker drops candidates whose `(sourceId, manga.url)` is in `getManga.awaitFavorites().map { it.source to it.url }`. Tracker-origin entries (`sourceId == RECOMMENDS_SOURCE`) skip the filter — their URLs are tracker URLs and would never match a library row anyway. Status-based filters ("hide Reading / Completed / Dropped") are deferred to Phase 7 with the rest of the personalization UI.
- **Anti-echo always on, even when the rerank toggle is off.** Rationale: hiding library-known manga from the carousel isn't really a taste opinion, it's just "don't suggest what I already have." Decoupling the two means flipping `enableRecommendationRerank` off returns the user to the Phase 5 ordering exactly, but with library entries still hidden. Users who want library entries back can revisit when Phase 7 adds the filter UI.
- **TasteProfile gains `tagEntryCounts: Map<String, Int>`** for the novelty boost. Populated in `ComputeTasteProfile` from every entry's tags regardless of status weight — even PLAN_TO_READ and UNKNOWN count toward exposure breadth (the user has seen the tag in their library), they just don't contribute to the affinity score. Cheap to compute, parallel-keyed with `tagScores`.
- **Rerank scope: source slice only, tracker slice passes through.** Trackers already return personalized recs by construction (you tracked these because they match your interests), and their fairness round-robin is a separate concern from taste alignment. Tracker entries also rarely carry parseable `SManga.genre`, so scoring them would mostly produce 0.0 noise.
- **Exploration slots pull from the top of the popularity-ordered slice**, not random samples. `⌈sourceSize × 0.2⌉` ≈ 4 slots at default `w_serendipity = 0.2`. Means users always see "what the source thinks is broadly relevant" no matter how strong the taste signal — a built-in guard against echo collapse.
- **Diversity cap: 2 per dominant tag.** "Dominant" = the candidate's highest-affinity tag from the user's `tagScores` map (so the cap operates on what the user cares about, not whichever tag the source happens to list first). Walks the taste-sorted list top-down, demotes offenders to a deferred list that's drained at the end if any slots remain. Single pass, no re-sort.
- **Bypass paths log under `[Phase6]`** so the verification probes are uniformly filterable in Logcat. Three bypass reasons: `emptyProfile` (zero tracked entries), `noScoredTags` (entries exist but none scored — all PLAN_TO_READ / UNKNOWN), `trivialSlice` (≤1 source candidate, nothing to reorder).
- **Defaults hardcoded for Phase 6**: `w_personal = 0.3`, `w_serendipity = 0.2`, `maxPerDominantTag = 2`. Phase 7 will surface sliders that read into these positions via preferences.

### Bugs surfaced and fixed during Phase 1–2 work

These were latent — Phase 2 just happened to exercise the right code paths.

- **`MangaCoverMetadata.getVibrantColor(Long?)` / `getColors(Long?)` NPE on null mangaId.** Both methods passed a nullable mangaId straight into `ConcurrentHashMap.get()`, which NPEs on null keys. Every other method in the file had a `mangaId ?: return` guard; these two missed it. Triggered by carousel cover loads passing `mangaId = null` (recommendations aren't library entries yet). Fix: added the same null guard.
- **`Manga.create()` and `copyFrom()` need separate imports.** Both are extension functions in the `data.database.models` package, not members of the `Manga` class. `GlobalSearchPresenter` already imports them; my Phase 2 work needed the same.

---

## Komikku layer (port as-is)

### Source-API contract

Add to [`source/api`](../source/api/) — `suspend fun getRelatedMangaList(manga: SManga): List<SManga>` on the source contract (likely `CatalogueSource`, but pin during phase 1 — Yōkai still has RxJava in places, so check whether the surrounding API is `Observable` or `suspend`).

`open` with a default keyword-search implementation so existing extensions continue to compile and run. CLAUDE.md §"Source Plugin System" prohibits breaking the public source-API surface without a migration plan — defaulting via the fallback satisfies that.

Three opt-in flags mirroring Komikku:

- `supportsRelatedMangas` — source declares it has a native implementation.
- `disableRelatedMangasBySearch` — disable the keyword-search fallback for this source (some sources return junk for two-word queries).
- `disableRelatedMangas` — turn the whole feature off for this source.

### Keyword-search fallback

Default behaviour when a source doesn't override `getRelatedMangaList`:

1. Split the manga's title into keywords (drop articles, punctuation, very short tokens).
2. For each keyword, run `source.getSearchManga(keyword)`.
3. Dedupe by `(source_id, manga_url)`, drop the current manga, cap at ~20.

### Tracker-backed recommendations

Three paging sources mirroring Komikku's `RecommendationPagingSource` subclasses:

- AniList GraphQL recommendations endpoint.
- MyAnimeList public API recommendations.
- MangaUpdates community ratings.

Each takes the current manga's title (or tracker ID if available) and returns related entries. Results merge into the same candidate pool as the source-native list.

### UI

Horizontal carousel below the description on [`MangaHeaderHolder`](../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaHeaderHolder.kt) — Yōkai's details screen is RecyclerView+ViewBinding, not Compose, so the carousel is a horizontal `RecyclerView` (LinearLayoutManager.HORIZONTAL), not LazyRow. Lazy-load on first scroll into view. Loading shimmer. Hide section if empty.

### Komikku reference points

- [`MangaScreenModel.kt`](../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt) lines 1205–1253 — `fetchRelatedMangasFromSource`. Lazy-load + cache shape.
- [`CatalogueSource.kt`](../../komikku/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/CatalogueSource.kt) lines 70–200 — extension-point shape and the keyword-search fallback. Komikku returns `List<Pair<String, List<SManga>>>` (keyword-bucketed); Y2K can flatten unless we want grouped UI.
- `RelatedMangasList.kt` / `RelatedMangasRow.kt` — port the layout logic, not the framework (Compose → RecyclerView).

---

## Y2K personalization layer

Built on top of the Komikku layer. Both mechanisms below are individually toggleable; with both off, the user gets a clean Komikku experience.

### Inputs

- **User's tracker library** — lazily fetched per-tracker, cached with 24h TTL, manual refresh button. Per entry: tracker score (normalized to 0–1), status (`reading` / `completed` / `dropped` / `on_hold` / `plan_to_read`), tags/genres exposed by the tracker.
- **Komikku candidate pool** for the currently-viewed manga (source-native + tracker similar, already deduped).

### Active candidate injection (additive — changes *what's in the pool*)

Two sub-mechanisms, both opt-in, both can be off:

**1. Tag-search on the current source.**

- Compute the user's top-N tags from the taste profile (default N = 3).
- For each tag, call `currentSource.getSearchManga(tag)`.
- Pool top hits across all top tags, dedupe.
- Cheap (~3 calls). Stays on the current source — no source-switching while reading.
- Limitation: depends on the source supporting tag-style search. Some sources only do title search; for those, this sub-mechanism contributes nothing (silent fallback to Komikku-only pool for that source).

**2. Cross-recommendation between top-tracked entries' related lists.**

- Pick the user's top-rated tracked manga (e.g. tracker score ≥ 8, max 5 entries).
- For each, call the source's `getRelatedMangaList(favorite)` — same API as phase 1 of the Komikku layer.
- Pool, dedupe.
- Heavier (~5 network calls per refresh) but high-precision: surfaces matches the user has already implicitly endorsed.
- Falls back gracefully when a favorite isn't on the current source — that favorite is just skipped.

Injected candidates are merged with the Komikku pool, deduped by `(source_id, manga_url)`, then passed to the rerank stage.

### Taste profile

For each tag/genre `t`:

```
score(t) = Σ (rating_norm × status_weight × is_tagged(m, t))  /  Σ (status_weight × is_tagged(m, t))
```

With status weights:

| Status | Weight |
|---|---|
| Completed | 1.0 |
| Reading | 0.7 |
| On hold | 0.3 |
| Plan to read | 0.0 |
| Dropped | −1.0 |

Result: a map `Tag → [-1, +1]`. Recomputed once per tracker-library refresh (not per recommendation).

### Reranking (toggleable — changes *what order the pool is in*)

For each candidate `c`:

- `taste_score(c) = mean(score(t) for t in tags(c))` → normalized rank in [0, 1].
- `popularity_rank(c)` = position in the original Komikku pool → [0, 1].
- `final_score(c) = (1 − w_personal) × popularity_rank(c) + w_personal × taste_rank(c)`

`w_personal` is the user's "Popular ↔ Personalized" slider, default 0.3. Skipped entirely if the rerank toggle is off.

### Serendipity / anti-echo-chamber

Personalization tends to homogenize results — high tag-affinity → all recommendations carry that tag → the user gets bored. Three small mechanisms keep breadth, all weighted by a single "Familiar ↔ Adventurous" slider `w_serendipity` (0..1, default 0.2):

**1. Exploration slots.** Reserve `⌈N × w_serendipity⌉` of the carousel's N slots for non-personalized candidates — pulled straight from the Komikku pool with no rerank, in their original order. Guarantees a baseline of "what's broadly relevant" no matter how strong the taste signal.

**2. Tag-diversity cap.** Within the personalized slots, no more than 2 candidates may share the same dominant tag. After rerank, walk the sorted list top-down; if a candidate's top tag already has 2 in the kept set, demote it past the next non-conflicting candidate. Single-pass, cheap.

**3. Tag-novelty boost.** Boost `taste_score` for candidates carrying tags the user has *few* tracked entries for. Reasoning: 50 Action vs 2 Slice-of-Life means barely-explored Slice-of-Life — surfacing one is exploration, not regression to the mean. Boost magnitude:

```
novelty_boost(c) = w_serendipity × min(cap, log(1 + N_total / N_tag))
```

where `N_total` is the user's library size, `N_tag` is the count tagged with `t`, taken across all tags of `c`. Capped to avoid blowing up on extremely rare tags.

`w_serendipity = 0` → pure preference (echo chamber). `w_serendipity = 1` → Komikku-baseline ordering. Default 0.2 is a light corrective.

### Failure modes

- No active trackers → injection + rerank both disabled silently; carousel = pure Komikku.
- Tracker library too small (<5 tracked entries) → one-line banner: "Track and rate manga to unlock personalized recommendations." Falls back to Komikku.
- Tracker fetch error → fall back, log, no user-facing error.

---

## Settings

**Settings → Library → Recommendations** (new sub-screen, or new section in the existing Library settings).

### Personalization

| Setting | Type | Default | Effect |
|---|---|---|---|
| Active candidate injection | toggle | on | Adds taste-driven candidates to the pool. Off = pool is Komikku-only. |
| Taste-profile reranking | toggle | on | Reorders the pool by taste. Off = original Komikku ordering preserved. |
| Recommendation style | slider 0–1 | 0.3 | "Popular ←→ Personalized". Only affects the rerank stage. Disabled when reranking is off. |
| Serendipity | slider 0–1 | 0.2 | "Familiar ←→ Adventurous". Controls exploration slots, diversity cap aggressiveness, novelty boost. |

Turning **both** "Active candidate injection" and "Taste-profile reranking" off → behaviour identical to a clean Komikku port. Important escape hatch.

### Filters

| Setting | Default |
|---|---|
| Hide already-tracked (Reading / Completed) | on |
| Hide dropped | on |
| Per-tracker enable (one checkbox per logged-in tracker) | on |
| Refresh taste profile (button) | — |

---

## Phased commit plan

| Phase | Layer | Hours | Status |
|---|---|---|---|
| 1. Source-API contract + keyword fallback | Komikku | 3–4 | ✅ done |
| 2. UI carousel on manga details | Komikku | 2–3 | ✅ done |
| 3. Tracker-backed recommendations | Komikku | 3–4 | ✅ done |
| 4. Taste profile (fetcher + cache + compute) | Y2K | 4–5 | ✅ done |
| 5. Active candidate injection (tag-search + cross-recommendation + HttpSource baseline + cross-pool dedup) | Y2K | 3–4 | ✅ done |
| 6. Rerank + anti-echo (formula + diversity cap + novelty + exploration slots) | Y2K | 4–5 | ✅ done |
| 6.5. Dedicated full-screen browse view ("See all" surface, bulk-select, drops 30-cap for the full grid) | Y2K | 1–2 days | ⏸ planned |
| 7. Settings UI (sliders + filters + refresh) | Y2K | 3–4 | ⏸ |
| 8. i18n + `docs/related-mangas.md` | both | 1 | ⏳ partial |
| **Total** | | **23–30** | |

Phases 1–3 ship a usable Komikku-equivalent on their own. Phases 4–7 add the Y2K layer; can land as a follow-up PR once the Komikku baseline is stable.

---

## Open questions

### Resolved (during Phase 1–3)

- ~~**Function signature on the source API.**~~ Ported Komikku's streaming `pushResults` callback verbatim — neither flat nor bucketed. See "Implementation status & decisions" above.
- ~~**Per-rerank pool size.**~~ Hard-cap of 30 (`RELATED_MANGAS_LIMIT` in `MangaDetailsPresenter`). In-process rerank is fine at this scale.
- ~~**Cache invalidation for the candidate pool.**~~ Per-`MangaDetailsPresenter` instance via the `relatedMangasFetched` one-shot guard. New controllers re-fetch.
- ~~**Tracker-recommendation click navigation.**~~ Tracker-origin cards carry a `RECOMMENDS_SOURCE = -1L` sentinel; on click they push `GlobalSearchController(title)` so the user picks an installed source to read on. Source-origin cards retain the existing `toLocalManga` path.
- ~~**Where the tracker recommendation classes live.**~~ New package `data/recommendation/` (sibling of `data/track/`). Uses `NetworkHelper.client` (shared, unauthenticated), `GetTrack` interactor for per-tracker `media_id` lookup, `@Serializable` DTOs.

### Resolved (during Phase 4 brainstorming)

- ~~**Score normalization across trackers.**~~ Per-user-per-tracker: fetch the user's `scoreFormat` from each tracker (AniList exposes it on `User.mediaListOptions`; others have fixed scales) and divide by that max to get 0..1. Unrated entries stored as `-1`, distinct from "rated zero".
- ~~**Taste profile data source.**~~ Two-layer (A local, B remote). A reads `manga_sync ⋈ mangas`. B persists per-tracker library fetches to a new `tracker_library_cache` SQLDelight table.
- ~~**Cache lifetime / TTL.**~~ Durable storage in the main DB (rides Yokai's backup pipeline), no time-based auto-invalidation. Refreshed manually + event-driven for Yokai-side writes + optional auto-interval (default Never).
- ~~**Trackers supported.**~~ AniList, MyAnimeList, Kitsu. MangaUpdates / Shikimori / Bangumi were considered but dropped during Phase 4.1 — the maintainer doesn't use them and won't ship untested fetchers. Self-hosted Komga / Kavita / Suwayomi also excluded (no canonical tag taxonomy).

### Resolved (during Phase 5 finalization)

- ~~**HttpSource baseline activation across Keiyoushi extension ecosystem.**~~ Komikku's `HttpSource` overrides `supportsRelatedMangas = true` and provides a default `fetchRelatedMangaList` that runs `popularMangaParse` on the manga details URL. Y2K's Phase 1 port omitted that override, so every HTTP-backed extension inherited `supportsRelatedMangas = false` and the source-native flow was dormant in practice — only the keyword-search fallback ran. Audit of the Keiyoushi extensions repo revealed ~377 extensions ship working `relatedMangaListRequest` / `relatedMangaListParse` overrides (342 Madara-derivatives parsing `.related-reading-wrap`, 12 Iken extensions hitting a dedicated `/api/recommendations` endpoint, 8 GalleryAdults derivatives, 15 standalone implementations). Re-ported the baseline so virtual dispatch lights up those overrides automatically; non-overriding extensions fall through to `popularMangaParse(details_page).mangas` which is typically empty and absorbed by dedup + 30-cap. An earlier port (`2aa8063fa`) was reverted (`cf1f5140e`) on a small biased sample; the audit invalidated that reversal and the re-port landed in the same release cycle.
- ~~**Same-manga dedup of recommendation candidates.**~~ Implemented as a parallel title-key `HashSet<String>` alongside the URL-keyed `LinkedHashSet` accumulator in `MangaDetailsPresenter.fetchRelatedMangasFromSource`. Normalization is minimal — lowercase + trim + collapse internal whitespace — enough to catch case/whitespace variants without risking false positives across legitimately distinct titles. First-arriving entry wins, which naturally prefers source-origin candidates (single fast call, route to reader on tap) over tracker entries (slower, multiple calls, route through Global Search).

### Resolved (during Phase 6 finalization)

- ~~**Where does the rerank logic live.**~~ `RecommendationRanker` lives in [`app/data/recommendation/`](../app/src/main/java/eu/kanade/tachiyomi/data/recommendation/RecommendationRanker.kt), beside `TasteCandidateFetcher` and `RecommendationsFetcher`. Operates directly on `RelatedMangaCandidate` + `SManga`, no module-graph changes. Still pure compute (no I/O, no `suspend`), so the use-case character is preserved without paying the cost of a `domain/`-side abstraction over `SManga`.
- ~~**Anti-echo scope.**~~ Library-URL exact match (`Pair<sourceId, url>` set, populated from `getManga.awaitFavorites()`). Tracker-origin candidates skip the filter. Status-based filters and fuzzy-URL matching deferred to Phase 7+.

### Still open (Phase 7+ territory)

- **Tag overlap calculation** — exact-match (default) vs. fuzzy ("Sci-Fi" vs "Science Fiction"). Phase 4 ships with exact-match + raw-distribution logging; the normalization map can be built from observed data later if mismatches actually hurt recommendations.
- ~~**Cross-tracker dedup of taste-profile entries**~~ — implemented in the same wave as Phase 4.3 after the maintainer's mirror-list setup surfaced as a real bug. Two cross-ref keys per entry (migrations 30 and 31): `mal_id` and `anilist_id`. AniList trivially populates `anilist_id` from its own remote id and `mal_id` from `Media.idMal` when set; MAL trivially populates `mal_id` from its own remote id (no AL cross-ref exposed); Kitsu resolves both via its `mappings` records (filter `externalSite=="myanimelist/manga"` and `"anilist/manga"`). `GetTrackedEntries.dedupedAcrossTrackers()` runs a two-pass collapse: first by `malId`, then by `anilistId` on the result. Both passes prefer AniList > MAL > Kitsu (richest tag taxonomy first). The second pass catches the "AniList manhwa without idMal + Kitsu has the AniList mapping but no MAL one" gap that pass 1 misses. Entries with neither cross-ref pass through and only dedupe within their own tracker. The orchestrator's post-refresh diagnostic logs the dedup-drop count for each pass.
- **Same-manga dedup of *recommendation candidates*** — different problem from taste-profile dedup. If two tracker recommendations surface the same manga under different URLs, the carousel currently shows both. Lower-impact than taste-profile dedup since the 30-cap bounds visual noise.
