# Recommendations

## Purpose

A manga's details page carries a Related row: titles similar to the one open, pooled from the source, from the public recommendation lists of four trackers and from the user's own tracker libraries, then reordered toward the user's taste. "See all" opens the whole pool as a grid for bulk-adding to the library. Novels have no recommendations; see Decisions.

## How it works

### The load

`MangaScreen` calls `MangaViewModel.loadRelatedMangas` once per open. It does nothing while `enableRelatedMangas` (`pref_enable_related_mangas`) is off, so the source is spared every request. With `relatedPlacement` set to `MENU` the inline row is hidden and the details overflow menu opens the See-all grid instead, but the load still runs, since the grid reads the same cache.

The load first serves `RelatedMangaCache` (in memory, keyed by manga id, `FRESH_MS` of 30 minutes). A fresh, complete entry ends the load. A stale one is shown at once and refreshed in the background. Before fetching, the load waits for the entry's own details and chapters (`awaitOwnDataLoaded`), because both hit the same host. It also starts `RefreshTrackerLibrary.refreshIfStale` out of band, so a taste pull lands for the next open rather than delaying this one.

### The streams

`RelatedMangasLoader.load` runs three coroutines into one mutex-guarded `Accumulator` and pushes a snapshot to the caller every time a batch adds something, so cards appear as they arrive:

- **Source-native.** Mihon's `CatalogueSource.getRelatedMangaList` on the entry's own source. Origin `SourceNative`.
- **Tracked trackers.** For each track whose tracker has a provider (`RecommendationProviders.forTracker`: AniList, MyAnimeList through Jikan, MangaUpdates, Shikimori), `getMediaContext` fetches that title's recommendations and genres once. Its recommendations join the pool when that tracker's switch is on; the same contexts then feed `TasteCandidateFetcher`. The tracks come from `GetTracksInGroup`, so a merged series uses the whole group's tracks.
- **Untracked trackers.** `RecommendationsFetcher` covers every enabled tracker the entry is not tracked on (`skipTrackerIds`) with one title search, then that title's recommendations. Origin `Tracker`.
- **Taste injection**, inside `TasteCandidateFetcher`, and only when at least one media context came back (the entry is tracked on a recommendations tracker). Cross-rec seeds are the user's tracked titles on that tracker that appear in the entry's own recommendation list, scored at least 0.8 and Completed or Reading (`selectCrossRecSeeds`, at most five); their recommendations join as `CrossRec(fromTitle)`. Tag search runs the source's own search for up to three of the entry's tracker genres the user scores positively (`selectContextualTags`), falling back to the source's genres when no tracker returned any; results join as `TagSearch(tag)`.

Every call goes through `cappedRecommendationCall`: a 15 second cap, a failure logged and dropped, a cancellation propagated. One tracker failing never blocks the others.

Tracker candidates carry `sourceId = RECOMMENDS_SOURCE` (-1), the tracker id and the remote id. Their URL fits no installed extension, so a tap opens `EntryGlobalSearchScreen` on the title (`relatedDestination`); a source candidate is stored through `NetworkToLocalManga` and opens as a `MangaScreen` (`localIdOf`).

### Dedup and agreement

The accumulator drops the entry itself (by URL and by title), then dedups on two keys: the candidate's URL (`RelatedMangaCandidate` equality) and its title set (`titleKeys`: the title plus a tracker's alternate titles, each through `TitleNormalizer`, which is NFKD, strip marks, lowercase, fold non-alphanumerics). The first arrival wins. Each title key also counts how many pushes carried it, at most once per push, and the snapshot's `agreementByUrl` carries the highest count among a candidate's keys.

### Assembly on read

The cache holds the unranked pool. `PrepareRecommendationAssembly` builds a `RecommendationAssembly` from the current preferences, the hide filter and the taste profile, and both the carousel and See all read the pool through it:

1. Drop what `EnabledRecommendationStreams` excludes: a tracker whose switch is off, cross-rec or tag search while off, and everything tracker-derived while `includeTrackerRecommendations` is off.
2. Drop what the hide filter hides.
3. Rank with `RecommendationRanker`.
4. For the carousel only, cap at 30 (`CAROUSEL_CAP` in `MangaViewModel`), keeping up to 12 slots for tracker candidates taken round-robin across trackers. Either side cedes room the other cannot fill.

The carousel's "See all (N)" counts everything `shows` accepts, which is the uncapped assembly. See all draws the uncapped list plus `hidden`, the hidden part behind an eye toggle.

### Ranking

`RecommendationRanker` only reorders. Tracker candidates pass through at the end in arrival order; the cap's round-robin decides their share. Source candidates score `(1 - wPersonal) * popularity + wPersonal * (taste + novelty) + agreement`, where popularity is the candidate's position in the pool (arrival order), taste is the mean profile score over the candidate's genres, novelty rewards rarely seen tags (capped), and agreement is `0.5 * ln(count)`. `ceil(size * wSerendipity)` slots stay in the source's own order at the top, and a diversity cap demotes a third candidate sharing a dominant tag. `recommendationStyle` sets `wPersonal`, `serendipity` sets `wSerendipity`. With rerank off, or an empty profile, only the agreement sort applies.

### The taste profile

The profile is built from the user's tracker libraries, never the local library. Five `TrackerLibraryFetcher`s (AniList, MyAnimeList, Kitsu, Shikimori, Bangumi, listed in `ReikaiBindings.providesTrackerLibraryFetchers`) pull the whole list with genres inline in the list call, and `RefreshTrackerLibrary` stores each tracker's rows in `taste_library`, replacing only that tracker's. Each pull is off by default (`pullLibraryFrom*`) and runs only while logged in.

A pull runs from three places: `refreshIfStale` on a details open (any enabled tracker older than six hours), the periodic `TrackerLibraryRefreshWorker` (`trackerLibraryAutoRefreshHours`: off, weekly or monthly), and Refresh now in settings, a one-off worker behind a 60 second cooldown (`tryStartManual`). All three first drop the rows of any tracker whose pull switch is off (`dropUnrequestedTrackers`).

`ComputeTasteProfile` reduces the rows. Rows sharing a MAL id or an AniList id are one series, transitively (`DisjointSet`), keeping AniList's row over MyAnimeList's over Kitsu's. Per tag the score is the sum of rating times status weight over the absolute weights, clamped to -1..1: Completed 1.0, Reading 0.7, On hold 0.3, Dropped -1.0, Plan to read and unknown 0. An unrated entry counts as 0.5. Every tag goes through `toTagKeys` on both the profile and the candidate side, so a genre listed twice counts once.

### The hide filter

`BuildRecommendationHideFilter` builds two indexes per load. The library index (favorites by URL and source, their tracks by tracker and remote id plus AniList and MAL ids, their normalized titles) is always built. The status index draws from both the library's own tracks (`LocalTrackStatusMapper`) and `taste_library`, so a title tracked but not in the library is caught too. `RecommendationHideFilter.isInLibrary` matches by URL and source, then tracker id, then title. A card it marks is dimmed and badged; `hideInLibraryRecommendations` hides exactly what it marks. The four status filters (Reading and Completed, Dropped, On hold, Plan to read) hide through the status index. Every filter defaults off.

See all also subscribes to the favorites of the pool's sources, so a title added after the grid opened is marked. It bulk-adds through `MangaLibraryAdder`, skipping tracker candidates and titles already favorited; its "Added N" counts only adds that landed. A grouped view sections the grid by origin in first-appearance order (`State.sections`), and range select reads the same sections.

### Adult content

Recommendations apply no adult-content filter. MyAnimeList search and the MyAnimeList library pull both send `nsfw=true`; AniList's recommendations and library queries do not request `isAdult`. Adult titles therefore shape the taste profile and appear as candidates like any other. Two limits come from the services: Kitsu returns its NSFW categories only to an account whose own SFW filter is off, so on a default account Kitsu tags (and Kitsu's Fill from tracker genres) are shorter; and which sources exist at all follows the extension content-warning setting, which also bounds source-native and tag-search candidates.

### What each tracker's API can answer

Measured against the live services; these are the facts any proposal to filter adult content or widen the taste profile rests on.

| Tracker | Adult signal | What it means |
|---|---|---|
| AniList | `Media.isAdult` | Sexual only, one 18+ bucket; Ecchi is outside it |
| MyAnimeList | `nsfw`: `white` / `gray` / `black` | Undocumented by MAL itself; manga has no `rating` enum |
| Kitsu | `media.sfw` (`ageRating != R18`), `Category.isNsfw` | `sfw` never fires on violence but reads an unrated title as safe; flagged categories (25 of 243, Nudity and Yuri among them) are omitted from `nodes` unless the account's own SFW filter is off |
| Shikimori | `isCensored`, GraphQL catalogue only | Hentai, yaoi and yuri in one bucket; in the 500 most popular manga it added no explicit title a tag list missed and flagged 19 that are not explicit. `userRates` takes no censor parameter |
| Bangumi | `nsfw`, on the full subject only | Absent from the collections payload, so one call per entry; set on 14 of 50 adult-tagged books, 13 already caught by tags, and only visible when authenticated |
| MangaUpdates | none | Genres only |
| Hikka | `nsfw` | Undocumented, not server-filterable |
| MangaBaka | `content_rating`: `safe` / `suggestive` / `erotica` / `pornographic` | Sexual only; violence sits at `safe` |

A taste fetcher needs one list call carrying score, status and tags per entry:

| Tracker | Whole-list call | Score | Status | Tags |
|---|---|---|---|---|
| MDList | Yes (`FollowsHandler.fetchAllFollows`) | Batched (`mangasRating`) | Yes | On `MangaDataDto`, dropped by `MdUtil.createMangaEntry` |
| MangaBaka | Unconfirmed (`library.read` scope granted) | Yes | Yes | Needs a `/v1/series/{id}` call per title |
| Hikka | Unconfirmed (`readlist` scope granted) | Yes | Yes | Yes |
| MangaUpdates | None | Call per title | Yes | Call per title |
| Kavita | None | Yes | From read progress | None |
| Komga, Suwayomi | None | None | From read progress | Yes |

The recommendations client rate-limits per host (`RecommendationProviders`): AniList 85/min, Jikan 3/s and 58/min, MangaUpdates 30/min, Shikimori 2/s and 60/min (its hard cap is 5/s and 90/min).

## Key files

- `app/src/main/java/reikai/domain/recommendation/RelatedMangasLoader.kt`: `load`, `Accumulator`, `fetchMediaContexts`.
- `app/src/main/java/reikai/domain/recommendation/RecommendationsFetcher.kt`: the untracked-tracker title search, `skipTrackerIds`.
- `app/src/main/java/reikai/domain/recommendation/RecommendationAssembly.kt`: `RecommendationAssembly`, `EnabledRecommendationStreams`, `PrepareRecommendationAssembly`, `TRACKER_RESERVE`.
- `app/src/main/java/reikai/domain/recommendation/RecommendationRanker.kt`: `rank`, `applyDiversityCap`.
- `app/src/main/java/reikai/domain/recommendation/RelatedMangaCache.kt`: `put`, `replaces`, `FRESH_MS`.
- `app/src/main/java/reikai/domain/recommendation/RelatedMangaCandidate.kt`: `titleKeys`, `localIdOf`; `TitleNormalizer.kt`, `RecommendationOrigin.kt`, `RecommendationsConstants.kt` (`RECOMMENDS_SOURCE`).
- `app/src/main/java/reikai/domain/recommendation/RecommendationHideFilter.kt` and `BuildRecommendationHideFilter.kt`: `isInLibrary`, `shouldHide`, `IndexBuilder`.
- `app/src/main/java/reikai/domain/recommendation/TrackerRecommendations.kt`: the provider contract, `getMediaContext`; the four providers beside it and `RecommendationProviders.kt` (`forTracker`, the rate-limited `client`).
- `app/src/main/java/reikai/domain/recommendation/CappedRecommendationCall.kt`: `cappedRecommendationCall`.
- `app/src/main/java/reikai/domain/recommendation/ReikaiRecommendationPreferences.kt`: every key, `recommendationToggles`, `enabledStreams`, `buildRanker`.
- `app/src/main/java/reikai/domain/recommendation/taste/TasteCandidateFetcher.kt`: `selectCrossRecSeeds`, `selectContextualTags`.
- `app/src/main/java/reikai/domain/recommendation/taste/ComputeTasteProfile.kt`: `STATUS_WEIGHTS`, `dedupedAcrossTrackers`; `GetTasteProfile.kt`, `TasteNormalize.kt` (`toTagKeys`, `normalizeTrackerScore`).
- `app/src/main/java/reikai/domain/recommendation/taste/RefreshTrackerLibrary.kt`: `refreshIfStale`, `tryStartManual`, `dropUnrequestedTrackers`.
- `app/src/main/java/reikai/domain/recommendation/taste/TrackerLibraryFetcher.kt`: `isPullRequested`, `isEnabled`; the five `*LibraryFetcher.kt` beside it, and `LocalTrackStatusMapper.kt` (`trackStatusOf`).
- `app/src/main/java/reikai/data/recommendation/taste/TrackerLibraryRefreshWorker.kt`: `setupTask`, `startNow`.
- `domain/src/main/java/reikai/domain/recommendation/taste/TasteLibraryRepository.kt` and `data/src/main/sqldelight/tachiyomi/data/taste_library.sq`: the taste cache.
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`: `loadRelatedMangas`, `applyRelated`, `CAROUSEL_CAP`.
- `app/src/main/java/reikai/presentation/details/MangaEntryAdapter.kt`: `MangaRelatedCarouselCapability`; placed by `relatedCarouselItem` in `EntryDetailsContent.kt`.
- `app/src/main/java/reikai/presentation/recommendation/RelatedMangaCarousel.kt`, `RecommendationGridItem.kt`, `RelatedDestination.kt` (`relatedDestination`).
- `app/src/main/java/reikai/presentation/recommendation/browse/RelatedMangasBrowseViewModel.kt`: `sections`, `addSelectedToLibrary`; `RelatedMangasBrowseScreen.kt`.
- `app/src/main/java/reikai/presentation/recommendation/SettingsRecommendationsScreen.kt`: the settings screen.

## Invariants and traps

- **Settings apply on read, never into the cache.** A stream switched off must vanish from a pool cached before the change; filtering at fetch time alone left it showing for the freshness window, and indefinitely when a refresh came back empty. A new switch goes into `EnabledRecommendationStreams` and the loader both.
- **An empty or partial result never replaces a fuller cache entry** (`RelatedMangaCache.replaces`). A refused put keeps the old fetch time, so a failed refresh retries on the next open.
- **Agreement counts a title once per push.** MangaUpdates lists a series in both of its buckets; counting per candidate would invent cross-source agreement the ranker rewards.
- **One rule for "already in my library".** The badge and the hide filter both go through `isInLibrary`. A source can list one series under two URLs, so URL and source alone miss it and the title fallback is what catches it.
- **Tapping a marked card opens the source's own row for that URL**, which for a second URL is not the library's row. Resolving a card to the library copy is not built.
- **The taste purge is keyed to `isPullRequested`, never `isEnabled`.** `isEnabled` folds in the login, and a tracker that logs itself out would otherwise lose its whole profile contribution.
- **Never fetch genres per title.** Every library pull must carry genres in the list call; a per-title fetch multiplies requests into rate-limit bans.
- **The recommendations client is one lazy app-scoped `OkHttpClient`.** Its per-host limit windows live on it, so building a client per fetch resets them. Shikimori IP-bans a missing or browser User-Agent, so its provider sends `REIKAI_TRACKER_USER_AGENT`.
- **Providers use public endpoints over the shared client**, never a tracker's authenticated client, which throws for a logged-out user.
- **See all reads the cache by manga id.** Voyager constructor args must be serializable, and after process death the cache is empty, so the grid shows its empty state until the manga is reopened.

## Decisions

- **Taste injection is gated on tracker similarity, not genres.** A source's few broad genres carry too little signal and leaked unrelated titles; the tracker's own recommendation graph decides. An untracked entry gets source-native and title-searched tracker results only. Void if sources start returning fine-grained tags.
- **The profile comes from tracker libraries, cached and pulled off the open path.** A details open never waits on a tracker for taste data, which also meets Jikan's caching requirement. Void if a tracker offers a cheap per-request taste endpoint.
- **Five trackers feed the profile; MangaUpdates does not.** MangaUpdates has no collection endpoint and its list items carry neither score nor genres. Kavita, Komga and Suwayomi are excluded because their library is the user's own server, largely already local, and none returns score, status and tags together. MDList could feed it from the follows pull but is not built; MangaBaka and Hikka wait on confirming a list endpoint that carries tags.
- **Kitsu and Bangumi feed taste only.** Neither has a recommendations endpoint, so they register no provider and no recommendations switch.
- **Each tracker's switches are listed once.** `recommendationToggles` names exactly the trackers `forTracker` answers for, and each fetcher carries its own pull switch; settings, the enabled set and the pull read those lists. `RecommendationTogglesTest` pins the first.
- **The carousel reserves 12 of 30 slots for tracker picks.** A source that fills the cap alone would otherwise push every tracker pick out. Void if tracker results are ranked against source results on one scale.
- **No fuzzy title matching.** Normalized equality plus alternate titles and URL identity only, so distinct series are never merged.
- **Origin grouping is See-all only.** The carousel stays flat-ranked in its small strip; the provenance already rides on each candidate.
- **No adult-content filter.** It reached only the carousel and Fill from tracker, most tracker candidates carry no adult signal to screen (only AniList offers a clean flag), and the trackers' own flags either mix in BL and GL (Shikimori's `isCensored`) or are missing from the library call and sparse (Bangumi's `nsfw`). Void if the recommendation and library endpoints of most trackers expose a sexual-content flag.
- **Novels have no recommendations.** LN sources expose no related titles and mainstream trackers track light novels unreliably, so a novel row would have almost no input. Void if novel tracking proves out.
- **The cross-tracker dedup reaches AniList, MyAnimeList and Kitsu only.** Shikimori and Bangumi rows carry no cross ids, so a series tracked there and on MAL counts twice.

## Upstream divergences

`// RK` islands in Mihon files: `MangaViewModel` (the load, `applyRelated`, the menu-placement flag, the state fields), `MangaScreen` (the load trigger, card taps, See all and the overflow action), `SettingsMainScreen` and `SettingsSearchScreen` (the settings entry), `CommonMangaItem` (helpers made `internal` for `RecommendationGridItem`), and the library pull in each tracker: `Anilist` / `AnilistApi`, `MyAnimeList` / `MyAnimeListApi` (`LIBRARY_FIELDS`), `Kitsu` / `KitsuApi`, `Shikimori` / `ShikimoriApi`, `Bangumi` / `BangumiApi`, plus the public `BASE_URL`s of `MangaUpdatesApi` and `ShikimoriApi` and the user agent in `ShikimoriInterceptor`. No deliberate divergence is recorded in [upstream-sync.md](../upstream-sync.md) for this area.

## Extending

- **A new recommendations tracker**: a `TrackerRecommendations` subclass using `candidate`, a branch in `RecommendationProviders.forTracker` with a rate limit on `client`, a preference and a `recommendationToggles` entry.
- **A new taste tracker**: a `TrackerLibraryFetcher` whose library call carries genres inline, an entry in `providesTrackerLibraryFetchers`, and a `pullLibraryFrom*` preference. Add its cross ids to `TrackedEntry` if it has them.
- **A new stream**: a `RecommendationOrigin` case, its fetch in `RelatedMangasLoader`, its gate in `EnabledRecommendationStreams.allows` and the loader, and a label in `RecommendationOriginLabels.kt`.
- **A new filter**: build its index in `BuildRecommendationHideFilter` and decide it in `RecommendationHideFilter.shouldHide`, so the carousel, its count and See all agree.

## Tests

Pool and assembly: `RecommendationAssemblyTest`, `RelatedMangaCacheTest`, `RecommendationRankerTest`, `TitleNormalizerTest`, `RecommendationHideFilterTest`, `RecommendationOriginTest`, `CappedRecommendationCallTest`, `RelatedMangaLocalIdTest`. Providers: `RecommendationTogglesTest`, `RecommendationSearchKindTest`, `ShikimoriRecommendationsTest`, `RecommendationDtosTest`. Taste: `ComputeTasteProfileTest`, `TasteCandidateSelectionTest`, `RefreshTrackerLibraryTest` (the cooldown and the purge, including the logged-out case), `AnilistLibraryDtoTest`, `LibraryFetcherDtoTest`, `LocalTrackStatusMapperTest`, `RemoteTrackStatusTest`. UI: `RelatedMangasBrowseViewModelTest`, `RelatedDestinationTest`.

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`.

## Related

- User doc: [related-mangas.md](../../related-mangas.md).
