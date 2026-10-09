# Tracking

## Purpose

Tracking binds a library entry to an online list service (AniList, MyAnimeList, RanobeDB and so on) and keeps reading progress, status, score and dates in sync with it. Manga and novels use the same sheet, the same tracker services and the same sync rules; a binding made on one source of a merged series counts for the whole group. Separately, an extension that keeps its own reading list on its site is told what the user read, added and removed.

## How it works

### Trackers and what each catalogues

Every tracker is a Mihon `Tracker` (`BaseTracker` subclass) registered in `TrackerManager`. Reikai adds three novel-first services as Reikai-owned files under `data/track/`: NovelUpdates (id 100), NovelList (101) and RanobeDB (102). Ids persist with saved tracks and never change.

A tracker declares which content types its catalogue holds with `supportsManga` and `supportsNovels` (`// RK` on `Tracker` and `BaseTracker`; manga defaults true, novels false). Seven services take both types: AniList, Kitsu, MyAnimeList, MangaUpdates, Shikimori, Hikka and MangaBaka. The three novel services set `supportsManga = false`. Bangumi, MDList and the server trackers (Komga, Kavita, Suwayomi) stay manga only. Every surface that offers trackers filters through one kernel, `supportsContent(isNovel)` in `TrackerContentSupport.kt`: the tracking sheet, both details screens' Tracking button and count, and the novel add-time bind. The library's tracker filter is the one exception and lists every logged-in tracker, since one library holds both types.

Search is the only place the manga trackers needed a novel branch. Mihon's `search()` asks each API for manga-type entries and filters light novels out, so `Tracker.searchNovel` (default: the manga search) is overridden by each novel-capable service to ask for novel entries instead (Kitsu splits on subtype, `isNovel()` in `KitsuApi`; the per-service media-kind tests are in `TrackerMediaKinds.kt`). Binding, finding, updating and OAuth are Mihon's code for both types. The `id:` search prefix is parsed once in `BaseTracker.trackerSearchId`, so a tracker's manga and novel searches answer it the same way.

### The tracking sheet and the per-type port

Both details screens open one sheet, `EntryTrackInfoDialog`, carrying only the entry's `EntryId`. Everything engine-specific goes through an `EntryTrackPort`, which `EntryTrackPorts.of(entry)` picks in one exhaustive `when`: the group-wide track read, refresh, the auto-bind entry, `supports`, search, bind, the group-wide unbind, and the `TrackWriter` that persists a field edit. Manga goes through Mihon's `manga_track` interactors (`GetTracksInGroup`, `RefreshTracks`, `DeleteTrackInGroup`); novels through Reikai's `novel_tracks` ones (`GetNovelTracks`, `AddNovelTrack`, `DeleteNovelTrack`, `RefreshNovelTracks`, `NovelTrackUpdater`). `NovelTrack` converts to the domain `Track` with `toUiTrack` so the sheet renders one type.

What the sheet offers is `offerTrackers(port, loggedIn, autoBind)`: logged-in trackers the type supports, minus auto-binding trackers that only offer themselves for entries they accept (a manga server's tracker). The details Tracking button counts by the same rule (`trackingButtonState`), so its count and its "open settings instead" gate match the sheet. A tracker that is a `ReplacingWriteTracker` (RanobeDB) asks before binding, because its write clears list fields it does not carry.

### Writes

- **Bind.** `AddNovelTrack` and Mihon's `AddTracks` both fill a read entry's start date and progress through the `bindBackfill` kernel, reading only that entry's own history.
- **Field edits** reach the service through one guarded push, `pushTrackEdit` (called from `BaseTracker.updateRemote` and from `NovelTrackUpdater`). It holds the `sendsProgressTo` guard that never sends a manga server a 0, and the tracker-worded failure toast. The field transitions themselves are `TrackFieldMutations`, shared with upstream's `BaseTracker` setters.
- **Reading progress** pushes from the reader on finishing a chapter (gated by `autoUpdateTrack`) and from the details mark-read action (the never / always / ask `AutoTrackState`). `TrackChapter` (manga, `// RK`) and `TrackNovelChapter` both call `pushChapterProgress`, which also stamps today's start date when a dated tracker's refreshed row has none and progress moves up from 0, since each service only stamps it at exactly chapter 1. Both return a `ChapterPushOutcome` naming the trackers that failed. `EntryAutoTrackOnMarkRead` gathers the refresh and push failures of one mark into at most one toast and shows nothing when the push lands. The reader and the delayed jobs ignore the outcome.
- **Offline.** A push that fails is queued. Manga uses Mihon's `DelayedTrackingStore` over `tracking_queue`, novels the same class over `novel_tracking_queue` (`NovelDelayedTrackingStore`), each with its own WorkManager job. Both jobs run `drainDelayedTracking` and schedule `delayedTrackingRequest` through `enqueueDelayedTracking`: retry once online, exponential backoff from five minutes, give up after four runs, drop an item whose track is gone.
- **Unread.** No tracker moves a site back on unread by default. A tracker implementing `UnreadPushTracker` (NovelUpdates, behind `novelUpdatesUnreadPush`) is told by `PushNovelUnread`, on an app-scoped coroutine so leaving the screen does not cut the write short.
- **Removal.** Both types' remove dialog calls `EntryTrackPort.removeTrack`, which runs on `RemoteFirstRemoval`: with "Also remove from" ticked, the service delete runs first and the local group-wide unbind only once it succeeds. A failure is toasted with the binding kept so the user can retry.

### Reads, refresh and errors

`GroupTrackReader` (behind `GetTracksInGroup` and `GetNovelTracks.awaitGroup` / `subscribeGroup`) reads every member's tracks while `sync_tracker_links_grouped` is on, keeps one row per tracker (the furthest read, `canonicalTracksPerTracker`), and looks the group up again whenever library membership changes, so an open details page follows a merge. The group lifecycle (hand-out before a split, `handOutGroupTrackers` via `PropagateTrackerLinks` and `PropagateNovelTrackerLinks`) is described in [merged-series.md](merged-series.md). A group refresh writes each row back to the member that owns it; a server tracker's progress marks chapters only on the source it serves (`// RK` in `RefreshTracks`).

`TrackerRefreshWorker` refreshes every bound tracker for every library entry of both types, started manually from the library. It is deliberately not part of the library update, which would multiply remote traffic per scheduled run.

Failures are worded in one place, `trackerErrorMessage` in `TrackerErrorMessage.kt`; a list of them is `trackerFailuresMessage`, a repeated line said once. Every interceptor that finds no usable login throws `TrackerSignedOutException` (MDList's `MangaDexAuthInterceptor` too, and NovelUpdates when its account page shows no lists), which reads as signed out. AniList answers a GraphQL error, a revoked token included, with a non-2xx status that Apollo leaves undecoded, so every `AnilistApi` call runs `throwOnAniListError`. Kitsu and Shikimori answer a missing id with HTTP 200 and an empty result, so the GraphQL trackers' metadata reads go through `trackerEntryOrThrow`, which throws `TrackerEntryMissingException`; `isMissingOnTracker` treats that and a 404 alike.

### Fill from tracker

The shared edit-info dialog fills title, cover, description, authors and genres from a bound tracker's `getMangaMetadata` (`// RK` on `Tracker`, ported from Komikku with a genres field). `EntryTrackerAutofill` picks the eligible trackers (self-hosted enhanced ones are dropped) and appends genres to existing tags. `metadataAccess` says whether the fetch needs a login: `SignedIn` by default, `Public` for the three novel services. A signed-out fill on a `SignedIn` tracker is refused before it fetches.

### The novel services

- **RanobeDB** binds to a **series**, not a book (`GET /series/{id}` carries the volume total, status and tags, and delete needs no lookup). It writes through the token routes under `/api/v0/user/` with either a pasted token or a WebView `auth_session` cookie (the server resolves the cookie before the bearer header; a stored cookie is marked by its `auth_session=` prefix, which a base32 token never contains). Both are validated against `/user/me` before storing (`storeCheckedCredential`), which also supplies the username `isLoggedIn` needs. No route reads a user's list entry back, so `refresh` refreshes catalogue metadata only and the local row stays authoritative. Reading progress is never pushed. A read-driven push happens only when the status moves and `ranobeDbSyncWhileReading` is on (default on); sheet edits are never gated. `writeListEntry` skips a body identical to the last one accepted.
- **NovelList** identifies a novel by UUID. The UUID rides in `remote_url` as a fragment (the site only resolves a slug path) and `remote_id` is a 64-bit surrogate (`NovelListIdentity.kt`), not an identity. The API host is a generated Cloud Run hostname, so it is editable (`novelListApiUrl`, https only, with a reset row) and never restored from a backup (`AppPreferenceCarry`). The entry reads back, so writes are partial; `refresh` also re-reads the catalogue's chapter total. There is no on-hold status, so it is not offered.
- **NovelUpdates** is scraped. Status moves the series between reading lists (`NovelUpdatesListMapping`, optionally user-mapped, read in both directions); progress is written into the user's own note, decoded as JSON. A series on a list the mapping does not cover reads as `OTHER_LIST` (0) and a push leaves it there. A read also moves the site's release bookmark. `novelUpdatesNeverBackwards` (default on) keeps the site's later progress when an earlier chapter is read. It is also an `AutoBindTracker`: the site's own sources bind by URL without a search, other novels still search. Genres are taken, tags are not.

Cookie-login trackers (`CookieLoginTracker`: RanobeDB, NovelList, NovelUpdates) sign in through `TrackerWebViewLoginActivity`, which knows nothing about any service; each tracker supplies the login URL, cookie domain and `credentialFromCookies`.

### Auto-bind

`AutoBindTracker` is a tracker that binds an entry from a source it knows, with no search: a manga server's own tracker (`EnhancedAutoBind` over Mihon's `EnhancedTracker`) or a site's tracker for the extensions reading that site (NovelUpdates). `AutoBindOnAdd` binds a newly added entry off the add itself, through `bindOnAdd`, which both `AddTracks.bindEnhancedTrackers` and `BindNovelTrackers` run; one tracker failing stops none of the others.

### Source-side tracking

Some extensions keep reading state on their own site (`SourceTracker` in `source-api`). `SourceTrackerDispatcher`, an app-scoped singleton, feeds `SourceTrackerKernel`, ported from tsundoku's dispatcher, whose behaviour extensions are written against: read and unread events wait a debounce per entry and merge while they agree; adds and removes also wait and go only if the library still agrees, so an add and a remove inside the wait cancel out. Migrations are passed on when `sourceTrackerOnMigration` is on. A failing site toasts once per cooldown. Where a Reikai tracker owns a site (`OwnedSites`: NovelUpdates), the extension's own tracking hooks never run and its settings for them are hidden, because the extension's note sync truncates at the first quote.

## Key files

- `app/src/main/java/eu/kanade/tachiyomi/data/track/Tracker.kt` and `BaseTracker.kt`: `supportsNovels`, `supportsManga`, `searchNovel`, `getMangaMetadata`, `metadataAccess`, `trackerSearchId`, `updateRemote`.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/TrackerManager.kt`: ids and registration (`NOVELUPDATES`, `NOVELLIST`, `RANOBEDB`).
- `app/src/main/java/eu/kanade/tachiyomi/data/track/ranobedb/`, `novellist/`, `novelupdates/`: the three novel services.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/CookieLoginTracker.kt`, `ReplacingWriteTracker.kt`, and `app/src/main/java/eu/kanade/tachiyomi/ui/setting/track/TrackerWebViewLoginActivity.kt`.
- `app/src/main/java/reikai/domain/track/EntryTrackPort.kt`: `EntryTrackPorts.of`, `removeTrack`.
- `app/src/main/java/reikai/presentation/track/EntryTrackInfoDialog.kt` and `TrackerErrorMessage.kt` (`trackerErrorMessage`, `trackerFailuresMessage`).
- `app/src/main/java/reikai/domain/track/GroupTrackReader.kt`, `GroupTrackerHandout.kt`, `TrackerContentSupport.kt`, `PushChapterProgress.kt`, `PushTrackEdit.kt`, `BindBackfill.kt`, `TrackFieldMutations.kt`, `ServerProgress.kt`, `DelayedTrackingDrain.kt`, `RemoteFirstRemoval.kt`.
- `app/src/main/java/reikai/domain/track/autobind/AutoBind.kt`: `AutoBindTracker`, `offerTrackers`, `trackingButtonState`, `bindOnAdd`; `AutoBindOnAdd.kt`, `BindNovelTrackers.kt`.
- `app/src/main/java/reikai/domain/track/source/SourceTrackerKernel.kt`, `SourceTrackerDispatcher.kt`; `site/OwnedSites.kt`.
- `app/src/main/java/reikai/domain/novel/track/`: `NovelTrackUpdater`, `TrackNovelChapter`, `NovelDelayedTrackingStore`, `NovelDelayedTrackingUpdateWorker`, `PushNovelUnread`, `PropagateNovelTrackerLinks`.
- `app/src/main/java/reikai/domain/novel/interactor/AddNovelTrack.kt`, `GetNovelTracks.kt`; `data/src/main/sqldelight/tachiyomi/data/novel_tracks.sq`.
- `app/src/main/java/reikai/data/track/`: `TrackerRefreshWorker`, `TrackerEntryResponse.kt` (`trackerEntryOrThrow`), `TrackerSignedOutException.kt`, `MetadataAccess.kt`, `CheckedCredential.kt`.
- `app/src/main/java/reikai/presentation/details/EntryAutoTrackOnMarkRead.kt`, `EntryTrackerAutofill.kt`.
- `app/src/main/java/reikai/domain/track/KitsuLibraryEntryResolver.kt`, `KitsuEntryIdCopies.kt`, over `app/src/main/graphql/reikai/graphql/kitsu/ReikaiKitsuFindLibraryEntry.graphql`.

## Invariants and traps

- **A tracker whose catalogue lacks a type is hidden for it, never shown disabled.** Binding across the two binds a different work, whose chapter count then drives progress sync.
- **Nothing in a tracker write catches its own failure.** A swallowed write reports success while the service and the local row diverge; the reference forks did this in several places.
- **Never log a cookie or token.** `credentialFromCookies`' argument and result are a live session, and logs reach crash reports.
- **Every RanobeDB write is destructive.** The route replaces status, score, dates, notes, volume count and custom labels from the body, and nothing reads them back first, so a write clears what it does not carry. Hence the bind confirmation, the status-moved gate and the identical-body skip.
- **Every NovelList `PUT` must carry `chapter_count`.** Omitting it resets progress to 0 on the live service (the other fields are preserved when omitted). `NLUpdateRequest` declares it non-optional and a test asserts the serializer still requires it.
- **NovelList's `rating` is 1 to 10**, so an unset score cannot be pushed as 0 and clearing a score has no representation.
- **A NovelUpdates push reads the note before it moves anything.** A refused or unparseable read fails the push with the site untouched, so a bind the app never saves cannot leave the series moved.
- **Signing out of a cookie-login tracker clears the site's cookies** (`clearSiteCookies`, run from `BaseTracker.logout`, Cloudflare clearance included); otherwise the next WebView sign-in captures the old account. RanobeDB's API client uses no cookie jar, because OkHttp's bridge would swap a token login's credential for the WebView's session.
- **A Kitsu row restored from a Yokai backup has the library entry id in `remote_id` and no `library_id`.** `KitsuLibraryEntryResolver` heals it on refresh, update or delete: the normal lookup runs first, only a row without a library id is read as an entry id, and only an entry the signed-in profile owns heals it (entry ids are global). The heal rewrites every copy at once through `KitsuEntryIdCopies`. `bind` never goes through it.
- **A Kitsu delete that hits a GraphQL `errors` answer with HTTP 200 reads as a success**, upstream's shape; remote-first removal cannot see it.

## Decisions

- **Reuse Mihon's tracker services; add only a novel search branch.** Seven of the ten novel trackers are upstream's, so novels inherit their OAuth and maintenance. Void if upstream adds its own novel search.
- **One track row while merged, resolved at read time.** Copies are made only just before a split. Void if trackers ever become per source by default.
- **Two queue files and two jobs, one store class and one drain.** The novel queue is keyed by track id and the two track tables share an id space; WorkManager keys unique work by tag, so each queue retries on its own schedule.
- **RanobeDB never receives reading progress.** It counts volumes and a source counts chapters, so chapter 574 would arrive as 574 volumes. The local row still shows chapters against a volume total of 0; a typed "does not track chapters" capability was judged not worth inventing for one tracker.
- **`ranobeDbSyncWhileReading` defaults on.** The status-moved gate keeps it to about one write per series, and off did not keep the status local, since any score or date edit carried it anyway.
- **NovelUpdates takes genres, not tags.** A series carries eighty or more tags against a handful of genres in one shared field.
- **The NovelList host is editable but https only, and not carried by backups.** Authenticated calls carry the JWT, and a shared backup could point the next sign-in at its author's host.
- **Tracker failures use Reikai's wording everywhere both types call, a deliberate divergence from Mihon.** The settings screen's account refresh and login dialogs still show the raw message, as upstream.
- **Declined services.** MyNovelList (IReader's own deployment with an empty catalogue, and a name shared with an unrelated site that has no API), MiraiList and Novel Trackr (no API), Hardcover (beta, and its terms forbid deployed clients). Revisit Hardcover if it leaves beta with OAuth.
- Open: why NovelUpdates once answered the note read with HTTP 400 during an auto-bind (a WordPress `admin-ajax` 400 means no handler answered for that session).

## Upstream divergences

`// RK` islands sit in `Tracker`, `BaseTracker` (capabilities, id search, shared field transitions, `pushTrackEdit`, cookie clearing), `TrackerManager`, `TrackPreferences`, each novel-capable tracker and its API class (`searchNovel`), `AnilistApi` (`throwOnAniListError`), `KitsuApi` (subtype split, library pull, entry lookup), `TrackChapter`, `RefreshTracks`, `AddTracks`, `DelayedTrackingStore` and `DelayedTrackingUpdateWorker`, and `SettingsTrackingScreen` (token and WebView logins, novel settings). Mihon's `TrackInfoDialog` is replaced by `EntryTrackInfoDialog` and manifested. Recorded divergences: [upstream-sync.md](../upstream-sync.md) "Deliberate divergences" (`RefreshTracks`, the delayed-tracking drain, remote-first removal, `ChapterPushOutcome`, tracker failure wording).

## Extending

- **A new tracker**: subclass `BaseTracker`, set `supportsManga` / `supportsNovels` to what its catalogue holds, override `searchNovel` if it holds novels, set `metadataAccess`, register it in `TrackerManager` with an id that never changes, and throw `TrackerSignedOutException` from its interceptor on a missing login.
- **A cookie login**: implement `CookieLoginTracker`; the WebView activity needs no change.
- **A new surface offering trackers**: go through the type's `EntryTrackPort` and `offerTrackers`, never a direct `supportsNovels` check.
- **A new push path**: call `pushChapterProgress` or `pushTrackEdit`, queue failures through the type's delayed store, and word errors with `trackerErrorMessage`.

## Tests

Port and sheet: `EntryTrackPortConformanceTest`, `TrackingButtonConformanceTest`, `TrackerContentSupportTest`, `AutoBindTest`. Group reads: `GroupTrackReaderTest`, `GroupTrackSubscriptionConformanceTest`, `GroupTrackerHandoutTest`, `RefreshTracksTest`, `RefreshTracksConformanceTest`. Writes: `NovelTrackUpdaterTest`, `MangaTrackWriterTest`, `TrackFieldMutationsTest`, `PushChapterProgressTest`, `ChapterPushOutcomeTest`, `ChapterPushConformanceTest`, `EntryAutoTrackOnMarkReadTest`, `BindBackfillTest`, `ServerProgressTest`, `DelayedTrackingDrainTest`, `DelayedTrackingRequestTest`, `RemoteFirstRemovalTest`. Errors: `AnilistErrorsTest`, `TrackerSignedOutTest`, `NovelUpdatesSignedOutTest`, `TrackerFailuresMessageTest`, `TrackerAutofillErrorTest`, `TrackerEntryResponseTest`. Novel services: `RanobeDbDtoTest`, `RanobeDbApiCookieTest`, `CookieLoginSignOutTest`, `NovelListRefreshTest`, `NovelUpdatesListMappingTest`, `NovelUpdatesReleasesTest`, `NovelUpdatesPushTest`, `HighestStillReadTest`, `ResetToDefaultPreferenceTest`. Kitsu: `KitsuLibraryEntryResolverTest`, `KitsuEntryIdCopiesTest`. Source-side: `SourceTrackerKernelTest`, `SourceTrackedEntriesConformanceTest`, `ReadStatusHandOffConformanceTest`, `OwnedSitesTest`.

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`.

## Related

- User doc: [guides/tracking.md](../../guides/tracking.md).
- Group-wide tracking and the split hand-out: [merged-series.md](merged-series.md).
- Duplicate detection by tracker id: [tracker-aware-duplicate-detection.md](../tracker-aware-duplicate-detection.md).
