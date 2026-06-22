# Recommendations & related carousel (P6)

A per-manga "Related" carousel on the details screen that blends five suggestion streams into one ranked list, reranked by a taste profile built from the user's tracker libraries, with a "See all" browse grid for bulk-adding.

## Goal

Show the user "more like this" on a manga's details screen: a horizontal carousel of related titles drawn from multiple sources, ordered by how well each match fits the user's taste, with a full-screen grid (See all) to explore the whole pool and add titles to the library in bulk. Ported from the old Yokai build onto Mihon, re-typed to Mihon's immutable models.

## Why

A single source's own "related" list is thin and varies wildly in quality. The trackers (AniList, MyAnimeList, MangaUpdates, Shikimori) maintain rich human-curated similarity graphs, and the user's tracked libraries encode what they actually like. Merging these and reranking by taste turns scattered weak signals into one strong, personalized suggestion list, the kind of "for you" surface Mihon does not ship.

## Approach

When a manga opens, the carousel queries several sources for "what's similar to this?", pools all the answers, removes anything already in the library or duplicated, sorts the rest so the titles that best match the user's taste come first, and shows them. "See all" opens the full pool as a grid for selecting many titles at once and adding them to the library. Novel recommendations are deliberately not built (see Status).

### The five input streams, merged into one carousel

A loader fans out across up to five kinds of suggestion source, runs them in parallel, and streams results into one accumulator so cards appear progressively rather than after every source finishes ([RelatedMangasLoader.kt](../../../app/src/main/java/reikai/domain/recommendation/RelatedMangasLoader.kt)):

1. **Source-native related.** The manga's own source, via Mihon's `CatalogueSource.getRelatedMangaList` contract (landed in P1). Available for every manga whose source supports it.
2. **Tracker recommendations by id.** For a manga tracked on a recs-capable tracker, the loader fetches that tracker's recommendation list using the exact remote id (`getMediaContext` / `getRecsById`), so no fuzzy matching is needed.
3. **Tracker recommendations by title search.** For recs-capable trackers the manga is *not* tracked on, `RecommendationsFetcher` runs a single title search to find the remote id, then pulls that tracker's recommendations. The loader passes `skipTrackerIds` so a tracker already handled in stream 2 is never queried twice ([RecommendationsFetcher.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationsFetcher.kt)).
4. **Cross-recommendations from your library** ("Because you're reading X"). See the tracker-gated providers below.
5. **Tag-search candidates from your taste** ("Matching your taste: ..."). Also tracker-gated.

Streams 4 and 5 come from `TasteCandidateFetcher` ([taste/TasteCandidateFetcher.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/TasteCandidateFetcher.kt)).

Each candidate carries an `origin` (source-native, a specific tracker, or a cross-rec from a named title) so the See-all grid can group by it ([RecommendationOrigin.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationOrigin.kt)).

**Dedup and agreement.** The loader normalizes titles (NFKD + strip diacritics + fold punctuation/case, so "café" = "cafe", fullwidth = ASCII; [TitleNormalizer.kt](../../../app/src/main/java/reikai/domain/recommendation/TitleNormalizer.kt)) and also keeps each candidate's alternate-title set, so a title that arrives under different names from two streams is recognized as one. A second dedup pass matches by resolved local manga id. When several streams agree on the same title, the agreement count is kept (not discarded) and feeds the ranker. Titles already in the library or already read/completed/dropped on a tracker are filtered out ([RecommendationHideFilter.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationHideFilter.kt), [BuildRecommendationHideFilter.kt](../../../app/src/main/java/reikai/domain/recommendation/BuildRecommendationHideFilter.kt)).

**Caching.** Results are cached in memory (~30 min freshness, cache-then-refresh) so reopening a manga is instant, with a background refresh when stale ([RelatedMangaCache.kt](../../../app/src/main/java/reikai/domain/recommendation/RelatedMangaCache.kt)). Fetches are on-demand only, debounced, and per-host rate-limited with identifying User-Agents (the Shikimori IP-ban and Jikan caching constraints are why; see Decisions).

### The taste-profile rerank

The taste profile is the personalization engine. It is computed from the user's *tracker* libraries, not the local library: for each enabled tracker the app pulls the user's list with genres/tags inline in the list call (never per title, which would blow rate limits) and persists it to a SQLDelight cache ([taste_library.sq](../../../data/src/main/sqldelight/tachiyomi/data/taste_library.sq), created by migration `13.sqm`). The per-tracker pull fetchers live in [taste/](../../../app/src/main/java/reikai/domain/recommendation/taste/) (`AnilistLibraryFetcher`, `MyAnimeListLibraryFetcher`, `KitsuLibraryFetcher`, `ShikimoriLibraryFetcher`, `BangumiLibraryFetcher`). The pull runs on a schedule (never / 7d / 30d, default manual) via WorkManager and `RefreshTrackerLibrary`; the profile is recomputed locally from the cache by `ComputeTasteProfile`, so opening a manga never hits a tracker for taste data.

`RecommendationRanker` ([RecommendationRanker.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationRanker.kt)) scores each pooled candidate against the profile (tag affinity) plus the cross-source agreement term, and the carousel + grid render in that order. Settings sliders (recommendation-style, serendipity) tune the weighting.

### The tracker-gated cross-rec + tag-search providers

The two taste-injected streams (4 and 5) use the trackers' own similarity graph rather than genre heuristics, because a source's 3-8 broad genres carry almost no similarity signal (romance titles leaked in just for sharing "Drama"). They only run when the current manga is tracked on a recs-capable tracker (AniList / MAL / MangaUpdates / Shikimori); Kitsu and Bangumi links do not qualify, and an untracked manga gets no injected streams.

- **Cross-rec (Option C):** intersect the user's highly-rated tracked titles with the current manga's own recommendation list (pure id intersection, the tracker itself vouches for the similarity), then surface *those* titles' recommendations, tagged "Because you're reading X". Seeds picked by `selectCrossRecSeeds`.
- **Tag-search (Option B):** feed the search the manga's clean *tracker* genres (e.g. AniList's Action/Psychological) ranked by the user's taste, falling back to source genres when a tracker returns none. Tags picked by `selectContextualTags`.

The loader fetches the current manga's recommendation graph once (via `getMediaContext`, which AniList overrides to return genres + recommendations in one query) and shares it between the carousel's tracker-recs and these injected streams, so AniList recs are never fetched twice ([TrackerRecommendations.kt](../../../app/src/main/java/reikai/domain/recommendation/TrackerRecommendations.kt), [AnilistRecommendations.kt](../../../app/src/main/java/reikai/domain/recommendation/AnilistRecommendations.kt)). The two injection toggles are hidden in settings unless a recs-capable tracker is logged in.

### See-all browse

A Voyager `Screen` showing the full ranked pool as a cover grid, seeded from the carousel's pool ([browse/RelatedMangasBrowseScreen.kt](../../../app/src/main/java/reikai/presentation/recommendation/browse/RelatedMangasBrowseScreen.kt)). It supports multi-select bulk add-to-library through Mihon's category-assignment flow, skips items already favorited, and offers a toggle between the flat taste-ranked grid and an origin-grouped view with section headers ("From your AniList recommendations", "Because you're reading X", "From this source"). Grouping is browse-only; the carousel stays flat-ranked. The card UI lives in [browse/RelatedMangasBrowseContent.kt](../../../app/src/main/java/reikai/presentation/recommendation/browse/RelatedMangasBrowseContent.kt) with its model [browse/RelatedMangasBrowseScreenModel.kt](../../../app/src/main/java/reikai/presentation/recommendation/browse/RelatedMangasBrowseScreenModel.kt).

The carousel itself is [RelatedMangaCarousel.kt](../../../app/src/main/java/reikai/presentation/recommendation/RelatedMangaCarousel.kt): a `LazyRow` of cover cards with a shimmer skeleton while loading, an in-library badge on resolved favorites, and progressive rendering as the loader streams candidates in. Tapping a card resolves the candidate to a local manga (or global-searches a tracker-origin candidate) and opens it.

## Key files

Net-new engine (`reikai.domain.recommendation`):

- [RelatedMangasLoader.kt](../../../app/src/main/java/reikai/domain/recommendation/RelatedMangasLoader.kt): orchestrates the five streams, dedup, agreement, accumulator.
- [RecommendationsFetcher.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationsFetcher.kt): tracker-recs fan-out (`skipTrackerIds` to avoid double-fetch).
- [RecommendationRanker.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationRanker.kt), [RecommendationsConstants.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationsConstants.kt): scoring (pure, unit-tested).
- [RelatedMangaCache.kt](../../../app/src/main/java/reikai/domain/recommendation/RelatedMangaCache.kt): in-memory cache-then-refresh.
- [TitleNormalizer.kt](../../../app/src/main/java/reikai/domain/recommendation/TitleNormalizer.kt), [RelatedMangaCandidate.kt](../../../app/src/main/java/reikai/domain/recommendation/RelatedMangaCandidate.kt), [RecommendationOrigin.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationOrigin.kt): candidate model + dedup + provenance.
- [RecommendationHideFilter.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationHideFilter.kt), [BuildRecommendationHideFilter.kt](../../../app/src/main/java/reikai/domain/recommendation/BuildRecommendationHideFilter.kt): library / read-status suppression.
- [TrackerRecommendations.kt](../../../app/src/main/java/reikai/domain/recommendation/TrackerRecommendations.kt) + the four providers (`AnilistRecommendations`, `MyAnimeListRecommendations`, `MangaUpdatesRecommendations`, `ShikimoriRecommendations`) and their `dto/` classes; [RecommendationProviders.kt](../../../app/src/main/java/reikai/domain/recommendation/RecommendationProviders.kt) registry.
- [ReikaiRecommendationPreferences.kt](../../../app/src/main/java/reikai/domain/recommendation/ReikaiRecommendationPreferences.kt): the 21 settings keys.

Net-new taste layer (`reikai.domain.recommendation.taste`):

- [taste/ComputeTasteProfile.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/ComputeTasteProfile.kt), [taste/TasteProfile.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/TasteProfile.kt), [taste/GetTasteProfile.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/GetTasteProfile.kt).
- [taste/TasteCandidateFetcher.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/TasteCandidateFetcher.kt): cross-rec + tag-search injection (`selectCrossRecSeeds`, `selectContextualTags`).
- The five `*LibraryFetcher.kt` pull fetchers + [taste/RefreshTrackerLibrary.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/RefreshTrackerLibrary.kt), [taste/TasteLibraryRepository.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/TasteLibraryRepository.kt), [taste/TasteNormalize.kt](../../../app/src/main/java/reikai/domain/recommendation/taste/TasteNormalize.kt).

Net-new UI (`reikai.presentation.recommendation`):

- [RelatedMangaCarousel.kt](../../../app/src/main/java/reikai/presentation/recommendation/RelatedMangaCarousel.kt), the three `browse/` files, [SettingsRecommendationsScreen.kt](../../../app/src/main/java/reikai/presentation/recommendation/SettingsRecommendationsScreen.kt).

Persistence:

- [data/.../taste_library.sq](../../../data/src/main/sqldelight/tachiyomi/data/taste_library.sq) + migration [13.sqm](../../../data/src/main/sqldelight/tachiyomi/migrations/13.sqm).

`// RK` islands on Mihon files:

- [MangaScreen.kt](../../../app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt): carousel item, phone (in-scroll) and tablet (start pane).
- `MangaScreenModel.kt`: state + debounced load + `tracks` plumbing.
- [SettingsLibraryScreen.kt](../../../app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsLibraryScreen.kt): entry to `SettingsRecommendationsScreen` (Settings → Library → Recommendations).
- The tracker `*Api.kt` recs/library-pull methods, `DomainModule` / `PreferenceModule`, `i18n` strings.

## Status

Shipped. P6 is done (Roadmap P6 row), on-device verified (phone + tablet). The carousel, taste-profile rerank, the four tracker recs providers + five library-pull fetchers, the tracker-gated cross-rec + tag-search, the See-all grid with bulk add and origin grouping, and the settings screen are all live. The taste fetchers + ranker were touched again in the Tier 0 duplication cleanup (shared `normalizeTrackerScore` + `String.toTagKey()`), confirmed sync-neutral.

**Novel recommendations are parked,** not built. LN sources do not expose related-title metadata, and getting the mainstream trackers to track light novels at all is unreliable, so a novel carousel would have almost no input signal. Revisit only if novel tracking proves out.

For the user-facing architecture overview of how the carousel blends and ranks suggestions, see [docs/related-mangas.md](../../related-mangas.md). This doc does not duplicate it; it records the P6 plan and decisions.

## Decisions & tradeoffs

- **Tracker similarity graph over genre heuristics for injection.** Cross-rec and tag-search are gated on the manga being tracked on a recs-capable tracker, because a source's broad genres carry too little signal (every genre-overlap band-aid leaked unrelated titles). The cost is that untracked manga get only the source-native + title-searched tracker streams, no taste injection. Accepted: the gate is the whole point, and the base carousel still works for every manga.
- **MangaUpdates is recs-only, no taste-pull.** Its library-list endpoint is undocumented and may not exist; the old code excluded it. The other five trackers give clean inline-genre pulls, already better than the old three.
- **Kitsu and Bangumi contribute taste only, no recs.** Neither has a recommendations endpoint (Kitsu exposes structural relationships, not taste recs). They register no recs provider; no UI special-case.
- **Genres must come inline with the library list, never per title.** A per-title genre fetch would multiply requests into a rate-limit blowup. Every kept tracker returns genres inline (AniList one GraphQL call; MAL `fields=node(genres)`; Kitsu `include=manga.categories`, 500/page; Shikimori via its GraphQL `userRates` since the REST `user_rates` omits genres; Bangumi `subject.tags`).
- **Conservative per-host rate limiting + identifying User-Agent, non-negotiable for Shikimori.** Shikimori IP-bans on a wrong/missing User-Agent and has a hard 5/s & 90/min cap; the limiter runs well under (2/s, 60/min) with the registered app name. The limiter lives on a single lazily-built per-process client so its windows persist across fetches (rebuilding per fetch reset them, the old code's key bug).
- **On-demand fetch + two-tier cache, taste pull off the open path.** Recs load only when the carousel shows (debounced, skipped if cache-fresh); the taste library pull is scheduled + persisted and the profile recomputes locally, so opening a manga never hits a tracker for taste data. This satisfies Jikan's mandatory caching and keeps the UI fast.
- **No fuzzy title matching in dedup.** Strong normalization + alt-title sets + an id second-pass only; fuzzy similarity risks false merges.
- **Origin grouping is browse-only.** The carousel stays flat-ranked (less clutter in a small horizontal strip); the See-all grid offers the grouped view since the provenance data already rides on each candidate, no extra fetching.
- **Tablet placement.** The carousel renders in the two-pane details start pane (after the description); the See-all grid is the escape hatch if the pane feels cramped.
