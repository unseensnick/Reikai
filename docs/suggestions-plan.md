# Suggestions / Related-Mangas — porting plan

Working notes for adding Komikku-style related-manga suggestions to Yōkai-Y2K, plus a Y2K-original personalization layer that re-ranks (and adds to) the result pool using the user's tracker library, with explicit anti-echo-chamber controls.

**Status:** not implemented. This document is the spec. Read it, settle the open questions at the bottom, then start phase 1.

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

| Phase | Layer | Hours |
|---|---|---|
| 1. Source-API contract + keyword fallback | Komikku | 3–4 |
| 2. UI carousel on manga details | Komikku | 2–3 |
| 3. Tracker-backed paging sources | Komikku | 3–4 |
| 4. Taste profile (fetcher + cache + compute) | Y2K | 4–5 |
| 5. Active candidate injection (tag-search + cross-recommendation) | Y2K | 3–4 |
| 6. Rerank + anti-echo (formula + diversity cap + novelty + exploration slots) | Y2K | 4–5 |
| 7. Settings UI (toggles + sliders + filters + refresh) | Y2K | 3–4 |
| 8. i18n + `docs/related-mangas.md` | both | 1 |
| **Total** | | **23–30** |

Phases 1–3 ship a usable Komikku-equivalent on their own — reasonable to land as one PR. Phases 4–7 add the Y2K layer; can land as a follow-up PR once the Komikku baseline is stable.

A research/scoping commit before phase 1 — pinning the source-API function signature and confirming Yōkai's source layer is `suspend`-friendly here — is a good idea.

---

## Open questions to resolve before coding

- **Where does the rerank logic live** — dedicated `RecommendationRanker` use-case in [`domain/`](../domain/) (clean separation, recommended) vs. presenter (couples concerns).
- **Tag overlap calculation** — exact-match (default) vs. fuzzy ("Sci-Fi" vs "Science Fiction"). Start exact + a small tracker-side normalization map for the worst offenders.
- **Score normalization across trackers** — AniList 0–100, MAL 1–10, MU 0–10 → normalize to 0–1 before merging into the taste profile.
- **Per-rerank pool size** — confirmed during phase 1. If Komikku returns ≤30, rerank in-process; if more, sample or paginate.
- **Function signature on the source API** — flat `suspend fun getRelatedMangaList(manga: SManga): List<SManga>` (default, simpler) vs. Komikku's keyword-bucketed `List<Pair<String, List<SManga>>>` (richer, lets the UI label each bucket "from search 'kaiju'"). Decide before phase 1.
- **Cache invalidation for the candidate pool** — fetch once per `MangaDetailsController` instance, or share across instances? Per-instance is fine for v1.
