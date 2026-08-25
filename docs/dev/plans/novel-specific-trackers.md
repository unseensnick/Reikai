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
search `searchNovels` where we use `searchNovel`, and we add `supportsNovels` / `supportsManga` plus
`getMangaMetadata`, the last already defaulted in `BaseTracker`. A ported tracker therefore needs a
rename and both capability flags. Setting only `supportsNovels` is not enough: manga support is the
default, so a novel-only tracker left at that default is still offered on every manga.

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

**Decided: reading progress is never pushed to RanobeDB** (2026-08-25). RanobeDB counts **volumes**
(`volumes_read`) while a source counts **chapters**, and nothing converts between them, so sending
`last_chapter_read` would report chapter 574 as 574 volumes of a 15-volume series. The route accepts
it silently (`z.number().min(0).max(maxNumberValue)`), so it would just be wrong. tsundoku reached the
same conclusion independently: its book form has no `volumes_read` field at all and its series form
hardcodes the slot to `0`.

**Every write is destructive, and this is the most important thing to know about the integration.**
The route writes `readingStatus`, `score`, `started`, `finished`, `notes`, `volumes_read`, `langs`,
`formats` and `selectedCustLabels` straight through from the request body, and no `GET` under
`/api/v0/user/` returns a list entry, so nothing can read the current values back first.
`editSeriesInList` makes this concrete: it `UPDATE`s the whole row and runs
`DELETE FROM user_list_series_label` before re-inserting whatever the body carried. So editing an
entry that already has data **clears that series' custom labels, notes, volume count, and language
and format filters**. We cannot preserve them; we can only choose what replaces them, and an empty
value is at least honest where a chapter number would be a lie. A first bind has nothing to lose,
which is why one looks clean. tsundoku has the same hole through the website's form endpoint.

That is what the **`ranobeDbSyncWhileReading`** preference exists for. Two rules bound the damage.
A read-driven push happens only when the status actually **moves**, so reading a hundred chapters
costs one write rather than a hundred. And deliberate edits in the tracking sheet are never gated,
because a switch that makes a user's own action silently do nothing is exactly what
[content-layer.md](../../.claude/rules/content-layer.md) forbids.

**It defaults on, reversing an earlier call to default it off** (2026-08-25). Off was chosen before
the status-moved test existed, when the cost looked like one write per chapter rather than one per
series. Worse, off did not mean the status stayed local: every payload carries `readingStatus`, and
`setRemoteScore` is ungated, so scoring an entry pushed the status that reading had already changed
locally. The status therefore arrived at the site anyway, just late and attached to an unrelated
edit, which is more confusing than either always or never. Found on a real account.

Also note **the local row still counts chapters against a volume total**, so an entry reads "574 / 15"
in-app after reading even though nothing wrong reaches the service. Fixing that properly needs a
typed capability saying a tracker does not track chapters, which the shared chapter interactor would
honour for both content types; it is not worth inventing for one tracker.

**Decided: series** (owner, 2026-08-25). `GET /series/{id}` carries `publication_status`, the
descriptions, `staff[].role_type` and the `tags[]` taxonomy, where the book endpoint reaches most of
that only by nesting the series inside itself; `c_num_books` is a real volume total, where tsundoku
counts the length of a `books` array instead; `userListSeriesSchema` has `volumes_read`, so
series-level binding keeps progress rather than trading it away; and deleting needs no book-to-series
lookup. Take the total from `c_num_books`, never the sibling `volumes.count`, which is nullable and
typed `string | number | bigint` because it reaches the wire as an unnormalised Postgres count.

## What Reikai is missing

- **A paste-a-token login.** `SettingsTrackingScreen` offers three shapes, not the two an earlier
  draft of this doc claimed: an OAuth deeplink through `openInBrowser(authUrl())` (plus MdList's PKCE
  variant), `LoginDialog(tracker, uNameStringRes)` as a two-field username and password form, and
  `loginNoop()` for the enhanced trackers. RanobeDB's PAT and MyNovelList's API key are a single
  secret with no username, so they need a one-field variant; `LoginDialog` is a two-property data
  class, so this is a small addition rather than new infrastructure. **Shipped** as
  `TokenLoginDialog` / `TrackingTokenLoginDialog`.
- **A username for a tracker that has none.** `BaseTracker.isLoggedIn` is username **and** password
  both non-empty, so a token-only tracker that leaves the username blank reads as logged out
  forever. RanobeDB fills it from `GET /api/v0/user/me`, which doubles as the check that a pasted
  token is real before it is stored.
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

**Step 5, the WebView cookie login. Built, ahead of the scraping trackers that were meant to gate
it,** because RanobeDB turned out to accept a session cookie on the same `/api/v0/user` routes as a
token. `hooks.server.ts` resolves the `auth_session` cookie into the caller *before* it looks for an
`Authorization` header, and rejects only a request carrying neither. So both credentials reach the
same JSON routes with the same bodies, and offering both logins costs one branch in the interceptor.

That also explains tsundoku's SuperForms machinery: it is not what a cookie forces, it is what
predates the v0 user routes, which landed 2026-08-13.

Shape: a `CookieLoginTracker` capability carrying the login URL, the cookie origin and the
extraction, plus a generic `TrackerWebViewLoginActivity` that knows nothing about any service.
tsundoku instead switches on tracker id in the login screen, which grows with every service. Its
three raw-cookie DEBUG lines are not ported; `security.md` forbids them because logs reach crash
reports. Both credentials validate against `/user/me` before being stored, and a stored cookie is
marked by its `auth_session=` prefix, which a base32 token can never contain.

The token stays the better credential and the field stays in the dialog beside the browser button: a
PAT has no expiry column, while a Lucia session does. Verify: sign in through the WebView, confirm a
bind writes, and confirm a logcat capture of the whole login shows no cookie value.

**Step 6, NovelList.** Port the JWT client, storing the UUID in `remote_url` rather than hashing it.
Depends on the per-type capability below, which landed first so the tracker is never offered on a
manga. Verify: bind and update against a real account, and confirm identity survives an app restart
and a backup round trip, which is what the hash-and-fragment scheme puts at risk.

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
- `app/src/main/java/eu/kanade/tachiyomi/data/track/novellist/`: the NovelList client, `NovelListApi`
  over the documented routes plus its bearer interceptor and `dto/`.
- `app/src/main/java/reikai/domain/track/TrackerContentSupport.kt`: the per-type capability kernel
  both tracking sheets filter through.
- `data/src/main/sqldelight/tachiyomi/data/novel_tracks.sq`: `remote_id INTEGER`, `remote_url TEXT`.

## Status

**RanobeDB is built (steps 1 to 3, plus step 5 out of order), and partly verified on device.** The
two logins, the series-bound tracker and its Fill-from-tracker support are in, alongside the shared
`pushChapterProgress` kernel that fixed the stale local row for both content types. Unit tests cover
the schema and the kernel, both verified by mutation.

Verified against a real account: **both logins**, token and WebView cookie, each validating through
`/user/me` before storing. And a **bind writes**, proven rather than assumed, because
`AddNovelTrack.bind` does not catch, `awaitSuccess` throws on a non-2xx, and the local row exists
anyway. Still unverified: a write carrying the *cookie* credential rather than the token, score and
date round-trips, `refresh`, unbind, and the read-while-syncing gate.

Grounded 2026-08-22 by reading both reference implementations, and re-verified 2026-08-25 against the
RanobeDB server source directly, because the published docs still describe the API as read-only.

Three things that reading settled, none of them visible from tsundoku's client:

- **tsundoku does not use the public API to write.** It posts SuperForms payloads to `/api/i/user/...`,
  a `+page.server.ts` form action backing the website's own UI, authenticated by the `auth_session`
  cookie. Roughly 210 of its 475 lines had no counterpart here. Note the asymmetry: its endpoint
  cannot take a token, but **ours takes either credential**, because `hooks.server.ts` resolves the
  cookie into the caller before it looks for an `Authorization` header. So their design is not the
  alternative to ours, it is what predates the v0 user routes.
- **The write routes are merged and live but undocumented.** The docs page source says the API
  "currently only supports read-only endpoints", so `/api/v0/user/` carries no deprecation promise.
  That is why `RanobeDbDtoTest` pins the payload shape: nothing else would notice it moving.
- **Nothing can read a user's own list entry back.** The only `GET` under `/api/v0/user/` is `me`;
  `book`, `series`, `release` and `publisher` export `PUT` and `DELETE` only, and the
  series detail route never passes the caller's id into `getSeriesOne`, so `refresh` can only refresh
  catalogue metadata and the local row stays authoritative for status, score and progress.

Remaining before this counts as done: bind, progress, score, dates, refresh and unbind against a real
account, each confirmed on the RanobeDB site rather than only in-app.

## Decisions & tradeoffs

**RanobeDB first, and alone at first.** Of the four it has the curated catalogue, stable integer ids,
a token flow needing no OAuth redirect and no secret in the APK, and tags that let it feed
recommendations. Its NSFW flag was part of that case when the tracker adult-content filter existed;
that filter was removed, so only the tags count now. See
[recommendations-adult-content.md](recommendations-adult-content.md).

**"No public API" is a maintenance cost here, not a blocker.** NovelUpdates has no documented API and
sits behind a Cloudflare managed challenge, and NovelList documents its routes but does not advertise
them (see below). Reikai already carries the extension ecosystem through exactly that:
`NetworkHelper` installs `CloudflareInterceptor` with a FlareSolverr fallback, and `AndroidCookieJar`
shares OkHttp's cookies with the WebView's own store, so a WebView login carries straight into API
calls. Both are buildable; the real cost is that scraped
selectors and private endpoints rot, which is why they are sequenced last and may be dropped.

**A tracker now declares which content types its catalogue holds, and a novel-only one is hidden
from manga.** `Tracker` gained `supportsManga` beside `supportsNovels`, defaulting true in
`BaseTracker` because every service inherited from upstream catalogues manga. Both tracking sheets
and the manga details count filter through one kernel, `List<Tracker>.supportingContent(isNovel)` in
`reikai/domain/track/TrackerContentSupport.kt`, so the rule is pinned once rather than restated per
surface, and one `@ParameterizedTest` covers both types.

This is the write-once exit the content-layer rule asks for, and the named mechanism is the remote
catalogue. RanobeDB lists light novels only. NovelList markets itself as a "Novel and Manhwa
Directory", but its one catalogue endpoint is `/api/novels/filter`, its records carry no medium
field, and pure manhwa are absent: Tower of God, Lookism and The Breaker each return zero results,
while Omniscient Reader returns the 551-chapter novel and Solo Leveling carries `TAG: Adapted to
Manhwa` on a novel row. Manhwa are not bindable entities there.

Binding across the two types is worse than an empty search. For the overlap titles a user reading
the manhwa would bind the novel entry, and that work's chapter count would then drive progress sync,
which is the same failure the RanobeDB volume count already forced `total_chapters = 0` for.

Before this, `RanobeDb` was offered on every manga's track sheet once logged in, and its
`search()` delegates to `searchNovel()`, so a manga search answered with light novels. The library
tracking filter is deliberately left listing every logged-in tracker: that sheet is shared by one
library holding both types, ruled in `reikai/presentation/library/LibraryEngine.kt`.


**NovelList publishes an OpenAPI document, so it is only the human-facing docs that are missing.**
`GET /api/openapi.json` returns a complete OpenAPI 3.1.0 spec titled "Novellist API 1.0.0", covering
every route with request and response schemas, enum values and numeric constraints. Their site has
no `/docs`, `/developers` or terms page, and web search finds nothing, so "no public API" was right
about discoverability and wrong about the contract. The DTOs are typed from the spec rather than
from sample responses, which matters because it marks `chapter_count` and the three collections
nullable where 90 live records had no null, and a wrong non-null throws at parse time.

Four things the spec settled that tsundoku's client does not show:

- **Unbind exists.** `DELETE /users/current/reading-list/{novel_id}` is a real route, so NovelList
  can implement `DeletableTracker`. tsundoku has no delete at all.
- **The entry reads back.** `GET` on the same path returns `status`, `chapter_count`, `rating` and
  `note`. Unlike RanobeDB, an update can therefore carry what it is not changing, so writes need not
  be destructive. `note` is the field at risk, because nothing in Reikai edits it.
- **The write is a partial update, with one exception.** Every field in the `PUT` body is optional
  and a null is omitted rather than sent, so `status`, `rating` and `note` are left alone when
  absent. `chapter_count` is not: see the measured write contract below.
- **There is no on-hold status.** The reading-list enum is `COMPLETED`, `DROPPED`, `IN_PROGRESS`,
  `PLANNED`, `UNKNOWN`. tsundoku maps `ON_HOLD` to `PLANNED`, which round-trips back as plan-to-read.
  Under the capability rule that is a silent no-op, so the status is not offered instead.

**Every write must carry `chapter_count`, and nothing in their document says so.** Measured against
the live service: a `PUT` that omits `chapter_count` resets it to 0, so a status-only or score-only
edit silently wipes the user's reading progress. `rating` and `note` are genuinely preserved when
omitted; `chapter_count` alone is defaulted rather than left alone. `NLUpdateRequest` therefore
declares it non-optional, and a test asserts the serializer still marks it required, because the
regression is invisible at the call site and destroys user data at runtime.

This took four probes because the first three each compared zero against zero: their own setup wrote
a status or a score after arming the progress, which zeroed the field under test before the test ran.
The probes live in `devtools/api-probes/novellist_*.py` (local only). The one that settles it re-arms
progress immediately before each write, so a zero afterwards can only have come from that write.

It is very likely a defect on their side rather than a deliberate contract, since it would lose a
chapter count for anyone changing status through the website too. Worth reporting to the maintainer,
and worth coding around regardless: a fix would not reach the deployed backend on any known schedule.

**`rating` is a float constrained to 1..10**, so an unset score cannot be pushed as 0, which is what
tsundoku's 422 comment refers to. Clearing a score has no representation and is recorded as a gap.

**None of tsundoku's spoofed CORS headers are reproduced.** Every write route answers 401 rather
than 403 without them, including a form-encoded `DELETE`, so the backend reaches its handler unaided.
That is the opposite of RanobeDB, whose SvelteKit host needs `Origin` on any non-GET.

**The backend is not the site.** It answers on `novellist-be-960019704910.asia-east1.run.app`, a
generated Cloud Run hostname carrying their project number, and it is the only host the spec
describes. A move to a custom domain would brick the client, which is the same risk that keeps
MyNovelList's base URL editable.

**MyNovelList's risk is who runs it, not whether it works.** IReader points at
`mynoveltracker.netlify.app`, its own deployment, and not at mynovellist.net, which exposes no API at
all. The catalogue is user-created and there is no content rating. Keeping the base URL editable is
the mitigation, and depending on it is a judgement call rather than a technical one.

**The reference defects above are the reason this is a port and not a copy.** Blocking calls in
coroutines, writes that cannot fail, and session cookies in the log are each individually small and
collectively the difference between shipping these and regretting them.
