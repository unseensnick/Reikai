# Novel-specific trackers: RanobeDB, MyNovelList, NovelList, NovelUpdates

## Goal

Add tracking services built for light novels, so a novel can be bound to something that actually
catalogues novels instead of only to manga-first services. RanobeDB first, then a decision on the
other three.

## Why

Reikai's seven novel-capable trackers are all manga or anime services that happen to list some light
novels. The four services here are novel-first, and two reference forks already ship working clients
for them, so the question is not whether they can be built but which are worth carrying.

## What the references actually do

Read directly from `refs/tsundoku` and `refs/IReader` rather than from their docs. tsundoku's three
trackers are single self-contained files (`RanobeDb.kt` 475 lines, `NovelUpdates.kt` 473,
`NovelList.kt` 304) subclassing the same `BaseTracker` we use.

**The interface is nearly identical, so the shape ports mechanically.** Comparing tsundoku's
`data/track/Tracker.kt` against ours member by member: same ids, same status and score members, same
`bind` / `update` / `refresh` / `login` / `logout`. Two differences only: tsundoku names the novel
search `searchNovels` where we use `searchNovel`, and we add `supportsNovels` plus `getMangaMetadata`,
the latter already defaulted in `BaseTracker`. A ported tracker therefore needs a rename, a
`supportsNovels = true`, and nothing else structural.

**RanobeDB's write path should not be ported at all.** `RanobeDbSuperForm` (`RanobeDb.kt:361-475`,
about a quarter of the file) hand-encodes SvelteKit SuperForms *slot indices*: a positional JSON array
where slot 6 is the reading status, 7 the score, 8 the start date, and so on, posted as
`__superform_json` with a literal `__superform_id` of `"tsundoku"`. Any change to the server's form
schema shifts those slots and corrupts writes with no error. That whole mechanism is now obsolete:
RanobeDB merged token-authenticated routes on 2026-08-13, so `PUT /api/v0/user/book/{id}` takes plain
JSON (`readingStatus`, `score`, `started`, `finished`, `notes`, `selectedCustLabels`) and
`DELETE` on the same path removes the entry. That also retires `fetchSeriesId` (`RanobeDb.kt:210`),
which exists only because deletion was a series-level form action.

**MyNovelList is a rewrite, not a port.** IReader is Kotlin Multiplatform on Ktor
(`MyNovelListApi.kt` 394 lines, `MyNovelListRepositoryImpl.kt` 253), with its own repository
abstraction rather than a `Tracker`. The API itself is simple: `Bearer` API key, `GET /novels/search`,
`GET /novels` for the whole library, `POST|PUT /novels/{id}/progress`, `DELETE /novels/{id}`. Note that
search requires the key too (`MyNovelListApi.kt:57` returns empty without one), unlike RanobeDB where
search is public.

## Defects in the references, not to be carried across

This is the part that makes the port worth planning rather than copying.

- **Blocking network calls inside coroutines.** `submitForm` and `submitSeriesForm`
  (`RanobeDb.kt:232`, `:275`) are not `suspend` and call `client.newCall(request).execute()`. They are
  invoked from `bind` and `update`, which are suspend, so every write blocks whatever dispatcher the
  caller is on.
- **Writes that cannot fail.** Those same two functions catch every exception, log, and return
  normally, and `update` (`RanobeDb.kt:155`) returns `track` regardless of the HTTP status. A rejected
  write reports success to the caller and the local row silently diverges from the service.
  `NovelList` is better here (`awaitSuccess()`), `NovelUpdates` is worse.
- **Searches that swallow errors.** `RanobeDb.search` (`:98`) and every MyNovelList call return an
  empty list on any exception, so a network failure is indistinguishable from no results.
- **Credentials written to the log.** `TrackerWebViewLoginActivity.kt:422`, `:434` and `:457` log the
  full cookie string for each service, which for NovelUpdates is the live `wordpress_logged_in`
  session. Our own rule in `.claude/rules/security.md` forbids exactly this, because log statements
  reach crash reports. These lines do not survive a port.
- **Dates sent but never collectable.** `RanobeDb` submits `started` and `finished`
  (`RanobeDb.kt:147-148`) while never overriding `supportsReadingDates`, which stays `false` in
  `BaseTracker`, so the UI never offers the fields whose values it transmits.
- **A progress read that fails to zero.** `NovelUpdates.getNotesProgress` (`:156`) returns `0` on any
  exception, and that value feeds `refresh`, so a transient failure can present as "no chapters read".

## Two identity problems worth deciding before writing code

**NovelList's ids are UUIDs and ours are integers.** `Track.remote_id` is a `Long`, and
`novel_tracks.remote_id` is `INTEGER NOT NULL`. tsundoku squares that circle by hashing the UUID
string into a Long (`NovelList.kt:223`, taking the absolute value of `String.hashCode()`) and keeping
the real UUID in a `tracking_url` fragment, read back with `substringAfter("#")` (`:98`). That is a
32-bit hash used as an identity, plus a convention that breaks silently if anything ever rebuilds the
tracking URL. We already have a better slot: `novel_tracks.remote_url` is a real `TEXT NOT NULL`
column, so the UUID can live there honestly. The unique constraint is on `(novel_id, sync_id)`, not on
`remote_id`, so nothing structural depends on the hash.

**RanobeDB binds to a book or a series, and they are different things.** Both accept writes on the new
API. A light novel a user reads is usually a series, while tsundoku binds per book and then has to
look the series up to delete. Deciding this once, up front, avoids a migration later.

## What Reikai is missing

- **A paste-a-token login.** Today `SettingsTrackingScreen` offers exactly two shapes: an OAuth
  deeplink through `openInBrowser(authUrl())`, and `LoginDialog(tracker, uNameStringRes)`, a
  two-field username and password form. RanobeDB's PAT and MyNovelList's API key are a single secret
  with no username, so they need a one-field variant. `LoginDialog` is a two-property data class, so
  this is a small addition rather than new infrastructure.
- **A WebView cookie login**, for NovelList and NovelUpdates only. tsundoku's
  `TrackerWebViewLoginActivity` is 475 lines and hardcodes per-tracker cookie extraction in a `when`
  over tracker ids (`:418-473`). If we build it, the extraction belongs on each tracker as a typed
  capability rather than a central switch that grows with every service.
- **Tracker ids.** Ours run 1 to 11 plus MDList at 60. tsundoku uses 100 for NovelUpdates, 101 for
  NovelList and 102 for RanobeDB. Matching those costs nothing and mirrors why MDList took Komikku's
  60. Ids persist with saved tracks and can never change afterwards.

## Steps

**Step 1, the token login dialog.** Add a single-secret variant beside `LoginDialog` and wire it so a
tracker can declare it needs one. Verify: an existing username/password tracker still logs in
unchanged, and the new dialog stores what is typed.

**Step 2, RanobeDB on the token API.** New tracker on `/api/v0`: public search, `PUT` and `DELETE`
under `/api/v0/user/`, with the PAT sent as `Bearer`. Bring across tsundoku's status and score
mappings and its search parsing; bring across none of `RanobeDbSuperForm`. Make writes suspend, let
failures propagate, and override `supportsReadingDates` to match the dates actually sent. Verify:
bind, progress, score, dates, refresh and unbind against a real account, each confirmed on the
RanobeDB site rather than only in-app, since a silently swallowed write is the failure mode being
designed out.

**Step 3, decide book versus series** using what step 2 learns, and record it here.

**Step 4, MyNovelList, if it is wanted.** A rewrite of IReader's Ktor client onto `BaseTracker` and
OkHttp. Keep the user-editable base URL, because the default host is one person's free-tier
deployment. Verify: the same end-to-end pass as step 2, plus that a changed base URL is honoured.

**Step 5, the WebView cookie login, only if NovelList or NovelUpdates is going ahead.** Port
`TrackerWebViewLoginActivity` without the three cookie-logging lines, and with per-tracker extraction
rather than the id switch. Verify: the captured credential authenticates a real request, confirmed by
a successful write, and a logcat capture of the whole login shows no cookie value.

**Step 6, NovelList.** Port the JWT client, storing the UUID in `remote_url` rather than hashing it.
Verify: bind and update against a real account, and confirm identity survives an app restart and a
backup round trip, which is what the hash-and-fragment scheme puts at risk.

**Step 7, NovelUpdates, or a decision not to.** Entirely scraped, with progress living in the user's
own notes field. If it goes ahead, the notes read and write need to be non-destructive under failure,
because they edit user-visible content. Verify: progress round-trips, and a forced failure leaves
existing notes untouched.

## Key files

- `refs/tsundoku/app/src/main/java/eu/kanade/tachiyomi/data/track/{ranobedb,novellist,novelupdates}/`:
  the three reference trackers.
- `refs/tsundoku/app/src/main/java/eu/kanade/tachiyomi/ui/webview/TrackerWebViewLoginActivity.kt`:
  the login harness, and the cookie logging to drop.
- `refs/IReader/data/src/commonMain/kotlin/ireader/data/tracking/mynovellist/`: the MyNovelList client.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/TrackerManager.kt`: ids and registration.
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTrackingScreen.kt`: the two
  existing login shapes and `LoginDialog`.
- `data/src/main/sqldelight/tachiyomi/data/novel_tracks.sq`: `remote_id INTEGER`, `remote_url TEXT`.

## Status

Not started. Grounded 2026-08-22 by reading both reference implementations and the RanobeDB server
source; its `PUT` and `DELETE` handlers and its PAT check in `hooks.server.ts` were read directly,
because the published API docs still describe the API as read-only.

## Decisions & tradeoffs

**RanobeDB first, and alone at first.** It is the only one of the four with documented endpoints, a
curated catalogue, stable integer ids, a token flow needing no OAuth redirect and no secret in the
APK, and tags that let it feed recommendations. Its NSFW flag was part of that case when the tracker
adult-content filter existed; that filter was removed, so only the tags count now. See
[recommendations-adult-content.md](recommendations-adult-content.md).

**"No public API" is a maintenance cost here, not a blocker.** NovelUpdates and NovelList have no
documented API and NovelUpdates sits behind a Cloudflare managed challenge, but Reikai already carries
the extension ecosystem through exactly that: `NetworkHelper` installs `CloudflareInterceptor` with a
FlareSolverr fallback, and `AndroidCookieJar` shares OkHttp's cookies with the WebView's own store, so
a WebView login carries straight into API calls. Both are buildable; the real cost is that scraped
selectors and private endpoints rot, which is why they are sequenced last and may be dropped.

**MyNovelList's risk is who runs it, not whether it works.** IReader points at
`mynoveltracker.netlify.app`, its own deployment, and not at mynovellist.net, which exposes no API at
all. The catalogue is user-created and there is no content rating. Keeping the base URL editable is
the mitigation, and depending on it is a judgement call rather than a technical one.

**The reference defects above are the reason this is a port and not a copy.** Blocking calls in
coroutines, writes that cannot fail, and session cookies in the log are each individually small and
collectively the difference between shipping these and regretting them.
