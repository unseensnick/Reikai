# Related-mangas carousel

A horizontal carousel of similar / related titles, shown below the description on the manga details screen. Pulls from five independent input streams and merges them into one row with cross-pool dedup and fair-share slot allocation, so a single fast input can't dominate the result list and the same manga can't appear twice via different streams.

Base feature ported from [Komikku](https://github.com/komikku-app/komikku); Yōkai-Y2K adapts the UX shape (single merged carousel instead of a separate full-screen Recommends flow), adds a tracker slot-reservation algorithm, and layers a taste-profile-driven personalization layer on top (tag-search + cross-recommendation for adding candidates, plus a rerank step that reorders the pool against your taste profile and drops library-known entries). See [What's different from Komikku](#whats-different-from-komikku) at the bottom.

## Where it appears

*Manga details → below the description, above the start-reading button.*

The carousel renders as a horizontal RecyclerView. While loading, a skeleton row holds the slot at its final 230 dp height so the rest of the screen doesn't reflow when results arrive. Once results land, the skeleton swaps out and the carousel populates; if nothing comes back, the section hides entirely instead of leaving an empty row.

The fetch fires once when the screen first attaches, then caches in the presenter for the lifetime of the screen. Switching source via the chip row creates a fresh presenter and re-runs the full fetch.

## Where candidates come from

Five independent streams feed the same pool. The first three are the Komikku-equivalent baseline; the last two are the Y2K personalization layer.

### 1. Source-native related-manga API

Every HTTP-backed extension is opted in by default — `HttpSource` overrides `supportsRelatedMangas = true` and provides a default `fetchRelatedMangaList` that runs `relatedMangaListRequest` → `relatedMangaListParse` (defaults: fetch the manga details page, parse it via `popularMangaParse`). The ~377 Keiyoushi extensions that ship overrides for those hooks light up automatically: Madara-derived extensions (~342) parse `.related-reading-wrap`, Iken-derived extensions (~12) hit a dedicated `/api/recommendations` endpoint, GalleryAdults-derived extensions fall through the popular-manga parser, and ~15 standalone extensions override directly (koharu, luscious, mangago, mangafire, etc.). Extensions whose details page has no parseable listing produce empty results that get absorbed by the carousel's dedup + 30-cap; sources can also opt out explicitly with `disableRelatedMangas = true`.

### 2. Keyword-search fallback

Independently of native support, unless a source sets `disableRelatedMangasBySearch = true`, the title is split into keywords (single-character tokens dropped, digit-only tokens dropped, punctuation stripped) and each keyword runs through the source's standard search endpoint. Results stream in as each keyword completes, so the carousel fills incrementally rather than waiting for all keywords to finish.

### 3. Tracker recommendations

Three public tracker endpoints, each running without a tracker sign-in:

- **AniList** — GraphQL `Media.recommendations`
- **MyAnimeList** — Jikan v4 `/manga/{id}/recommendations` (the official MAL API doesn't expose recommendations)
- **MangaUpdates** — v1 `/series/{id}` community-recommendations array

Per tracker: if the user has a track entry for the current manga on that tracker, the recommendation lookup uses the existing `media_id` directly. Otherwise a title-search resolves the `media_id` first, then the recommendation fetch runs. So tracker-backed recommendations work even on untracked manga.

### 4. Tag search on current source *(Y2K)*

The user's top three taste-profile tags (see [Taste profile](#taste-profile) below) run as searches against the current source via `getSearchManga`. ~3 calls per page open. Stays on the current source — no source-switching while reading. Limitation: depends on the source supporting tag-style search; on sources that only do title search this contributes nothing (silently no-ops).

Gated by Settings → Library → Recommendations → Candidate injection → **Tag search on current source** *(default on)*.

### 5. Cross-recommendation from favorites *(Y2K)*

The user's top-rated tracked manga (normalized score ≥ 0.8, status COMPLETED or READING, capped at the top 5 by score) get looked up on the current source by title via `getSearchManga`. For each match, the source's native related-mangas list is fetched and pushed into the pool. Heavier (~5–10 network calls per page open) but higher precision: surfaces matches the user has already implicitly endorsed.

Falls back gracefully when a favorite isn't on the current source — that favorite is just skipped. Also skipped silently when the taste profile is empty.

Gated by Settings → Library → Recommendations → Candidate injection → **Cross-recommendation from favorites** *(default on)*. Also structurally gated on `CatalogueSource.supportsRelatedMangas` — true by default for every HTTP extension.

## Taste profile

A `Map<Tag, Score>` derived from the user's tracked manga across the configured trackers. Phase 4 builds this; Phase 5's tag-search and cross-recommendation streams consume it.

### Data sources

Two layers, unioned at compute time:

- **Layer A — local** — joins the user's existing `manga_sync` rows with their library `mangas` and reads tags directly. Currently-reading manga only.
- **Layer B — remote** — pulls each enabled tracker's library via that tracker's API and caches the result locally in the SQLDelight `tracker_library_cache` table (with `tracker`, `remote_id`, `title`, `score`, `status`, `tags`, plus nullable `mal_id` and `anilist_id` cross-ref columns). Catches manga the user has rated then removed from their library — supports the "rate, then clean up" workflow that Layer A alone would lose.

### Cross-tracker dedup

The same manga tracked on AniList + MyAnimeList + Kitsu would otherwise count three times in the profile, inflating those tags. `GetTrackedEntries.dedupedAcrossTrackers()` runs a two-pass collapse:

1. **Pass 1: dedup by `mal_id`** — AniList carries `idMal` natively, MAL is its own id, Kitsu resolves it via `mappings` records (`externalSite == "myanimelist/manga"`)
2. **Pass 2: dedup by `anilist_id` on Pass 1 results** — catches the "AniList manhwa with no `idMal` + Kitsu with the AniList mapping but no MAL one" gap that Pass 1 misses

Both passes prefer AniList > MAL > Kitsu (richest tag taxonomy first). Entries with neither cross-ref pass through and only dedup within their own tracker.

### Cache lifecycle

Durable storage (rides the regular DB backup pipeline) instead of a time-based TTL. Refresh paths:

- **Manual** — Settings → Library → Recommendations → **Refresh now** (60 s cooldown after each press)
- **Event-driven** — Y2K's own tracker writes (add/update via the in-app track sheet) update the cache row in place via `InsertTrack` / `UpdateTrack`
- **Optional auto-refresh** — Settings dropdown: `Never` (default) / `7 days` / `30 days`. Conservative default; opt-in for users who want a fail-safe

Rate-limit guards: sequential per-tracker fetch (not parallel), honor `Retry-After` on 429, no background WorkManager job, no app-launch refresh.

### Trackers in scope

AniList, MyAnimeList, Kitsu. MangaUpdates / Shikimori / Bangumi were considered but dropped — the maintainer doesn't use them and won't ship untested library fetchers. Self-hosted Komga / Kavita / Suwayomi also excluded (no canonical tag taxonomy).

### Tag scoring

Per tag `t`:

```
score(t) = Σ (rating × status_weight) / Σ |status_weight|
            over manga tagged with t and with non-zero status_weight
```

| Status | Weight |
|---|---|
| COMPLETED | +1.0 |
| READING | +0.7 |
| ON_HOLD | +0.3 |
| PLAN_TO_READ | 0.0 (signal-free) |
| DROPPED | −1.0 |
| UNKNOWN | 0.0 |

Unrated entries (`score = -1.0`) substitute `0.5` for `rating` so status still signals direction with neutral magnitude. Denominator uses `|status_weight|` (not raw `status_weight`) to avoid a divide-by-zero when a tag has equal COMPLETED + DROPPED counts. Result is clamped to `[-1.0, +1.0]`.

`topTags(n)` returns the n highest-scoring tags — Phase 5's tag-search consumes this with `n = 3`.

## How clicks work

The carousel branches on the origin of each card:

- **Source-origin card** (came from source-native or keyword-search) — opens the manga's detail page in the current source. If the entry isn't yet a local DB row, it's inserted on demand (mirrors how Global Search resolves results).
- **Tracker-origin card** (came from one of the three trackers) — opens Global Search pre-filled with the manga's title, so you can pick an installed source to read it on. Tracker URLs don't belong to any installed extension, so opening one directly would create an orphan DB row — the Global Search detour avoids that.

## Pool composition

A single deduplicated pool, capped at 30 entries:

- **URL dedup** within each source's URL space — handled by a `LinkedHashSet<RelatedMangaCandidate>` keyed by `manga.url`. Tracker URLs (anilist.co/...) and source URLs (mangadex.org/...) live in distinct namespaces, so url-keyed dedup can't catch the cross-stream case.
- **Cross-pool title dedup** — handles the cross-namespace case. A parallel `HashSet<String>` alongside the accumulator tracks normalized titles (`lowercase + trim + collapse internal whitespace`); the first arrival for a given title key wins. So "Solo Leveling" appearing via source-native, AniList recommendations, and a favorite's related list collapses to one card. Source-origin entries naturally win the race since they're populated by a single fast call and route directly to the reader on tap — exactly the right preference.
- **30-cap** is split with a soft reserve: up to 12 slots are held for tracker-origin entries, distributed round-robin across the three trackers (~4 each) so a fast-returning tracker can't eat the whole reserve. Empty trackers cede their share. Either side of the source/tracker split cedes any unfilled capacity to the other.

The reserve and round-robin exist because tracker endpoints have asymmetric speeds — Jikan typically returns 90+ items in ~250 ms, AniList in ~400 ms, MangaUpdates in ~800 ms — so without an explicit reservation the faster trackers would consume every available slot before the slower ones responded.

Title normalization for the cross-pool dedup is intentionally minimal — case + whitespace only, no punctuation stripping, no diacritic folding, no cross-script collapse. Aggressive normalization risks collapsing legitimately different titles ("Café" vs "Cafe"); start conservative and extend [`normalizeTitleForDedup`](../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaDetailsPresenter.kt) if observation shows common false negatives.

## Reranking *(Y2K)*

Once the merged pool is assembled, [`RecommendationRanker`](../app/src/main/java/eu/kanade/tachiyomi/data/recommendation/RecommendationRanker.kt) reorders the source-origin slice against the user's taste profile and drops anything already in the library. Tracker-origin entries keep their round-robin fairness ordering — their recommendations are already personalized by construction and rarely carry parseable tags.

### Anti-echo (always on)

Candidates whose `(sourceId, manga.url)` is already a library entry are dropped before scoring. Tracker-origin candidates skip the filter — their URLs are tracker URLs and wouldn't match a library row anyway. Runs independently of the rerank toggle: turning Rerank by taste off restores Phase 5 ordering, but library entries stay hidden.

### Scoring

For each source-origin candidate:

```
final_score = 0.7 × popularity_rank + 0.3 × (taste_score + novelty_boost)
```

- `popularity_rank` is the candidate's inverse position in the merged pool's arrival order (top of the pool ≈ 1.0).
- `taste_score` is the mean of `tagScores[t]` over the candidate's `SManga.getGenres()` tags (lowercased + trimmed). Tags not in the profile contribute 0.0. Untagged candidates score 0.0 on the taste axis and naturally land near the popularity-only ordering.
- `novelty_boost` rewards tags the user has *few* tracked entries for: `0.2 × min(2.0, ln(1 + totalEntries / Σ tagEntryCounts[t]))`. Capped to avoid blowing up on extremely rare tags.

### Exploration slots

`⌈sourceSize × 0.2⌉` slots (~4 of the visible source slice) stay in the pool's original popularity order, pulled from the top of the unsorted source slice. Guarantees the user always sees "what the source thinks is broadly relevant" no matter how strong the taste signal — built-in guard against echo collapse.

### Diversity cap

No more than 2 of the taste-ranked picks may share the same dominant tag (the candidate's highest-affinity tag in the profile). Walks the sorted list top-down; offenders go to a deferred list that's drained into any remaining slots at the end. Single pass, no re-sort.

### Empty-profile path

When the taste profile has no entries or no scored tags, scoring is bypassed and the pool returns with only anti-echo applied. Matches the Phase 5 pattern: features that need a populated profile degrade silently to baseline behaviour when the profile is empty.

Defaults — `w_personal = 0.3`, `w_serendipity = 0.2`, `maxPerDominantTag = 2` — are hardcoded for now. Phase 7 will surface sliders that bind to these positions.

## Settings

*Settings → Library → Recommendations.*

Four sections:

**Recommendation sources** *(Komikku baseline)*

- **Tracker-backed recommendations** *(master, default on)* — turn the entire tracker-recommendation stream off without affecting source-native + keyword-search results.
- AniList / MyAnimeList / MangaUpdates *(per-tracker, default on)* — toggle each tracker independently. Sub-toggles disable when the master toggle is off.

**Taste profile** *(Y2K)*

- **Pull library from these trackers** — per-tracker toggles for AniList / MyAnimeList / Kitsu. Default off; greyed out unless the tracker is logged in.
- **Auto-refresh tracker library** — `Never` (default) / `every 7 days` / `every 30 days`.
- **Refresh now** — manual refresh button. 60 s cooldown after each press.
- **Last refresh** — per-tracker timestamp summary line.

**Candidate injection** *(Y2K)*

- **Tag search on current source** *(default on)* — runs top-3 taste-profile tags as searches on the current source.
- **Cross-recommendation from favorites** *(default on)* — runs top-5 favorites' related-mangas lists on the current source.

**Reranking** *(Y2K)*

- **Rerank by taste** *(default on)* — reorders the source-origin slice by `popularity_rank + taste_score + novelty_boost`, reserves ~20% of slots for exploration picks, and caps repeated dominant tags at 2. Anti-echo (drop library-known URLs) runs whether this toggle is on or off.

Source-native and keyword-search streams (#1 and #2 above) are governed by source-level extension flags (see [Where candidates come from](#where-candidates-come-from)), not user-facing settings.

## What's different from Komikku

The Komikku-baseline streams (#1-3 above) are a direct port — same source-API contract, same opt-in flags, same tracker-recommendation endpoints, same `media_id`-or-search dispatch, same `RECOMMENDS_SOURCE = -1L` sentinel for tracker-origin cards, same `HttpSource` baseline override for `supportsRelatedMangas = true`. The user-visible shape differs in a few places:

- **Single merged carousel** instead of Komikku's separate per-tracker sections on a dedicated Recommends screen. Tracker results live alongside source-native results in one row; Komikku gives each tracker its own screen section.
- **Tracker slot reservation + round-robin** algorithm to share the 30-cap fairly across trackers. Komikku doesn't need this since its sections are independent and pre-sized.
- **Cross-pool title dedup** beyond URL-keyed dedup, so the same manga can't show up as separate cards via different streams. Komikku dedups by `mangaId` at render time after each stream's been resolved into local DB rows; Y2K dedups by normalized title at insertion, which catches cross-namespace cases (tracker URL vs source URL) more directly.
- **`@Serializable` DTOs** for all tracker responses instead of Komikku's raw `JsonObject` / `jsonArray` traversal — matches the conventions Yokai's existing tracker code uses elsewhere.
- **Settings sub-screen** *(Settings → Library → Recommendations)* — Komikku exposes per-source recommendation flags differently; Yōkai-Y2K has a dedicated screen with three sections (Recommendation sources, Taste profile, Candidate injection).
- **RecyclerView + FlexibleAdapter** instead of Compose `LazyRow` — Yōkai's manga details screen is still Conductor + ViewBinding, so the carousel matches that framework.
- **Komikku's MangaUpdates `category_recommendations` ("similar") variant** is not ported — Yōkai-Y2K uses the community-recommendations variant only.

The personalization layer (taste profile + active candidate injection, streams #4 and #5) is Y2K-original — Komikku has no equivalent.

Komikku also has source-specific recommendation integrations (MangaDex similar, ComicK) and a full bulk-favorite UI on the dedicated Recommends screen — neither is ported, since the inline carousel UX doesn't have a place for them.
