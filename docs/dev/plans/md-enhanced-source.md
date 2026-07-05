# MangaDex enhanced source

## Goal

Wrap the installed MangaDex extension in a Reikai delegated/enhanced source that adds MangaDex-native behaviour: richer metadata (tags with namespaces, cross-tracker ids, ratings), OAuth login, an MDList tracker, follows sync (import your MangaDex library, push local favourites back), and a settings hub. A general MangaDex feature; it lives under `exh/` only for historical parity with the borrowed subsystem.

## Why

MangaDex is one of the most-used sources, and the stock extension is a thin HTML/JSON reader: no login, no follows sync, no metadata enrichment, no MDList tracking. Komikku ships all of this as an enhanced source; Reikai already ported the enhancement *machinery* (for the EXH adult sources) but never the MangaDex payload. This also unblocks the roadmap's MangaDex source-native similarity carousel. Reference implementation: Komikku (`refs/komikku`), which Reikai borrows from; Komikku sits on an older Mihon source-api, so the port is a re-typing job, not a copy.

## Status

**IN PROGRESS.** On branch `feat/enhanced-source` off `main`. Phases 0, 1, 2 and 3 shipped and on-device verified (delegated wrap + metadata-enriched details + the gallery-info card + OAuth login and the MDList tracker); Phase 4+ remain. A 5-agent deep-research pass mapped the whole port's blast radius (see the corrections folded through this doc); a later Komikku parity audit added the entries in "Parity-audit additions". This doc is the forward plan; each phase's notes get rewritten to "what shipped" (with commit SHAs) as it lands. Size: `[L]`, multi-session. The `exh/md` payload is ~37 files / ~3,535 LOC in Komikku plus the wrapper, tracker, metadata, and settings glue.

**Deep-research headline.** No structural blocker: no DB migration (metadata tables are `manga_id`-keyed, source-agnostic; MdList uses the existing `manga_sync`), no new R8 keep (`exh.md.**` under `exh.**`, `mdlist` under `eu.kanade.**`), DI interactors already registered (`DomainModule`), tracker id `60L` free, no `DatabaseHandler` anywhere in the payload. The recurring surprise has one root cause: Reikai's source-api provides abstractions that make the correct port **leaner and differently shaped** than a Komikku copy (chiefly: `MetadataSource` owns the metadata round-trip, so `ApiMangaParser` is parse-only). Net-new source-api types for later phases: `FollowsSource`, `LoginSource`, `RandomMangaSource`, `MetadataMangasPage` (only `NamespaceSource` exists today).

**How the wrap activates (the Comick lesson).** `toEnhancedSource` (`AndroidSourceManager.kt`) matches an installed extension to a delegate **by source name first** (`DELEGATED_SOURCES.values.find { it.sourceName == source.name }`), then by qualified class name / factory-prefix. The wrap fires on the name `"MangaDex"` + package `eu.kanade.tachiyomi.extension.all.mangadex.MangaDex`, **not** on `MANGADEX_IDS`. `MANGADEX_IDS` (the 62 language source ids) is a *separate* gate (library-update exclusion, later metadata/recs gating). Both must hold; dump the installed source's id/name on-device before trusting either.

## What Reikai already has (reuse, do NOT rebuild)

Reikai's EXH port (E-Hentai, Pururin, 8Muses, nHentai, etc.) already provides the whole enhancement scaffold:

| Component | Reikai location | Reuse |
|---|---|---|
| Delegated-source wrapping | `source-api` `exh/source/EnhancedHttpSource.kt`, `DelegatedHttpSource.kt`; `toEnhancedSource` + `DELEGATED_SOURCES` in `AndroidSourceManager.kt` | 100%, just add a registry entry |
| Delegate on/off toggle | `core/common` `exh/pref/DelegateSourcePreferences.kt` (`delegateSources`) | 100%, MangaDex inherits it |
| Metadata contract | `source-api` `eu/kanade/tachiyomi/source/online/MetadataSource.kt` | interface to implement |
| Metadata base + flatten | `source-api` `exh/metadata/metadata/RaisedSearchMetadata.kt`, `base/FlatMetadata.kt`; template `EHentaiSearchMetadata.kt` | subclass it |
| Metadata storage | `data` `search_metadata.sq` (+ `search_tags`, `search_titles`); `MangaMetadataRepositoryImpl` | reuse tables as-is |
| Metadata on details UI | `presentation` `manga/components/GalleryInfoBox.kt` (+ `NamespaceTags`) | add a MangaDex branch |
| Tracker framework | `data/track/Tracker.kt`, `BaseTracker.kt`, `TrackerManager.kt` | subclass + register |
| Tracker OAuth token storage | `TrackPreferences.trackToken(tracker)`; pattern in `data/track/anilist/Anilist.kt` | store MD OAuth the same way |
| OAuth login activity | `ui/setting/track/BaseOAuthLoginActivity.kt` | extend for the MD callback |
| Source login (prefs/webview) precedent | `exh/ui/login/EhLoginActivity.kt` | reference only |
| Settings screen convention | `presentation/more/settings/screen/SearchableSettings.kt`; `ConfigurableSource`; prefs pattern `exh/source/ExhPreferences.kt` | build the hub on it |
| Net-new-package proguard keep | `app/proguard-rules.pro` `-keep ... exh.**` | already covers `exh.md.**` |

## What is build-new (the MangaDex payload to port + re-type)

Port from Komikku and re-type onto Reikai's current source-api. Suggested package parity with Komikku (all under the `exh.**` keep):

- `app/.../source/online/all/MangaDex.kt`, the `DelegatedHttpSource` subclass. Komikku implements `DelegatedHttpSource + MetadataSource + FollowsSource + LoginSource + RandomMangaSource + NamespaceSource + UrlImportableSource`. `DelegatedHttpSource + MetadataSource + NamespaceSource` are the Phase 1 core (all present in Reikai); `FollowsSource` (P4), `LoginSource` (P3), `RandomMangaSource` (P6) are **net-new interfaces absent in Reikai** and get ported with their phase, as does the `MetadataMangasPage` type (P4).
- `app/.../exh/md/` subsystem (~37 files): `dto/` (10), `handlers/` (incl. `MangaHandler`, `PageHandler`, `ApiMangaParser`, `FilterHandler`, `FollowsHandler`, `SimilarHandler`, and the 6 external-aggregator handlers: MangaPlus, Bilibili, Comikey, Azuki, MangaHot, Namicomi), `service/` (`MangaDexService`, `MangaDexAuthService`, `SimilarService`), network (`MangaDexAuthInterceptor`, `MangaDexLoginHelper`), `utils/` (`MdUtil`, `MdApi`, `MdConstants`, `MdLang`, `FollowStatus`, `MdExtensions`), and the follows/login/similar UI.
- `source-api/.../exh/metadata/metadata/MangaDexSearchMetadata.kt`, `RaisedSearchMetadata` subclass (mdUuid, cover, title, altTitles, description, authors, artists, langFlag, lastChapterNumber, rating, cross-tracker ids, status, followStatus). `EHentaiSearchMetadata` is the direct template.
- `app/.../data/track/mdlist/MdList.kt`, a `BaseTracker`. In Komikku MDList is **not a separate service**: it wraps the MangaDex source's follows via `MdUtil.getEnabledMangaDex()`, `bind/refresh/update` proxy to the source's follow-status/rating APIs. `FollowStatus` maps to `track.status`.
- `source-api/.../exh/source/SourceIds.kt`, add `MANGADEX_IDS` (62 language source ids, copy from Komikku) + a MangaDex constant; the file comment already reserves this ("MangaDex / Comick ids come with their later phases").
- Settings hub screen + a `MangaDexPreferences` (or extend `SourcePreferences`) for `preferredMangaDexId`, `mangadexSyncToLibraryIndexes`, and the 8 per-language advanced prefs.
- Follows sync worker(s): import + push (Komikku bolts `SYNC_FOLLOWS` / `PUSH_FAVORITES` targets onto `LibraryUpdateJob`; Reikai may prefer a dedicated `WorkManager` job, mirror the shipped one-way E-Hentai favorites backup infra).

## Approach (phased, each phase shippable and testable)

**Phase 0, scaffolding. SHIPPED (`feat/mangadex-source`, `f12da092e`).** What landed:

- **`MANGADEX_IDS`** (62 per-language ids, verbatim from Komikku) added to Reikai's `source-api` `SourceIds.kt`. No separate id/package constant (Komikku has none; the wrap matches by name, so the package literal is a Phase 1 concern). Deliberately **not** in `LIBRARY_UPDATE_EXCLUDED_SOURCES`: MangaDex gains chapters through the normal sweep.
- **`dto/` (9 files, app module)**: pure `@Serializable` data classes, verbatim. `MangaDto` / `RatingDto` keep `JsonElement` fields for the per-language title/description/links maps.
- **`utils/` (app module)**: `MdApi`, `MdConstants`, `MdLang`, `FollowStatus`, `MdExtensions`, and a partial `MdUtil`. `MdExtensions` inlines Komikku's `exh.util.under` as a plain `<` (only two uses in the whole payload, so no util file added).

Deferred by design (dependencies do not exist yet, or YAGNI until their phase):

- **`MdUtil` source-discovery** (`getEnabledMangaDex(s)`, needs the `MangaDex` class + a `preferredMangaDexId` pref) -> **Phase 1**; **OAuth** (`saveOAuth`/`refreshTokenRequest`/`getPkceChallengeCode`) -> **Phase 3**; **i18n desc helpers** (`addAltTitleToDesc`/`addFinalChapterToDesc`, Komikku `SYMR`) -> **Phase 2**; **`encodeToBody`** (POST-body helper) -> **Phase 1** with the services.
- **`MdApi` / `MdConstants` auth members** (`baseAuthUrl`, `login`, `token`, `Login` PKCE object) -> **Phase 3** with OAuth.
- **`MangaDexRelation`** (source-api enum) -> **Phase 6**, since it needs 17 `relation_*` i18n strings and is only consumed by the deferred similar/relations feature. Porting it now would force premature i18n additions.

**Re-typing done:** `SManga.create().apply {}` for `createMangaEntry` (no Komikku `SManga(...)` constructor); Komikku `// KMK` / `// KMM` provenance markers stripped.

**Verified:** `:app:compileDebugKotlin` clean (no warnings from the new files). Minified `:app:assemblePreview` intentionally deferred to Phase 1: Phase 0 has no `Injekt.get<T>()` (the one such call, `getEnabledMangaDex`, is deferred), so the R8/`FullTypeReference` failure mode cannot occur yet, and the package is already under the `exh.**` keep.

**Phase 1, MVP delegated source. SHIPPED + on-device verified** (`79e4fc844` metadata + service, `2d839d54f` wrapper + registration, `28ba52d7b` UA fix). Verified on emulator-5554: opening a MangaDex title (Chainsaw Man, source id `2499283573021220255`, which is in `MANGADEX_IDS`) fills in author, artist, completed status, description, and the namespaced Demographic / Content Rating / Tags chips; chapters load through the delegate. **On-device gotcha (fixed):** the MangaDex API returns HTTP 400 to the browser User-Agent Reikai's network client injects (for Cloudflare bypass), so `MangaDexService` must pass the delegate's `headers` (the extension's `Tachiyomi` UA) to every call, the same way the stock extension does. Details 400'd (Unknown author / 0 chapters) until that landed. The correct Reikai shape is **leaner than a Komikku copy**: Reikai's `MetadataSource` interface already owns the metadata round-trip.

**Key correction (deep research).** Reikai's `MetadataSource.parseToManga(manga, input)` (source-api `MetadataSource.kt`) does the whole round-trip itself (`getFlatMetadataById` -> `parseIntoMetadata` -> `insertFlatMetadata` -> `createMangaInfo`), injecting the interactors via `get() = Injekt.get()`. `EightMuses` is the working template: it implements `MetadataSource`, overrides **only** `parseIntoMetadata`, and its `getMangaUpdate` calls the interface `parseToManga`. So the metadata class comes forward into Phase 1 (parsed and stored here; the GalleryInfoBox *UI* stays Phase 2), and the parser is thinner than Komikku's.

Files (build-new, re-typed onto current source-api):

- `MangaDex.kt` implements `DelegatedHttpSource + MetadataSource<MangaDexSearchMetadata, Triple<MangaDto, List<String>, StatisticsMangaDto>> + NamespaceSource` (all three interfaces exist in Reikai; `MetadataSource` is **core, not deferred**). Register in `DELEGATED_SOURCES` (`::MangaDex`, `factory = true`, sourceId `fillInSourceId`, package `eu.kanade.tachiyomi.extension.all.mangadex.MangaDex`). Keep the `source_$id` SharedPreferences block + per-language pref-key helpers (defaults work with no settings UI until Phase 5).
- **MVP enhances details only; chapters + pages + browse + search delegate to the stock extension**, exactly as `EightMuses` does (`EightMuses.getMangaUpdate` delegates chapters). The stock MangaDex extension already parses chapters and serves at-home pages; the custom chapter/page handlers only add pref-gated behaviour (blocked groups, data-saver: Phase 5) and aggregator routing (Phase 6), so `PageHandler` and `MangaHandler.getChapterList`/`ApiMangaParser.chapterListParse` are **deferred**.
- `exh/md/handlers/ApiMangaParser.kt` is **parse-only, details subset**: `parseIntoMetadata` (DTO -> metadata fill) + `parseStatus`. **Drop** Komikku's `parseToManga`, its three `injectLazy` interactors, and `newMetaInstance` (the `MetadataSource` interface owns all of that); the chapter-parse methods wait for their phase.
- `exh/md/handlers/MangaHandler.kt`: `getMangaDetailsInput` (the `Triple`: viewManga + simple chapters + statistics) + `getSimpleChapters`. `MangaDexService.kt` (shipped).
- `source-api` `MangaDexSearchMetadata.kt` (shipped; `getExtraInfoPairs` stubbed until Phase 2).
- `MdUtil`: `getEnabledMangaDex(s)` stays **deferred** (correction, 2026-07-04 audit: Phase 1 does NOT need it, the service is built per-instance straight off the delegate's `client` + `headers`). It lands with **Phase 3+**, where the tracker / follows / settings hub need a net-new `preferredMangaDexId` source pref + `getMainSource<MangaDex>` to pick one preferred MangaDex source (inline the absent `nullIfZero` as `takeIf { it != 0L }` then).

`getMangaUpdate` (mirrors `EightMuses`): `fetchDetails` -> build the `Triple` via `MangaHandler`, call the interface `parseToManga(manga, triple)`; `fetchChapters` -> `delegate.getMangaUpdate(manga, chapters, fetchDetails = false, fetchChapters = true).chapters`.

Re-typing deltas confirmed:

- **Combined `getMangaUpdate`** collapses Komikku's split `getMangaDetails` + `getChapterList`, mirroring `EightMuses`.
- **`SChapter.create().apply {}`** (interface with `create()`), like `SManga`.
- **Absent Komikku helpers:** `xLogE`/`xLogD` -> Reikai `logcat`; `floor` -> `kotlin.math.floor`; `nullIfZero` -> `takeIf`/`takeUnless`.
- **Description-augmentation + cover niceties dropped -> Phase 5.** `ApiMangaParser` drops the `altTitlesInDesc`/`finalChapterInDesc` branches + params (their prefs have no UI yet and need i18n Reikai lacks), plus `firstVolumeCover` (its pref) and the `coverQuality` suffix (default empty works).
- **`PageHandler` external aggregators stripped -> Phase 6** (at-home only). Its `updateExtensionVariable` reflection into the stock extension's `helper.tokenTracker` is **dropped for MVP** (obfuscation-fragile; its `?: return` guards make it optional); confirm at-home images still load on-device.
- **`FilterHandler` not ported** (unreferenced by `MangaDex.kt`; search filters ride the delegate).

Acceptance: install the MangaDex extension on-device, toggle delegate on, browse/search/open details/read a chapter through the enhanced source; confirm parity with the raw extension. **Dump the installed MangaDex source name + id; confirm the per-language sources are named `"MangaDex"` (so the name-match wraps them) and the id is in `MANGADEX_IDS`** (the Comick lesson). Run a minified `:app:assemblePreview` (first Injekt generics via the `MetadataSource` interactor round-trip).

**Phase 2, details rendering (metadata UI only). SHIPPED + on-device verified (`a39cb5752` pairs, `b3cc17e65` rating card, `559c86cfb` reference-matched card redesign).** The metadata is already parsed, namespaced, and stored in Phase 1 (`MetadataSource` + `ApiMangaParser.parseIntoMetadata`); Phase 2 just renders it. What shipped:

- **`MangaDexSearchMetadata.getExtraInfoPairs`** is now the full faithful port of Komikku's (was a stub returning empty): id, cover, title, authors, artists, language, last chapter number, rating, status, follow status, and the 5 cross-tracker ids. `SYMR`->`MR`; Komikku's commented-out lines (`mdUrl`, `users`, `missing_chapters`) dropped.
- **7 new i18n strings** (`last_chapter_number`, `follow_status`, `anilist_id`, `kitsu_id`, `mal_id`, `manga_updates_id`, `anime_planet_id`), Komikku's exact keys/values, in `i18n` `strings.xml` under an `// RK` Phase 2 comment. The other 8 labels (`id`, `title`, `author`, `artist`, `language`, `status`, `average_rating`, `thumbnail_url`) already existed.

**Curated `GalleryInfoBox` branch added, matching the Komikku reference.** `getExtraInfoPairs` feeds two consumers: the details card and the full `MetadataViewScreen` "more info" viewer. Komikku's MangaDex details card is *not* a generic dump: it shows a curated rating widget (stars + `9.19 - Amazing`) with the full field list behind a More-info button (confirmed against on-device Komikku screenshots). So `GalleryInfoBox` gains a `MangaDexGalleryInfo` branch mirroring `MangaDexDescriptionAdapter`: `RatingStars(rating / 2)` + `"%.2f - <descriptor>"`, where the descriptor buckets `rating.roundToInt()` (9 = Amazing, 10 = Masterpiece) via 12 ported `rating0..rating10` / `no_rating` i18n strings and a private `mdRatingLabel` helper. The full dump (title, author, tracker ids, ...) stays behind More-info via the unchanged `getExtraInfoPairs`. `GalleryInfoBox.kt` is a Reikai net-new file, so no `// RK` fencing. (This reverses an earlier call to skip the branch and use the generic inline path; the reference showed the curated widget is the intended look.)

**Card redesign to match the reference (`559c86cfb`), on-device verified.** The first cut wrapped the rows in an `OutlinedCard` and rendered plain `label: value` lines, which read as a cheap knockoff next to Komikku. Reworked the whole `GalleryInfoBox` to Komikku's `*DescriptionAdapter` look: borderless inline block; EH gets a two-column icon grid (rating + descriptor, size, language, favourites, visibility, uploader) with `MenuBook` / `Storage` / `Bookmark` icons; MangaDex gets a borderless rating row; the "Gallery info" link became "More info" (net-new `more_info` string), moved inline. Sizes matched to Komikku's spec (fields `bodyMedium`, EH rating `bodySmall`, 24dp field icons, 20dp stars, half-star rounding so 4.48 -> 4.5). Also gave EH the rating descriptor ("4.48 - Amazing", `averageRating * 2` into the shared `ratingLabel`). The generic fallback (other metadata sources) keeps the plain rows + a bottom More-info link.

Acceptance (verify on-device): open a MangaDex title, confirm the info card shows the curated rows (rating, last chapter, follow status, tracker ids) and the namespaced Demographic / Content Rating / Tags chips (already from Phase 1); confirm the "more info" viewer shows the full set; confirm metadata persists and reloads from DB.

**Phase 3, OAuth login + MDList tracker. SHIPPED + on-device verified.** Sign in to MangaDex from Settings > Tracking (browser OAuth2 PKCE), bind a title as `MdList`, and its follow status + rating round-trip with the account (verified on-device: login persists, bind, and status + chapter changes reflected on mangadex.org). What landed:

- **`LoginSource`** (source-api, net-new, pure Kotlin) is the only new interface. `MangaDex.kt` implements it (login via `MangaDexLoginHelper`) plus `fetchTrackingInfo`/`updateFollowStatus`/`updateRating` delegating to a Phase-3-subset `FollowsHandler`.
- **OAuth stack** ported verbatim: `MangaDexAuthInterceptor` (bearer + refresh-on-401), `MangaDexLoginHelper`, and `MdUtil`'s `saveOAuth`/`loadOAuth`/`getPkceChallengeCode`/`refreshTokenRequest`. The OAuth blob reuses `MALOAuth`, stored in `TrackPreferences.trackToken(mdList)`. `MdConstants.Login` + `MdApi` auth members un-deferred.
- **`MangaDexAuthService`** trimmed to the 7 tracker endpoints (status get/set, follow/unfollow, rating update/delete, ratings); all DTOs already existed.
- **`MdList : BaseTracker(60L)`** registered in `TrackerManager` (`// RK`). `getEnabledMangaDex` un-deferred but **minimal** (first enabled MangaDex source; the `preferredMangaDexId` picker is deferred to the Phase 5 settings hub). `MangaDexNotFoundException` when none enabled.
- **Login UI is Compose-native**: a `// RK` `TrackerPreference` row in `SettingsTrackingScreen` opens the browser via `MdConstants.Login.authUrl`; the only Activity is the mandatory OAuth callback `MangaDexLoginActivity` on `tachiyomisy://mangadex-auth` (`// RK` manifest entry), reusing Komikku's grandfathered `tachiyomisy` public client (MangaDex has closed new public-client registration, confirmed against their docs). Adds `md_follows_unfollowed` i18n.
- **Dropped as unused in Phase 3**: `createInitialTracker` (Phase-4 / auto-track caller only), `getMangaMetadata`/`hasNotStartedReading` (not in Reikai's `Tracker` interface), `FollowsHandler`'s `lang` param + follows-listing methods (Phase 4).

**The bug that bit (do-not-rediscover):** the auth + login calls must carry the delegate's extension `headers`, not the bare `network.client`. MangaDex rejects Reikai's injected browser User-Agent with an HTTP 400 (serving its web SPA instead of the Keycloak token JSON), the same latent issue Phase 1 hit for `MangaDexService`. `loginHelper` and `MangaDexAuthService` both thread the source's `client` + `headers`. Invisible off-device; the token exchange 400'd until the headers were threaded.

**Known follow-up (this branch):** MDList tracker-search results show no cover, because the generic `MangaCover.Book(data = url)` loads the MangaDex cover CDN (`uploads.mangadex.org`) with the browser UA and gets the same 400. The fix lives in the Coil/image pipeline (a UA rule for the cover host), not the tracker code.

**Phase 4, follows sync.** Port the `FollowsSource` interface + the `MetadataMangasPage` type (**both net-new, absent in Reikai**), the rest of `FollowsHandler`, and the follows UI (`MangaDexFollowsScreen`/`ScreenModel`/`PagingSource`, Voyager). Import ("Sync Follows to Library", filtered by the selected follow statuses) and push ("Sync Library to MangaDex", flips UNFOLLOWED->READING for local MD favourites). Drive from a worker. Acceptance: import brings followed titles into the library as favourites with metadata + chapters; push updates the account.

**Phase 5, settings hub.** A `SearchableSettings` MangaDex screen (or embed in the source's `ConfigurableSource.getPreferenceScreen()`): Login, Preferred MangaDex language id, Sync Follows to Library, Sync Library to MangaDex. Back the 8 advanced per-language prefs (`dataSaverV5_`, `usePort443_`, `blockedGroups_`, `blockedUploader_`, `thumbnailQuality_`, `tryUsingFirstVolumeCover_`, `altTitlesInDesc_`, `finalChapterInDesc_`); Komikku leaves these UI-less, decide whether to surface them.

**Phase 6, optional polish (defer / separate).** Similar-manga recs (`SimilarHandler` + `SimilarService` + `RandomMangaSource` (net-new interface) + `MangaDexRelation` (source-api enum, needs 17 `relation_*` i18n strings), feeds the roadmap MangaDex-similarity carousel); the 6 external-aggregator page handlers (chapters hosted off-site), which carry real weight (MangaPlus XOR-cipher interceptor, Bilibili RxJava + POST token fetch, ~490 LOC, per-site fragility); cover-quality / data-saver / first-volume-cover niceties. None block a useful enhanced source.

## Parity-audit additions (2026-07-04, not in the original phase list)

Surfaced by the Komikku parity audit (`docs/dev/audits/2026-07-04-komikku-parity-details-browse-adult-mangadex.md`, local). Fold these into the phases they belong to:

- **Deep-link + share-to-import** `[M]` (highest-impact unplanned item): a manifest `autoVerify` intent-filter for `mangadex.org` `/manga/ /title/ /chapter/` links + a `UrlImportableSource` impl (`mapUrlToMangaUrl` / `mapUrlToChapterUrl`) + batch-add-by-URL. Reikai's manifest has no MangaDex entries today. Standalone; can land any time from Phase 3 on (pairs naturally with the Phase 3 manifest work for the OAuth redirect).
- **Browse "Random" + "Follows" header buttons** `[S]`: `MangaDexFilterHeader` above browse results (gated by an is-MangaDex check) that launch the Phase 6 random and Phase 4 follows features. Without it those have no discoverable entry point, so ship the button with each feature (Follows button -> Phase 4, Random button -> Phase 6).
- **Long-press MangaDex rating -> copy** `[S]`: a Komikku details gesture. **Verify against [security.md](../../.claude/rules/security.md) (clipboard of source data) before adding**; may be an intentional drop.
- **Two app-migrations** `[S]`, Phase 3+: `LogoutFromMangaDexMigration` (force logout if the login flow changes) + `DeleteOldMangaDexTracksMigration`. Only relevant once login + the tracker ship, and only if the login flow later changes.

## Post-port audit (run after each phase lands, and a full pass before ship)

Once a phase's files are ported, audit the Reikai port against the Komikku source to confirm behavioural parity, treating the divergences below as **expected** (a match, not a gap) and anything else as a finding. Diff each ported file against its Komikku original symbol-by-symbol; for each Reikai difference, classify it as an expected divergence, an intentional Reikai improvement, or a real porting bug. Spawn an audit agent (Komikku file as reference, Reikai file as subject) per file group so it stays grounded in `file:line`.

**Expected divergences (do NOT flag these):**

- **`getMangaUpdate` collapse.** Split `getMangaDetails`/`getChapterList` become one `getMangaUpdate` guarded by `fetchDetails`/`fetchChapters`.
- **`SManga.create().apply {}`** in place of Komikku's `SManga(...)` constructor; no `copy(args)`.
- **`MdUtil` incremental.** Methods absent in an early phase are deferred by design (source-discovery -> Phase 1, OAuth -> Phase 3, i18n desc helpers -> Phase 5), not missing.
- **Leaner `ApiMangaParser` (deep research).** Reikai's parser is parse-only: it drops Komikku's `parseToManga`, its three `injectLazy` interactors, and `newMetaInstance`, because Reikai's `MetadataSource` interface owns the metadata round-trip (`parseToManga`/`fetchOrLoadMetadata`) and `MangaDex.kt` supplies `newMetaInstance`. Expected, not a dropped feature.
- **`PageHandler` reflection hack dropped for MVP.** Komikku's `updateExtensionVariable` (reflection into the stock extension's `helper.tokenTracker`) is omitted; it is obfuscation-fragile and optional. Expected.
- **Reikai i18n.** `SYMR.strings.*` (Komikku moko) re-pointed to Reikai's i18n resources; string keys/values may be renamed.
- **Absent Komikku helpers substituted:** `exh.util.under` / `nullIfZero` inlined; `exh.log.xLogE`/`xLogD` -> Reikai `logcat`; `exh.util.floor` -> `kotlin.math.floor`.
- **`SManga.create().apply {}` / `SChapter.create().apply {}`** for both models (interfaces with `create()`).
- **Phase 1 drops the `altTitlesInDesc` / `finalChapterInDesc` description branches and params** (deferred to Phase 5 with their settings UI and i18n). Not a lost feature.
- **Injekt, not Koin.** DI wiring uses Reikai's Injekt registration; no Koin.
- **`// RK` islands** on any edit to a Mihon-owned file (registry, prefs), where Komikku uses `// SY` / `// KMK`.
- **MVP scope cuts (Phase 1).** No external-aggregator page handlers (MangaPlus, Bilibili, Comikey, Azuki, MangaHot, Namicomi), no login/follows/similar; MangaDex at-home path only. These arrive in later phases (or Phase 6, deferred).
- **`MdList` tracker id.** Reikai's own stable id (Komikku uses `60L`); may differ if `60L` is taken.

**Real findings to catch:** dropped fields in a re-typed DTO or metadata mapping; a handler call silently not collapsed (details or chapters lost); a JSON language-map parsed as a bare `String`; an `apply {}` local-var shadowing a model property (the known EXH-port trap); a net-new `exh.md` sub-package missing from the proguard keep; wrong source-name/id causing the wrap to never activate.

## Key files (reference)

Komikku (port from): `refs/komikku/app/.../source/online/all/MangaDex.kt`, `refs/komikku/app/.../exh/md/**`, `refs/komikku/source-api/.../exh/metadata/metadata/MangaDexSearchMetadata.kt`, `refs/komikku/app/.../data/track/mdlist/MdList.kt`, `refs/komikku/source-api/.../exh/source/SourceIds.kt` (`MANGADEX_IDS`), the MangaDex settings screen, and `refs/komikku/app/.../exh/ui/metadata/adapters/MangaDexDescriptionAdapter.kt`.

Reikai (touch): `AndroidSourceManager.kt` (`DELEGATED_SOURCES`), `source-api` `exh/source/SourceIds.kt`, `TrackerManager.kt`, `GalleryInfoBox.kt`, plus the net-new `exh/md/**`, `MangaDex.kt`, `MdList.kt`, `MangaDexSearchMetadata.kt`, and the settings screen.

## Re-typing deltas and gotchas

- **Combined `getMangaUpdate`.** Komikku overrides split `getMangaDetails`/`getChapterList` (each delegating to `MangaHandler`); Reikai's source-api uses the combined `getMangaUpdate(manga, chapters, fetchDetails, fetchChapters): SMangaUpdate` (`DelegatedHttpSource`). Collapse both handler calls into one method, mirroring `EightMuses.getMangaUpdate`.
- **`SManga` is an interface with `create()`.** Reikai's `SManga` (source-api `source/model/SManga.kt`) is an interface built via `SManga.create().apply { url = ...; title = ...; thumbnail_url = ... }`. Komikku's `SManga(url = ..., title = ...)` constructor calls (in `MdUtil.createMangaEntry` and the handlers) must be re-typed. No `copy(field = ...)`.
- **Absent Komikku helpers (definitive, deep research).** Reikai lacks `exh.util.under`/`underEq` (-> `<`/`<=`), `exh.util.nullIfZero` (-> `takeUnless { it == 0 }` / `takeIf { it != 0L }`), `exh.util.floor` (-> `kotlin.math.floor`), and the whole `exh.log.xLog*` family (-> Reikai `logcat`). Everything else Komikku's md code uses (`capitalize`, `trimAll`, `dropEmpty`, `dropBlank`, `nullIfBlank`, `nullIfEmpty`, `trimOrNull`, `removeArticles`) already exists in Reikai's `core/common` `exh/util`.
- **`MdUtil` forward deps.** Ported incrementally, not whole (see Phase 0). Its source-discovery / OAuth / i18n methods land with their phases.
- **DTO maps.** MangaDex title/description are per-language maps in the API JSON; parse with `MdLang` fallback, not a bare `String`.
- **OAuth2 PKCE.** Real code-verifier/challenge; store tokens via `TrackPreferences.trackToken`, not raw SharedPreferences; refresh on 401.
- **MDList tracker id.** Komikku registers `MdList(MDLIST)` in `TrackerManager` with `MDLIST = 60L`. Reikai's tracker ids stop at Suwayomi `9L`, so **`60L` is free**; ids persist with tracks, so use `60L` (match Komikku) and never change it.
- **Confirmed non-issues (deep research), stop worrying about these:** no `DatabaseHandler` anywhere in the payload (the parser uses interactors, and in Reikai the `MetadataSource` interface owns even those); no SQLDelight migration (metadata tables are `manga_id`-keyed and source-agnostic, MdList tracks use the existing `manga_sync`); no new R8 keep (`exh.md.**` under `exh.**`, `mdlist` under `eu.kanade.**`); the `GetManga`/`GetFlatMetadataById`/`InsertFlatMetadata` interactors are already Injekt-registered (`DomainModule`); the `Page` constructor and `network.GET`/`POST`/`parseAs` are identical.
- **Extension availability (the Comick lesson).** Confirm the MangaDex extension is installed and its source id is in `MANGADEX_IDS` before trusting the wrap. MangaDex, unlike stock Comick, is actively distributed, so this is expected to hold; still verify on-device.
- **Library-update exclusion.** Decide whether MangaDex favourites should skip the normal update sweep (E-Hentai does); if so, add to `LIBRARY_UPDATE_EXCLUDED_SOURCES`.
- **Minified build.** The net-new `exh.md` package uses Injekt generics (first via the `MetadataSource` interactor round-trip in Phase 1, not `getEnabledMangaDex` which stays deferred to Phase 3+); it is under the existing `exh.**` keep, but verify a minified `:app:assemblePreview` at Phase 1 (Phase 0 had no Injekt generics, so it was skipped there).

## Decisions and open questions (resolve as you build)

1. **Scope for a first ship.** Phases 1-3 (delegated source + metadata + login/MDList) already deliver a strong enhanced source; follows sync (4) and settings (5) can trail. Similar/aggregators (6) are separate. Recommend shipping 1-2 first (no account needed), then 3-5.
2. **Follows-sync worker shape.** Reuse Komikku's `LibraryUpdateJob` target approach vs a dedicated `WorkManager` job like the shipped one-way E-Hentai favorites backup. Prefer the dedicated job (smaller blast radius on `LibraryUpdateJob`).
3. **Advanced prefs UI.** Surface the 8 per-language prefs (data-saver, blocked groups, cover quality) or keep them hidden like Komikku. Surfacing data-saver + cover-quality is the highest-value subset.
4. **External aggregators.** Port all 6 handlers, only MangaPlus, or none for v1? Chapters hosted on those sites won't read without them, but they add ~490 LOC and per-site fragility.
5. **`MdList` naming.** Reikai i18n names should say "MangaDex" (the tracker), not "MDList", if that reads clearer to users.
