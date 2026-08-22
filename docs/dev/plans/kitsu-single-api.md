# Kitsu on one API, with NSFW awareness

## Goal

Move Reikai's last two Kitsu calls off Kitsu's JSON:API onto their GraphQL API, so the whole tracker
speaks one API, and use the NSFW signals GraphQL exposes so adult content stops silently shaping the
recommendation taste profile.

## Why

The GraphQL port (mihon `31d3c2371`, ported in `bf4c8d528`) moved every tracking call over but left
two Reikai-owned islands behind, fenced `// RK` in `KitsuApi`: `getUserLibrary`, the whole-library
pull feeding the recommendation taste profile, and `getMangaMetadata`, the per-title read behind
Fill-from-tracker. They stayed because upstream's GraphQL selection set carries neither the
cross-tracker mappings nor the genre list those two read, and because upstream exposes no
whole-library query at all.

Reading Kitsu's own schema shows both gaps are upstream's selection set, not Kitsu's schema. The
capability is there; nobody had asked for it. Meanwhile the split costs a permanent second API
surface, two DTO families for one tracker, and a dependency on an endpoint we no longer otherwise
touch.

The NSFW half is a real gap rather than polish. `getMangaMetadata` already drops NSFW categories
before offering genres to the edit-info dialog (`KitsuApi.kt`, the `attributes.nsfw != true` filter),
but `getUserLibrary` takes every category title straight into the taste profile's tag set. Nothing in
`reikai/domain/recommendation/` mentions NSFW, adult or lewd anywhere, and no sibling fetcher
(AniList, MyAnimeList, Shikimori, Bangumi) filters adult genres either. So an adult title in a
tracker library steers recommendations by its adult tags, with no way to see that happening or turn
it off, on a screen whose library-side twin has had a Lewd filter all along.

## Approach

### What Kitsu's schema actually offers

Grounded by reading `hummingbird-me/kitsu-server` on `the-future`, the repository's default branch.
Live introspection is closed to unauthenticated clients (403), and the deployed schema is therefore
**not** directly confirmed; see Decisions for how each step verifies against the real endpoint before
anything is deleted.

- `Query.currentProfile: Profile` ("You must supply an Authorization token in header"), and
  `Profile.library: Library!`.
- `Library.all(mediaType: MediaType, status: [LibraryEntryStatus], sort: ...)` returns a
  `LibraryEntry` Relay connection, so pagination is `first` / `after` with `pageInfo`. The schema sets
  `default_max_page_size 2000`, well above the 500 the JSON:API pull uses per page.
- `LibraryEntry` carries `status: LibraryEntryStatus!`, `rating: Int`, `progress: Int!`,
  `private: Boolean!`, `startedAt` / `finishedAt`, `media: Media!`, and **`nsfw: Boolean!`**, described
  as whether the entry's media is not safe for work.
- The `Media` interface carries `titles: TitlesList!`, `description` (a localized field),
  `categories: Category.connection!`, `mappings: Mapping.connection!`, `staff: MediaStaff.connection!`,
  `posterImage`, plus **`sfw: Boolean!`** and **`ageRating: AgeRating`** (`G`, `PG`, `R`, `R18`).
- `Category` carries **`isNsfw: Boolean!`** and a localized `title`.
- `Mapping` carries `externalSite: MappingExternalSite!` and `externalId: ID!`; the enum includes
  `MYANIMELIST_MANGA` (`myanimelist/manga`) and `ANILIST_MANGA` (`anilist/manga`), the two the taste
  profile resolves today.
- Enum wire values are the GraphQL names, in screaming case: `LibraryEntryStatus` is `CURRENT`,
  `PLANNED`, `COMPLETED`, `ON_HOLD`, `DROPPED`, and `MangaSubtype` is `MANGA`, `NOVEL`, `MANHUA`,
  `ONESHOT`, `DOUJIN`, `MANHWA`, `OEL`.
- A localized field resolves to `Types::Map`, a loose key-value scalar, so `title(locales: ["en"])`
  parses as `Map<String, String>` and is read by key, exactly as upstream already parses `description`.

Two consequences worth stating before the steps. `LibraryEntry.media` is the `Media` interface, so
`subtype` needs an inline `... on Manga` fragment while `titles`, `categories` and `mappings` do not.
And `Library.all` filters by media type and status but not by NSFW, so NSFW selection stays
client-side, which is where the app already makes that decision anyway.

### How the two calls end up

**The library pull** becomes one paged GraphQL query rooted at `currentProfile`, which also retires
the stored-user-id lookup: `getUserLibrary` stops taking a `userId` and `Kitsu.getUserId()` goes with
it. Per page it selects `status`, `rating`, `nsfw`, and on the media the id, titles, categories with
`isNsfw`, and mappings. That is a strict superset of what the JSON:API version reconstructs by
indexing a side-loaded `included` array, so `resolveLibraryPage` and the nine JSON:API graph DTOs in
`dto/KitsuLibrary.kt` disappear, leaving only the flat `KitsuLibraryEntry` the fetcher maps from.

**The metadata read** folds into the query upstream already issues. `getMangaDetails` posts
`findMangaById` with the `COMMON_MANGA_DATA` fragment, which already carries titles, description,
poster and staff; the only field Fill-from-tracker needs beyond it is `categories`. So the two become
one query with two mappers rather than two queries, and `dto/KitsuMetadata.kt` goes.

### NSFW, behind a preference

Four signals arrive with the queries above: the entry's `nsfw`, the media's `sfw` and `ageRating`,
and each category's `isNsfw`. The design uses the entry flag, keeps the category flag where it is
already used, and deliberately ignores the other two.

**The setting, the storage model and the filtering rules are owned by
[recommendations-adult-content.md](recommendations-adult-content.md)**, which covers all five
taste-profile trackers rather than Kitsu alone. Kitsu is one supplier in that design, and the only
thing this plan owes it is the flag, selected in the queries below and stored unconditionally so the
toggle applies at read time. What follows is Kitsu-specific and does not repeat that plan.

**What Kitsu's flag actually means.** `LibraryEntry.nsfw` and `Media.sfw` both come from one rule in
`app/models/concerns/age_ratings.rb`: `sfw?` is `age_rating.in?(%w[G PG R])`, so NSFW is exactly
`age_rating == R18` and nothing else. This is a maturity axis rather than a dedicated sexual-content
boolean, but the cut lands in a useful place for us: `R18` is documented as "Contains adult content or
themes", while violent-but-not-sexual titles sit at `R`, "Possible lewd or intense themes", and stay
unflagged. `Category.isNsfw` is separate and is a property of the category itself, so it marks the
erotica and hentai categories directly. Two consequences to design around rather than discover later:
an unrated title is treated as safe (`sfw?` also returns true when `age_rating` is nil), so the flag
under-reports, and it will never catch violence, which is what the owner asked for.

Category `isNsfw` replaces the JSON:API `nsfw` attribute in the metadata mapper, so Fill-from-tracker
keeps exactly its current behaviour with no user-visible change. That filter is deliberately **not**
put behind the new preference: it governs which genres get written onto an entry from the edit-info
dialog, a different surface with its own existing behaviour, and settings here stay scoped to the
screen they affect.

`ageRating` and `sfw` are not used. `sfw` is the inverse of information already carried by entry
`nsfw`, and `ageRating` is a stricter axis (`R` covers "possible lewd or intense themes") that would
class ordinary seinen as adult and does not match how the app's existing Lewd filter behaves.

The app's existing notion of adult content is the shared library filter, and the plan plugs into it
rather than inventing a parallel one. `libraryItemFilterFields` binds one `isLewd` seam used by both
content types, with manga supplying a source name and novels passing `null` so the heuristic falls
through to its genre half. Kitsu's flag is a fourth input alongside the extension NSFW flag, the
gallery-source check and the genre heuristic that `AdultContentChecker` already combines.

## Steps

Each step names the check that would catch it being wrong. No JSON:API path is deleted until its
GraphQL replacement has been observed returning the same data on device, which is what stands in for
the introspection we cannot run.

**Step 1, the library query behind a temporary second path.** Add the GraphQL
`currentProfile { library { all(...) } }` query and its DTOs alongside the existing JSON:API pull,
without removing anything. Verify: run both against the owner's real account and compare entry count,
titles, statuses, ratings and resolved MAL/AniList ids. This is the step that proves the deployed
schema matches `the-future`, and it is deliberately first and deliberately non-destructive, because
every later step assumes it.

**Step 2, cut over and delete.** Point `KitsuLibraryFetcher` at the GraphQL pull, delete
`resolveLibraryPage`, `buildInitialLibraryUrl`, the nine graph DTOs, and `Kitsu.getUserId()`. Verify:
the taste cache repopulates with the same row count as step 1 recorded, and `LibraryFetcherDtoTest`
is rewritten against the GraphQL envelope rather than deleted, since it is the only Kitsu test in the
tree.

**Step 3, fold the metadata read into `findMangaById`.** Add `categories` to the shared fragment, map
it into `TrackMangaMetadata`, delete `dto/KitsuMetadata.kt` and the JSON:API metadata call. Verify:
Fill-from-tracker on a bound entry fills the same author, artist, description, cover and genre set it
fills today, compared against a before capture.

**Step 4, answer the adult-content field for Kitsu.** Map `LibraryEntry.nsfw` or any category's
`isNsfw` onto the `AdultContent` value that
[recommendations-adult-content.md](recommendations-adult-content.md) defines, which by then exists
with Kitsu answering `UNKNOWN`. Verify: a device pull, then a query against the pulled database
showing the adult titles in the real library stored as `ADULT` and the clean ones as `CLEAN`, which
doubles as the check that the deployed schema really returns the field.

**Step 5, retire the JSON:API surface.** Remove `JSON_API_BASE_URL` and the two mapping-site
constants, and confirm no request in a full session touches `/api/edge/`. Verify: exercise search,
bind, update, refresh, unbind, Fill-from-tracker and a taste pull with logcat filtered to
`kitsu.app/api`, and see only `/api/graphql`.

Step 4 is the only ordering constraint between the two plans: it needs the GraphQL library query from
step 1, and it needs the `AdultContent` model from the adult-content plan's step 1. Everything else
here is independent of that plan, and everything there except its own step 6 is independent of this
one.

## Key files

- `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/KitsuApi.kt`: both islands, `// RK` fenced,
  plus the `COMMON_MANGA_DATA` fragment step 3 extends.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/Kitsu.kt`: the `getUserLibrary` passthrough
  and `getUserId()`, both of which step 2 removes.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/dto/KitsuLibrary.kt`,
  `dto/KitsuMetadata.kt`: the Reikai-owned JSON:API DTOs, deleted by steps 2 and 3.
- `app/src/main/java/reikai/domain/recommendation/taste/KitsuLibraryFetcher.kt` and its siblings, plus
  `TrackedEntry.kt`, `TasteLibraryRepository.kt` and `reikai/data/recommendation/taste/`: the consumer
  chain and the table step 4 changes. `ComputeTasteProfile.kt` and `TasteCandidateFetcher.kt` are
  where step 5 applies the preference.
- `app/src/main/java/reikai/domain/recommendation/ReikaiRecommendationPreferences.kt`: the taste-profile
  region holding the five `pullLibraryFrom*` toggles, which the new preference joins.
- `app/src/main/java/reikai/presentation/recommendation/SettingsRecommendationsScreen.kt`:
  `tasteProfileGroup`, which renders those toggles and gains the new row.
- `app/src/main/java/reikai/presentation/library/LibraryItemFields.kt`,
  `reikai/util/MangaLewd.kt`, `reikai/domain/manga/AdultContentChecker.kt`: the app's existing adult
  content notion that step 6 would plug into.
- `app/src/test/java/reikai/domain/recommendation/taste/LibraryFetcherDtoTest.kt`: the only Kitsu
  test, rewritten in step 2.

## Status

**Steps 1, 2, 3 and 5 shipped** (`ebe0ff307`, `a522851b1`), so Kitsu now speaks one API and
`/api/edge/` is gone from the tree. Step 4, the adult-content flag, belongs to
[recommendations-adult-content.md](recommendations-adult-content.md) and is not started.

Grounded 2026-08-22 against `hummingbird-me/kitsu-server` on `the-future` and against the current
tree; the GraphQL port it builds on shipped in `bf4c8d528`.

**What the device runs settled that reading could not.** The deployed schema does match
`the-future`: the library pull returned exactly the JSON:API result, 88 entries with 75 MyAnimeList
ids, 81 AniList ids and 87 carrying tags. Two things only a real run caught. Statuses would all have
landed as `UNKNOWN`, because GraphQL reports the enum upper case where the JSON:API reported it
lower. And Fill-from-tracker threw on a missing `views` field, because reusing upstream's poster DTO
tied it to a selection set it does not share; the fix was its own poster type, and the lesson is that
reusing a DTO across queries couples them to each other's field lists.

The step ordering also proved worth keeping: building the GraphQL pull alongside the old one and
comparing before deleting is what made both defects cheap.

## Decisions & tradeoffs

**One API is the point, and it costs a pre-production dependency.** Kitsu's own tooling labels GraphQL
"Pre-Production", and the JSON:API this retires carries no deprecation notice anywhere in their docs or
repository. So this trades a documented-but-unused API for a single surface on a pre-production one.
That is the owner's call, taken knowingly; the mitigation is that every step verifies against the live
endpoint before deleting its predecessor, so a schema mismatch surfaces as a failed comparison rather
than as a silently empty taste profile.

**The deployed schema is inferred, not introspected.** Everything above comes from first-party server
source on the repository's default branch, not from the running endpoint, which returns 403 to
unauthenticated clients. Extracting the owner's bearer token to introspect was rejected. Step 1 exists
to close this gap empirically before anything depends on it.

**NSFW filtering stays client-side.** `Library.all` filters by media type and status only. Fetching
everything and deciding locally also keeps the flag available for later use rather than making it
unrecoverable at the query.

**`ageRating` and `sfw` are deliberately unused**, for the reasons in Approach. Recording that here
so a later reader does not treat their absence as an oversight.

**Category titles are read by locale key.** A localized field is a loose map, not a string, so the
category mapper reads `["en"]` and must tolerate a missing key rather than assuming one, the same way
upstream's description mapper does.

**Found while inventorying, not fixed here:** `Novel.isLewd()` in
`app/src/main/java/reikai/domain/novel/NovelLewd.kt` has no callers anywhere in main or test. The
novel library reaches the same genre-only result through the shared `libraryItemFilterFields` seam
with a null source name, so the function is dead code whose KDoc still describes it as the live novel
twin. Left out of this plan's diff on blast-radius grounds; it belongs to whoever next touches the
lewd filter, which step 6 may well be.
