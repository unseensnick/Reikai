# Adult content from trackers, behind one setting

## Goal

One setting covering every surface where a tracker's content reaches the user: tracker search, the
recommendation taste profile, tracker-sourced suggestions and Fill-from-tracker genres. When it is
off, sexually explicit titles are neither requested from the services that let us choose nor used
from what is already cached. Alongside it, widen the taste profile to every tracker whose API can
enumerate a user's library, rather than the five it reads today.

Scope is **sexual** content, not violence (owner, 2026-08-22): erotica, sex and nudity, not gore.

## Why

Nothing in `reikai/domain/recommendation/` filters adult content today. A grep of all sixteen files in
the taste package for nsfw, adult, lewd, hentai, erotic or explicit returns zero hits. So an adult
title in a tracked library steers recommendations through its adult tags, and adult titles come back
as suggestions, with no way to see it happening or turn it off. The library has had a Lewd filter all
along; recommendations never got one.

Worse, two trackers make it deliberate rather than accidental. `MyAnimeListApi` sends
`nsfw=true` on both search and the taste-profile library pull, opting in to adult results, and then
never requests MAL's per-item `nsfw` field, so the app asks for adult titles and cannot tell which
they are. AniList's `Media.isAdult` is a clean boolean and appears in none of the five selection sets
in `AnilistApi.kt`, while the same library query does request `genres` and `tags`, so AniList's Hentai
genre flows into the profile as an ordinary tag.

**Scope is sexual content, not violence** (owner, 2026-08-22). Erotica, sex and nudity, not gore. That
distinction is load-bearing below, because several services only offer a maturity rating that mixes
the two, and the app's own existing heuristic mixes them as well.

The coverage half has the same root cause. Three of the eight trackers never feed the profile at all,
so a user who tracks on them gets recommendations shaped by whichever of the other five they also use,
or by nothing. Adding them is worth doing on its own, and it is worth doing in the same initiative
because a new fetcher that ignored the adult setting would reopen the gap the moment it shipped.

## Approach

### What each tracker can actually tell us

Five trackers feed the taste profile today (`ReikaiBindings` builds the fetcher list): AniList,
MyAnimeList, Kitsu, Shikimori and Bangumi. **The owner has asked for the rest to be added where the
service can support a whole-library pull** (2026-08-22), which is its own step below; MangaBaka in
particular has the cleanest adult signal of all eight, so it arrives with the best data of any of
them.

| Tracker | Signal | Sexual only? | Requested today |
|---|---|---|---|
| AniList | `Media.isAdult` | Yes, single 18+ bucket; AniList excludes Ecchi from it | No |
| MyAnimeList | `nsfw`: `white` / `gray` / `black` | Undocumented by MAL; manga has no `rating` enum | No |
| Kitsu | `LibraryEntry.nsfw` (`ageRating == R18`) plus `Category.isNsfw` | The entry flag is a maturity axis; the category flag is directly sexual | Category flag only, and only for Fill-from-tracker |
| Shikimori | `isCensored` (GraphQL only) | No. Its own schema defines the axis as hentai, yaoi **or** yuri in one bucket | Declined, measured 2026-08-23 |
| Bangumi | `nsfw`, on the full Subject only and never on the collections payload | Partly, and applied to about a quarter of adult titles | Declined, measured 2026-08-23 |
| MangaUpdates | none at all | n/a | n/a, genres only |
| Hikka | `nsfw`, undocumented, not server-filterable | Unknown | No fetcher |
| MangaBaka | `content_rating`: `safe` / `suggestive` / `erotica` / `pornographic` | Yes; violence tags sit at `safe` | No fetcher |

Two of these need care rather than trust. **Kitsu's entry flag is `ageRating == R18` and nothing
else** (`sfw?` is `age_rating.in?(%w[G PG R])` in `app/models/concerns/age_ratings.rb`). It will never
misfire on violence, because violent titles sit at `R`, but it under-reports: a genuinely erotic title
rated `R`, or one with no rating at all, reads as clean, since `sfw?` also returns true for a nil
rating. Kitsu's `Category.isNsfw` is the better signal and is per-category, so the two combine.
**MAL's three values are undefined in MAL's own documentation**; the meaning is taken from a
community spec.

### Shikimori and Bangumi are declined, on measurement

Both were planned as ordinary per-tracker steps. Read-only probes against each service's live API on
2026-08-23, authenticated with the owner's own accounts, killed both. Recorded here because the
obvious next reader will propose them again.

**Shikimori's `isCensored` is not a sexual-content axis.** Its schema documents the sibling filter as
"Set to `false` to allow hentai, yaoi and yuri", so the three sit in one bucket, and a live sample of
the 500 most popular manga bears that out: 29 carry the flag, the shipped keyword list already flags
10 of those, and the remaining 19 are titles like Banana Fish, Given, Sasaki to Miyano, Yagate Kimi
ni Naru and Killing Stalking. None is sexually explicit. **Not one entry in the sample was flagged by
the keyword list and not by `isCensored`**, so mapping the flag would have added zero true positives
against 19 false ones, and it would have marked as adult exactly the tags `AdultContentTest` pins as
clean. With the filter on by default, a BL or GL reader's taste profile would have been gutted.

The flag would still catch a genuinely explicit work tagged only Yaoi, which exists. There is no
field that separates that from Banana Fish, so the recall is unreachable rather than merely unbought.

Shikimori also has **no request-side gate on the library pull**: `userRates` takes page, limit,
userId, targetType, status and order, and nothing else. `censored` belongs to the catalog `mangas`
query. So the pull was never missing adult entries, and there is nothing to send.

**Bangumi cannot answer per entry on the call the pull makes.** Collections return `SlimSubject`,
which has no `nsfw` property; only the full subject read does, at one HTTP call per entry. The
endpoint also ignores an nsfw parameter, so there is no request-side gate here either. Paying for the
flag was then measured directly against 50 adult-tagged books: the keyword list catches 39 of them
from the top ten tags the collections payload already carries, `nsfw` is set on 14, **13 of those 14
are already keyword-caught, so the flag's entire contribution is one entry in fifty**, and that one
is tagged with vocabulary the widened keyword list now covers. The other 36 explicit books read
`nsfw: false`, which would map to `CLEAN` and suppress the keyword fallback, so buying the flag would
have made the filter worse than not buying it.

One correction worth keeping, because the first reading of the anonymous data was wrong: Bangumi's
flag is both sparsely applied and hidden from unauthenticated callers. Authenticated search returns
more results than anonymous, and `nsfw: true` appears only once authenticated. Neither half rescues a
signal that fires on a quarter of adult titles and is redundant on almost all of them.

**What this costs, stated plainly:** two of the five taste fetchers stay on the keyword fallback
permanently. That is the reason the fallback is worth widening and worth making user-correctable,
rather than a stopgap to be retired tracker by tracker.

### Which trackers can actually feed a taste profile

A fetcher needs one response carrying, per entry, a score, a status and tags. `TrackerLibraryFetcher`
is three members (`trackerId`, `isEnabled()`, `fetchLibrary()`), so the interface is not the obstacle;
the services are. Measured against the current tree:

| Tracker | Whole-list call | Score | Status | Tags | Verdict |
|---|---|---|---|---|---|
| MdList (MangaDex) | Yes, already built | Batched, separate call | Yes | On the DTO, dropped by the mapper | **Add it** |
| MangaBaka | No, but `/v1/my/library` is the per-id path and the granted scope is `library.read` | Yes | Yes | Series call per title | Likely, verify the endpoint |
| Hikka | No, but the granted OAuth scope includes `readlist` | Yes | Yes | Yes | Likely, verify the endpoint |
| MangaUpdates | No, and none evidenced | Separate call per title | Yes | Separate call per title | **No** |
| Kavita | No | Yes | Derived from read progress | None at all | No |
| Komga | No | None | Derived from read progress | Yes | No |
| Suwayomi | No | None | Derived | Yes | No |

**MdList is the one that is nearly free.** `FollowsHandler.fetchAllFollows()` already pages every
follow through `mdListCall` and merges reading statuses from `readingStatusAllManga()`, so the pull
exists and is in use for the bulk follows sync. Tags are already on `MangaDataDto`; they are simply
not copied, because `MdUtil.createMangaEntry` maps only url, title and thumbnail. Scores come from
`mangasRating`, which batches ids rather than costing a call per title.

**MangaUpdates is out on cost, not preference.** It has no collection endpoint, its list item carries
neither rating nor genres, and both would be separate per-title calls. The `TrackerLibraryFetcher`
KDoc already says so, and this measurement agrees with it.

**The three self-hosted services are out on merit rather than capability.** Their "library" is the
user's own server, which the app largely already holds locally, so a taste profile from them is
closer to circular than informative. None of them returns the score, status and tags triple from one
response anyway: Kavita has a score but no genres, Komga has genres but no score, Suwayomi has genres
but no score, and all three derive status from read progress rather than the user declaring it.

### The model

`TrackedEntry` gains a typed three-state field, not a nullable boolean:

```
enum class AdultContent { ADULT, CLEAN, UNKNOWN }
```

The third state is the point. With a `Boolean?`, a tracker that cannot answer silently reads as "not
adult" at every careless call site; with `UNKNOWN` the difference stays visible and each consumer has
to decide. MangaUpdates answers `UNKNOWN` always, and so does any tracker whose call has not been
extended yet, which makes this shippable one tracker at a time without a half-filtered profile.

Resolution is one shared kernel both the profile and the suggestion path call:

1. Tracker says `ADULT`, the entry is adult.
2. Tracker says `CLEAN`, the entry is clean. The tracker's own answer beats a keyword guess.
3. `UNKNOWN`, fall back to matching the entry's tags against a sexual-content keyword list.

### The keyword fallback is permanent, not a stopgap

An earlier draft of this section had it the other way round, on the assumption that every current
taste fetcher would end up answering the field directly and the fallback would stop firing for them.
The measurements above retired that: **Shikimori and Bangumi are declined, so two of the five stay on
the fallback for good.** It also carries the suggestion side, where a candidate from a recommendation
provider arrives with genres and no flag, and any tracker with no usable signal at all (MyNovelList
has tags only).

That changes how much rides on the list, and it is why widening it is real work rather than
housekeeping, and why the tag picker in the steps below is worth building: a heuristic that is
permanent for two trackers will keep meeting vocabulary nobody enumerated.

### The sexual-content keyword list is new, and deliberately not the existing one

The app already has `hasLewdGenre` in `reikai/util/MangaLewd.kt`, used by the library Lewd filter and
by notification hiding through `AdultContentChecker`. It cannot be reused here as it stands, because
its keyword set includes `mature`, matched by substring, so "Mature Themes" trips it. That is correct
for its own job, which is a broad "might not want this on a lock screen" test, and wrong for this one,
which is sex-only by the owner's definition.

So this feature gets its own narrower list, living beside the existing helper rather than replacing
it. Two lists is not duplication here: they answer two different questions, and collapsing them would
silently change the shipped library filter. Whether `mature` should also leave the existing list is a
separate question, raised in Open questions rather than decided here.

**The list is taken from the services' published vocabularies, not written from memory.** A term
qualifies only where that service's own definition is sexual. The rule that does most of the work:
a definition phrased as a disjunction is out, because it catches violence-only series. MangaUpdates
defines **Mature** as "intense violence, blood and gore, sexual content and/or strong language" and
**Adult** as "intense violence and/or graphic sexual content", so neither implies sexual content, and
Berserk carries Mature. Only Smut and Hentai are unambiguous there.

The near-misses matter as much as the matches, and each is pinned by a test: `adult` catches
MyAnimeList's "Adult Cast" theme and AniList's "Primarily Adult Cast"; `sex` catches "Asexual",
"Bisexual", "Heterosexual" and MyAnimeList's "Magical Sex Shift" theme; `nudity` is an AniList tag
carrying `isAdult: false`; Yaoi, Yuri, Boys Love and Girls Love are orientation and romance rather
than explicitness, and Shikimori censors them under local law rather than for content; Josei and
Seinen are demographics; Doujinshi is "fan based work inspired by official anime or manga"; Harem is
a non-adult tag on AniList and a not-NSFW category on Kitsu.

Two deliberate exclusions that are judgement calls rather than clear-cut, recorded so they can be
revisited. **Ecchi** is filed under MyAnimeList's "Explicit Genres" but carries `isAdult: false` on
AniList, and it is common enough on ordinary series that matching it would visibly shrink a profile;
it is fanservice rather than sex. **Tentacle** is an AniList adult tag but a not-NSFW category on
Kitsu, so the two services disagree outright.

Bangumi answers in Chinese, so the list carries its vocabulary; a Latin-only list would silently pass
every adult entry from it. The Chinese half was widened on measurement (see the step below), because
the first pass covered only a fraction of the terms Bangumi's community tags actually use.

Shikimori was assumed to need the same treatment and does not. Its GraphQL `genres` selection returns
English names, with the Russian in a separate `russian` field the fetcher does not request, so the
Cyrillic entries never fire on that path. They are kept because the same list screens candidates from
elsewhere, but nothing on the Shikimori pull depends on them.

### The setting is tracking-wide, not recommendations-scoped

**It governs every tracker surface, not just recommendations** (owner, 2026-08-22). So it lives in
`TrackPreferences` and renders on `SettingsTrackingScreen` beside the other cross-tracker switches,
not in the recommendations screen's taste-profile group where an earlier draft put it. Recommendations
are one consumer of it, not its owner.

The surfaces it reaches:

- **Tracker search**, the dialog a user searches in to bind an entry. Adult results are excluded when
  the setting is off, and where a service offers a request-side parameter the search stops asking for
  them rather than fetching and discarding.
- **The taste profile**, so adult entries do not shape tag affinities.
- **Tracker-sourced suggestions**, the four recommendation providers whose candidates reach the
  carousel.
- **Fill-from-tracker genres**, where Kitsu already drops NSFW categories. That existing filter
  becomes one instance of the shared rule instead of a Kitsu-only special case, and it applies to
  every tracker that can answer.

Gating search was a deliberate call and is worth recording, because the opposite argument is
reasonable: a search that cannot find a title the user typed looks broken. The ruling is that a user
who has turned adult content off has said what they want from the app's tracker surfaces, and a search
box is one of them. Binding an entry the tracker will not return is still possible through the `id:`
prefix, which resolves a specific id or slug rather than searching, so the escape hatch exists for
someone who knows what they want.

### Where the preference applies inside recommendations

`repository.getAll()` has three readers, and only two may be filtered. `GetTasteProfile` and
`TasteCandidateFetcher` are what the setting is about. `BuildRecommendationHideFilter` is the anti-echo
filter that suppresses suggestions the user already tracks, so filtering adult entries out of it would
make adult titles the user already reads start appearing as recommendations, the exact opposite of
what the setting promises. The filter goes at those two call sites, never in the repository.

The setting also covers the suggestion side, not just the profile: the four tracker recommendation
providers return candidates carrying genres, so the same kernel screens them before they reach the
carousel. One toggle governs both, because "adult titles stop shaping my taste" and "adult titles stop
being suggested to me" are not two things a user would want separately.

### The preference also gates the request, not just the read

Some services return adult titles only if asked: MAL documents that "by default, some APIs don't
return nsfw content". So a library pull that does not ask can be silently incomplete.

**Ask when the setting allows adult content, and stop asking when it does not** (owner, 2026-08-22).
The setting is off by default, so the default behaviour is not to ask. The reason to gate the request
rather than only the read is privacy: a user who has asked to exclude sexual content should not have
adult titles fetched and stored in a local database anyway. Filtering only at read would leave them
on disk.

**In practice this reaches exactly one tracker.** An earlier draft expected Shikimori and Bangumi to
be gated here too, on the strength of `censored=false` and an nsfw search filter. Neither applies to
the call the pull makes: Shikimori's `userRates` takes no `censored` argument, and Bangumi's
collections endpoint ignores an nsfw parameter, both verified against the live APIs on 2026-08-23.
AniList's library query has never had one. So MyAnimeList is the only request-side gate there is, and
it is also the whole reason cache invalidation matters.

The read filter stays as well, as the second layer. It is what handles entries already in the cache,
entries from the four services with no request-side filter, and any title a service mislabels.

**Consequence, stated because it is a real cost:** turning the setting off widens what the API
returns, so the cache is stale in a way a normal refresh interval will not notice. Changing the
preference therefore marks the taste cache stale, and the next pull refetches. That is one network
round trip on a setting nobody flips often, and it is the honest behaviour: the alternative is a
profile that silently stays narrow until the cache happens to expire.

Within a single setting state the flag is still always selected and always stored, so nothing about a
cached entry has to be re-derived, and the read filter needs no network.

## Steps

Ordered so each lands independently and the profile is never half-filtered. Every step names its
check.

**Step 1, the model and the kernel.** Add `AdultContent`, the `TrackedEntry` field defaulting to
`UNKNOWN`, the `taste_library` column (a new `.sqm`, which needs no `versionCode` bump), the
repository round-trip, and the resolution kernel with its keyword list. Nothing reads it yet. Verify:
unit tests over the kernel for all three states and the keyword fallback, each verified by mutation.

**Step 2, the preference, the recommendation call sites, and cache invalidation.** Add the boolean to
`TrackPreferences`, render it on `SettingsTrackingScreen` beside the other cross-tracker switches,
honour it in `GetTasteProfile` and `TasteCandidateFetcher`, and mark the taste cache stale when it
changes so the next pull refetches under the new request rules. With every tracker still answering `UNKNOWN`,
this is already useful through the keyword fallback alone. Verify: a test that an adult entry
contributes no tags when the preference is on and does when it is off, mutated both ways; a test
pinning that `BuildRecommendationHideFilter` still sees it either way, since that asymmetry is what a
later refactor is most likely to flatten; and an on-device check that flipping the setting triggers a
refetch rather than leaving the old rows.

**Step 3, AniList.** Add `isAdult` to the taste library selection set and map it. No request-side gate
exists on the library query, so this is read-filter only. The cheapest real win, and independent of
everything else. Verify: pull the owner's AniList library and confirm known adult entries come back
`ADULT` and ordinary ones `CLEAN`.

**Step 4, MyAnimeList.** Add `nsfw` to `LIBRARY_FIELDS`, map `black` and `gray` to `ADULT` and `white`
to `CLEAN`, and make the existing `nsfw=true` on the library pull follow the setting while leaving the
one on search unconditional. Verify: same on-device comparison, plus confirming the library pull
returns fewer entries with the setting on. This step also settles whether `gray` behaves as borderline
in practice, which MAL does not document.

**Step 5, the two declines, the measured keyword widening, and the invalidation.** Replaces the
per-tracker step this used to be; the reasoning is in the Approach section above, and the trackers
themselves need no code. Three parts:

- Record the Shikimori and Bangumi declines with their measurements. The `yaoi` and `yuri` cases
  `AdultContentTest` already pins are the rule the Shikimori decline protects, so no new test is
  owed there.
- Widen the keyword list with the Bangumi vocabulary the 50-title sample named: the Japanese `エロ`
  stem, `成年コミック`, `成人漫画`, `黄漫`, `H漫画` and `18X`. Deliberately out: `卖肉` and `肉番`, which are the
  Chinese equivalent of Ecchi and follow that exclusion; `NTR`, because matching is by substring and
  "ntr" sits inside "control"; and the bare `成人` stem, which would catch `成人式`, a coming-of-age
  ceremony. Verify: each new term gets a case in `AdultContentTest`, plus a near-miss case for the
  three exclusions, all checked by mutation.
- Mark the taste cache stale when the setting flips, the piece deferred from step 2, and stop the
  cache holding rows for trackers whose pull the user turned off, without which clearing it whole
  destroys data nothing will rebuild. Verify: on-device, flip the setting, confirm the cached rows go
  and a details-page open refetches; separately, enable a tracker's pull, let it populate, turn it
  off and confirm only its rows go.

**Step 5a, the tag picker** (owner, 2026-08-23). The keyword list is permanent for two trackers, so
users need a way to correct both a false positive and a miss without waiting on a release. Two string
sets on `TrackPreferences`, edited by picking from the tags actually present in the local taste cache,
sorted by frequency and showing which the built-in list currently matches. **Not free-text entry:**
tags are matched as substrings against `lowercase().trim()`, so a typo or an unused term is a setting
that silently does nothing and gives the user no way to tell. Resolution order, settling the ruling
below: whitelisted tags are subtracted from the entry's tags; any remaining blacklisted tag makes it
explicit; a whitelisted tag present on the entry downgrades a tracker's `ADULT` to `UNKNOWN` so the
remaining tags decide; otherwise the kernel is unchanged. Known limit, recorded rather than fixed: a
per-tag control cannot override a tracker verdict on a title carrying no relevant tag, which is the
`Redo of Healer` case from step 3. That needs a per-title override and is out of scope. Verify: unit
tests over each clause of the order, including the mixed case where a whitelisted and an explicit tag
sit on the same entry, each by mutation.

**Step 6, Kitsu.** Map `LibraryEntry.nsfw` or any `Category.isNsfw` to `ADULT`. Depends on the Kitsu
GraphQL move, which is why it is last. See [kitsu-single-api.md](kitsu-single-api.md).

**Step 7, the MangaDex taste fetcher.** Add `MdListLibraryFetcher` over the existing
`fetchAllFollows()`, mapping tags off `MangaDataDto.attributes` rather than through
`MdUtil.createMangaEntry`, which drops them, and pulling scores through the batched `mangasRating`.
MangaDex's `contentRating` is the adult signal, so it answers the field from step 1 on arrival rather
than shipping as another `UNKNOWN`. Verify: a device pull whose entry count matches the owner's
MangaDex follow count, with tags present and adult titles marked.

**Step 8, MangaBaka and Hikka, if their list endpoints exist.** Both granted scopes imply a
readable list (`library.read`, `readlist`) and both would arrive with good per-entry data, but neither
endpoint is called anywhere in the tree, so this step starts by confirming the endpoint exists before
committing to the fetcher. MangaBaka is the more valuable of the two, since its `content_rating` is
the cleanest adult signal of any tracker we support. Verify: the endpoint returns a paged list for the
owner's account; if it does not, the step closes as not-viable with that recorded rather than left
open.

**Step 9, tracker search and Fill-from-tracker.** Screen search results through the same kernel, and
where a service takes a request-side adult parameter, stop sending it when the setting is off. Extend
Kitsu's existing Fill-from-tracker category filter into the shared rule so every tracker that can
answer applies it. Sequenced after the per-tracker steps because it reuses the signals they add, and
it is the step that makes the setting genuinely app-wide rather than recommendations-only. Verify: a
search for a known adult title returns it with the setting off and not with it on, on each tracker
that can answer, and the `id:` escape hatch still binds it either way.

**Step 10, the docs.** The taste profile is a documented user feature; the new setting needs a line in
the "Your taste profile" section of `docs/related-mangas.md`, plus a CHANGELOG entry.

## Key files

- `app/src/main/java/reikai/domain/recommendation/taste/TrackedEntry.kt`, `ComputeTasteProfile.kt`,
  `GetTasteProfile.kt`, `TasteCandidateFetcher.kt`: the model and the two filtered readers.
- `app/src/main/java/reikai/domain/recommendation/BuildRecommendationHideFilter.kt`: the third reader,
  deliberately unfiltered.
- `app/src/main/java/reikai/domain/recommendation/taste/{Anilist,MyAnimeList,Kitsu}LibraryFetcher.kt`:
  the three fetchers that answer the field. `ShikimoriLibraryFetcher.kt` and `BangumiLibraryFetcher.kt`
  stay on the keyword fallback permanently, per the declines in Approach; do not add a flag to either.
- `app/src/main/java/reikai/domain/recommendation/taste/AdultContent.kt`: the enum, the resolution
  kernel and the keyword list, which is where the fallback and the tag picker both land.
- `app/src/main/java/reikai/domain/recommendation/taste/RefreshTrackerLibrary.kt`: owns the invariant
  that the cache holds rows only for trackers the user still pulls from, which is what lets the
  adult-setting flip clear it whole.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistApi.kt` (the library selection set)
  and `myanimelist/MyAnimeListApi.kt` (`LIBRARY_FIELDS`, and the two `nsfw=true` request params, of
  which only the library one follows the setting).
- `app/src/main/java/eu/kanade/domain/track/service/TrackPreferences.kt` and
  `eu/kanade/presentation/more/settings/screen/SettingsTrackingScreen.kt`: where the setting lives and
  renders, since it is tracking-wide rather than recommendations-scoped.
- `app/src/main/java/reikai/presentation/track/EntryTrackInfoDialog.kt`: the tracker search surface
  step 9 screens.
- `app/src/main/java/exh/md/handlers/FollowsHandler.kt` (`fetchAllFollows`), `exh/md/utils/MdUtil.kt`
  (`createMangaEntry`, which drops tags) and `exh/md/dto/MangaDto.kt`: what step 7 builds on.
- `app/src/main/java/reikai/util/MangaLewd.kt`: the existing broader heuristic this deliberately does
  not reuse.
- `data/src/main/sqldelight/tachiyomi/data/taste_library.sq`: the cache table.

## Status

**Steps 1 to 4 shipped** (`b515944ae`, `fc81876bc`, `b633a6e10`, `780428900`): the model and cache
column, the setting and the two filtered readers, then AniList and MyAnimeList answering the field.
Steps 5 onward are open. Grounded 2026-08-22 against the current tree and each service's own API
documentation and source, then re-grounded 2026-08-23 against the live APIs, which rewrote step 5
entirely (see the declines in Approach).

**Neither the Shikimori nor the Bangumi account holds enough data to measure against.** The owner's
Shikimori library returns one entry and their Bangumi collection two; both are test binds rather than
real libraries. So the two declines rest on public-sample measurements and each service's own schema,
not on the owner's own data, and that is the one place the evidence is thinner than preferred. It is
recorded rather than left implicit, because "verify against the owner's library" is the obvious thing
a later reader will reach for and it will not work.

**Device-verified end to end on the carousel**, which is the only place any of this is observable
(the library screen has no recommendation surface). Same title, adult content on: "See all (135)"
with doujinshi among the results, including one titled `Shingeki no Kyojin dj - knife`. Adult content
off: "See all (282)", uniformly mainstream, no doujinshi. **The count rising when the filter is on is
correct and worth understanding before touching this**: the filter does not subtract from a fixed
list, it changes the taste profile, which changes which genres get searched for candidates, so a
different and larger set comes back.

Two things the device runs settled that reading could not. AniList flags 9 of 189 entries adult, and
two of them (`Redo of Healer`, a MILF-party isekai) carry no tag the keyword list would have matched,
so the service's own ruling catches what substring matching structurally cannot. And MyAnimeList's
`nsfw=false` request really does drop adult entries before they reach the cache, which is the privacy
half of the design working rather than a filter applied after storage.

**Step 5 shipped**, and the device run is what proves the request-side gate does its job end to end.
MyAnimeList held 142 cached rows, every one `CLEAN`, because `nsfw=false` had been dropping adult
entries before they reached the cache. Flipping the setting on cleared the cache, the next
details-page open refetched, and MyAnimeList came back with 145 rows of which 3 were `ADULT`. Those
three were unreachable before and would have stayed missing for up to six hours without the
invalidation. Turning a tracker's pull off was verified separately: AniList's 189 rows went and
MyAnimeList's 142 stayed.

**Tokens refresh lazily, which is normal but has a tail worth knowing.** Both interceptors refresh an
expired access token on the next request, so a tracker nothing calls sits with a dead token
indefinitely; the owner's Shikimori and Bangumi tokens were 22 and 16 days stale simply because
neither pull toggle is on. That self-heals on first use. The hazard behind it is the refresh token
expiring during a long idle stretch, which logs the tracker out silently and shows up only as a
tracker row with no username under it. Noted here, not owned by this plan.

## Decisions & tradeoffs

**Adult content is opt-in** (owner, 2026-08-22): `showAdultTrackerContent` defaults to `false`, so
the filter is on until a user turns it off. This overrides an earlier draft of this plan, which had
it the other way round on the grounds that the app leans permissive elsewhere (`showNsfwSource`
defaults to true).

**A user's tag pick outranks the tracker's own answer** (owner, 2026-08-23). This inverts the rule
step 1 set, where a tracker saying `ADULT` or `CLEAN` was final and keywords only spoke when it could
not. Recorded as an inversion rather than folded in quietly, because the original rule is stated
above and a reader meeting both would otherwise have to guess which won. The reasoning: offering the
control at all implies it decides, since a switch the tracker can veto is not a control. The
precedence that makes this safe, including why a whitelist does not clear a title that also carries
an explicit tag, is in step 5a.

**Invalidation hangs off the settings switch, not a listener or a stored marker** (2026-08-23). The
switch's `onValueChanged` runs before the write commits and returns whether to commit, so clearing
the cache there needs no new state, and this feature area already reacts to a preference the same way
where the auto-refresh interval reschedules its job. The two alternatives were measured against it
and lost. An app-scoped collector on the preference's `changes()` would fire on its ignition emission
and clear the cache on every launch unless a drop is threaded through, and it would put recommendation
knowledge into a Mihon file for a preference nothing else writes. A stored marker compared at refresh
time is self-healing but keeps a second source of truth about what the cache was built under, and it
purges late: rows survive until the next details-page open, which is the wrong answer for a setting
whose request-side half exists for privacy. The one hole in the chosen approach is a backup restore
flipping the preference without going through the switch, and it is harmless, because the taste cache
is not in the backup and a restore therefore leaves it empty and repulled anyway.

**Deleting the whole cache, not just the affected tracker.** MyAnimeList is the only tracker whose
request depends on the setting, so a narrower delete is possible and would need the fetcher to declare
that dependency. It is not worth a typed capability with one implementer, and the wide delete is
better on the privacy axis regardless: the read filter only hides AniList's adult rows, while a delete
removes them.

**The wide delete was only safe once the cache stopped holding rows nobody pulls** (owner, 2026-08-23).
The device run that verified the invalidation exposed the reason. Flipping the setting cleared 419
rows and the refetch restored 142: MyAnimeList's, because it was the only tracker whose pull was still
switched on. AniList's 189 and Kitsu's 88 were orphans, cached back when those pulls were enabled,
never removed when they were turned off, and shaping the taste profile the whole time, since the read
path takes every cached row and only the pull path asks whether a tracker still feeds it. Flipping one
switch therefore shrank the profile by two thirds with nothing to say so.

Rather than narrow the delete around that, the orphans are gone: `RefreshTrackerLibrary` now drops the
rows of any tracker whose pull preference is off, at the top of all three entry points. The cache can
then only hold rows a refetch can rebuild, which is what makes clearing it whole a cheap operation
instead of a lossy one. It also closes the older bug on its own, that a tracker you stopped pulling
from kept steering recommendations forever.

**The purge is keyed to the preference, never to `isEnabled()`.** The obvious version drops any
tracker that cannot answer, which folds in being logged out, and a tracker that logs itself out
silently is a thing that happens here. That would turn a transient auth failure into a wiped profile
contribution, so the fetcher exposes `isPullRequested()` for the preference alone and keeps
`isEnabled()` as that plus the login check. A conformance test pins the distinction: keying the purge
to `isEnabled()` fails exactly the logged-out case and nothing else.

**The cost of that default, stated plainly:** nothing filters adult content in recommendations today,
so an existing library's profile does change on upgrade, and adult titles stop shaping it until the
user opts in. That is a real behaviour change rather than a no-op, and it wants a CHANGELOG entry
that says so rather than one that only describes the new switch.

**One toggle, not per-tracker toggles.** The taste-profile group already has five per-tracker pull
switches, and adding five more would double it for a distinction nobody wants: a user who does not
want adult content shaping recommendations does not want it from AniList but not MAL.

**Both layers, not one**, though the split turned out lopsided. The request-side gate is for privacy
and for completeness, and it reaches MyAnimeList alone: AniList's library query has no adult argument,
Shikimori's `userRates` takes no `censored`, Bangumi's collections ignore an nsfw parameter, and Hikka
has no filterable attribute at all. The read-side filter carries everything else, which is the other
four trackers, already-cached entries, and mislabelled titles. Neither layer alone is sufficient, so
both ship, but the read filter is doing nearly all the work.

**`UNKNOWN` is a state, not a null.** Recorded because the obvious shortcut is a `Boolean?`, and the
whole point is that a tracker's inability to answer stays visible rather than defaulting to "clean".

**MangaUpdates gets keywords only**, because its API exposes no adult field of any kind. Its
free-form user-voted categories carry a better signal than its genres, and are not currently fetched;
noted rather than planned. It also gets no taste fetcher, for the separate reason in Approach.

**The three self-hosted trackers are deliberately excluded from the taste profile**, on the reasoning
in Approach. Recorded here so a later reader does not add them as an obvious gap.

**Of the incoming novel-specific trackers, only RanobeDB can participate** (see the Trackers section
of `ROADMAP.md`). Its documented v0 response types carry a tag taxonomy typed
`"content" | "demographic" | "genre" | "tag"` plus an `nsfw` boolean, so it can both feed the profile
and answer the adult field, and its `content` tags are the closest thing to a sexual-content axis any
tracker we support offers. MyNovelList returns a flat `tags` array with no adult flag, and the
scraped NovelUpdates and NovelList paths carry neither tags nor a rating. Those three answer
`UNKNOWN`, and with no usable tags the keyword fallback has nothing to match either, so they read as
clean. That is the honest outcome rather than a
gap to close, and it is the reason `UNKNOWN` is a real state instead of a null that defaults to
"clean" by accident.

**MyAnimeList's existing `nsfw=true` becomes conditional on the library pull, and stays unconditional
on search.** The two are different questions. The library pull feeds the profile, so it follows the
setting. Search is the user typing a title and expecting to find it, and a tracker search that
silently cannot find an adult title the user is looking for is a bug, not a feature; the setting is
about what shapes recommendations, not about what the user is allowed to look up.
