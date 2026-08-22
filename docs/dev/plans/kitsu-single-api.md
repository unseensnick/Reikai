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

### NSFW

Four signals arrive with the queries above: the entry's `nsfw`, the media's `sfw` and `ageRating`,
and each category's `isNsfw`. The design uses two of them and deliberately ignores the others.

Category `isNsfw` replaces the JSON:API `nsfw` attribute in the metadata mapper, so Fill-from-tracker
keeps its current behaviour with no user-visible change, and the same filter is applied to the taste
profile's tags, which is the gap being closed. Entry `nsfw` is carried into `TrackedEntry` so the
recommendation layer can tell an adult entry from a clean one at all, which today it cannot.

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

**Step 4, NSFW into the taste profile.** Filter `isNsfw` categories out of the tag set, and add the
entry's NSFW flag to `TrackedEntry`, the `taste_library` table and its repository. Verify: a unit test
over the mapper asserting an NSFW category is dropped and a clean one kept, verified by mutation, plus
a device pull showing the adult titles in the real library stored with the flag set.

**Step 5, retire the JSON:API surface.** Remove `JSON_API_BASE_URL` and the two mapping-site
constants, and confirm no request in a full session touches `/api/edge/`. Verify: exercise search,
bind, update, refresh, unbind, Fill-from-tracker and a taste pull with logcat filtered to
`kitsu.app/api`, and see only `/api/graphql`.

**Step 6, the cross-tracker question.** Decide whether the other four fetchers answer the new NSFW
field or leave it null, and whether the recommendation layer acts on it (hiding adult suggestions
behind the existing Lewd preference) or merely records it. This is listed as a step rather than folded
into step 4 because it is an owner decision, not a mechanical one; see Open questions.

## Key files

- `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/KitsuApi.kt`: both islands, `// RK` fenced,
  plus the `COMMON_MANGA_DATA` fragment step 3 extends.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/Kitsu.kt`: the `getUserLibrary` passthrough
  and `getUserId()`, both of which step 2 removes.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/dto/KitsuLibrary.kt`,
  `dto/KitsuMetadata.kt`: the Reikai-owned JSON:API DTOs, deleted by steps 2 and 3.
- `app/src/main/java/reikai/domain/recommendation/taste/KitsuLibraryFetcher.kt` and its siblings, plus
  `TrackedEntry.kt`, `TasteLibraryRepository.kt` and `reikai/data/recommendation/taste/`: the consumer
  chain and the table step 4 changes.
- `app/src/main/java/reikai/presentation/library/LibraryItemFields.kt`,
  `reikai/util/MangaLewd.kt`, `reikai/domain/manga/AdultContentChecker.kt`: the app's existing adult
  content notion that step 6 would plug into.
- `app/src/test/java/reikai/domain/recommendation/taste/LibraryFetcherDtoTest.kt`: the only Kitsu
  test, rewritten in step 2.

## Status

Not started. Grounded 2026-08-22 against `hummingbird-me/kitsu-server` on `the-future` and against the
current tree; the GraphQL port it builds on shipped in `bf4c8d528`.

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
