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
| Shikimori | `isCensored` (GraphQL only) | Yes, though over-broad: it also flags BL and GL regardless of explicitness | No |
| Bangumi | `nsfw` on Subject | Yes, undocumented, equated with R18 by their own search filter | No |
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

### The keyword fallback is narrower than it looks

Worth stating before the list, because it changes how much rides on it: **six of the eight services
expose a machine-readable adult signal**, and all five current taste fetchers are among them. So once
the per-tracker steps land, the fallback never fires for them. It earns its place in three other
places: before those steps land, for a tracker that has no flag (MyNovelList has tags only), and on
the suggestion side, where a candidate from a recommendation provider carries genres but no flag.

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

Shikimori answers in Russian and Bangumi in Chinese, so the list carries their terms too; a
Latin-only list would silently pass every adult entry from both.

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

Several services return adult titles only if asked, and default to hiding them: MAL documents that
"by default, some APIs don't return nsfw content", Shikimori's `censored=false` is what "allows
hentai, yaoi and yuri", and Bangumi excludes R18 for unauthenticated callers. So a library pull that
does not ask can be silently incomplete.

**Ask when the setting allows adult content, and stop asking when it does not** (owner, 2026-08-22).
The setting is off by default, so the default behaviour is to ask, which also closes gaps the profile
has today on Shikimori and Bangumi. The reason to gate the request rather than only the read is
privacy: a user who has asked to exclude sexual content should not have adult titles fetched and
stored in a local database anyway. Filtering only at read would leave them on disk.

The read filter stays as well, as the second layer. It is what handles entries already in the cache,
entries from services with no request-side filter (AniList's library query has none, Hikka has none at
all), and any title a service mislabels.

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

**Step 5, Shikimori and Bangumi.** Add `isCensored` to Shikimori's GraphQL library query and `nsfw` to
Bangumi's collections read, and start sending Shikimori's `censored=false` and Bangumi's `filter.nsfw`
according to the setting. Both currently ask for neither, so their profiles may be missing adult
entries today, and this is the step that closes that. Verify: on-device pull per tracker with the
setting off, expecting entry counts to rise if the owner's libraries hold adult titles, and noting
that Shikimori will flag BL and GL titles that are not explicit, which is its behaviour and not a bug
in the mapping.

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
- `app/src/main/java/reikai/domain/recommendation/taste/{Anilist,MyAnimeList,Kitsu,Shikimori,Bangumi}LibraryFetcher.kt`:
  the five fetchers that answer the new field.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistApi.kt` (the library selection set),
  `myanimelist/MyAnimeListApi.kt` (`LIBRARY_FIELDS`, and the two `nsfw=true` request params),
  `shikimori/ShikimoriApi.kt`, `bangumi/BangumiApi.kt`.
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
Steps 5 to 10 are open. Grounded 2026-08-22 against the current tree and each service's own API
documentation and source.

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

**Deferred from step 2 on purpose:** the cache invalidation the plan put there. It only matters once
a request-side gate exists, which arrived with step 4, so the setting now needs to mark the taste
cache stale when it flips. That is the first thing step 5 owes.

## Decisions & tradeoffs

**Adult content is opt-in** (owner, 2026-08-22): `showAdultTrackerContent` defaults to `false`, so
the filter is on until a user turns it off. This overrides an earlier draft of this plan, which had
it the other way round on the grounds that the app leans permissive elsewhere (`showNsfwSource`
defaults to true).

**The cost of that default, stated plainly:** nothing filters adult content in recommendations today,
so an existing library's profile does change on upgrade, and adult titles stop shaping it until the
user opts in. That is a real behaviour change rather than a no-op, and it wants a CHANGELOG entry
that says so rather than one that only describes the new switch.

**One toggle, not per-tracker toggles.** The taste-profile group already has five per-tracker pull
switches, and adding five more would double it for a distinction nobody wants: a user who does not
want adult content shaping recommendations does not want it from AniList but not MAL.

**Both layers, not one.** The request-side gate is for privacy and for completeness; the read-side
filter is for everything the request cannot cover, which is AniList (no adult argument on the library
query), Hikka (no filterable attribute at all), already-cached entries, and mislabelled titles.
Neither layer alone is sufficient, so both ship.

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
