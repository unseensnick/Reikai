# Suggestions / Related-Mangas — porting plan

Working notes for adding Komikku-style related-manga suggestions to Yōkai-Y2K, plus a Y2K-original personalization layer that re-ranks (and adds to) the result pool using the user's tracker library, with explicit anti-echo-chamber controls.

**Status:** Phases 1–3 are done. Phases 4–8 are not started — read this doc, the "Implementation status & decisions" section below, and the still-open questions at the bottom, then start Phase 4.

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
| 3. Tracker-backed recommendations | ✅ done | (this commit) |
| 4. Taste profile | ⏸ next | |
| 5. Active candidate injection | ⏸ not started | |
| 6. Rerank + anti-echo | ⏸ not started | |
| 7. Settings UI | ⏸ not started | |
| 8. i18n + `docs/related-mangas.md` | ⏳ partial | CHANGELOG bullets land per phase; dedicated doc not yet written |

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
| 4. Taste profile (fetcher + cache + compute) | Y2K | 4–5 | ⏸ next |
| 5. Active candidate injection (tag-search + cross-recommendation) | Y2K | 3–4 | ⏸ |
| 6. Rerank + anti-echo (formula + diversity cap + novelty + exploration slots) | Y2K | 4–5 | ⏸ |
| 7. Settings UI (toggles + sliders + filters + refresh) | Y2K | 3–4 | ⏸ |
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

### Still open (Phase 4+ territory)

- **Where does the rerank logic live** — dedicated `RecommendationRanker` use-case in [`domain/`](../domain/) (clean separation, recommended) vs. presenter (couples concerns).
- **Tag overlap calculation** — exact-match (default) vs. fuzzy ("Sci-Fi" vs "Science Fiction"). Start exact + a small tracker-side normalization map for the worst offenders.
- **Score normalization across trackers** — AniList 0–100, MAL 1–10, MU 0–10 → normalize to 0–1 before merging into the taste profile.
