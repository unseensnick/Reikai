# MangaDex enhanced source

## Goal

Wrap the installed MangaDex extension in a Reikai delegated/enhanced source that adds MangaDex-native behaviour: richer metadata (tags with namespaces, cross-tracker ids, ratings), OAuth login, an MDList tracker, follows sync (import your MangaDex library, push local favourites back), and a settings hub. A general MangaDex feature; it lives under `exh/` only for historical parity with the borrowed subsystem.

## Why

MangaDex is one of the most-used sources, and the stock extension is a thin HTML/JSON reader: no login, no follows sync, no metadata enrichment, no MDList tracking. Komikku ships all of this as an enhanced source; Reikai already ported the enhancement *machinery* (for the EXH adult sources) but never the MangaDex payload. This also unblocks the roadmap's MangaDex source-native similarity carousel. Reference implementation: Komikku (`refs/komikku`), which Reikai borrows from; Komikku sits on an older Mihon source-api, so the port is a re-typing job, not a copy.

## Status

**IN PROGRESS.** On branch `feat/mangadex-source` off `main` (not `roadmap/later`). Scouted (two Explore passes: Komikku payload + Reikai delegation machinery) and confirmed; Phase 0 underway. This doc is the forward plan; each phase's notes get rewritten to "what shipped" (with commit SHAs) as it lands. Size: `[L]`, multi-session. The `exh/md` payload is ~37 files / ~3,535 LOC in Komikku plus the wrapper, tracker, metadata, and settings glue.

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

- `app/.../source/online/all/MangaDex.kt`, the `DelegatedHttpSource` subclass. Komikku implements `DelegatedHttpSource + MetadataSource + FollowsSource + LoginSource + RandomMangaSource + NamespaceSource + UrlImportableSource`. Only `DelegatedHttpSource + MetadataSource` are needed for the core; the rest are per-phase.
- `app/.../exh/md/` subsystem (~37 files): `dto/` (10), `handlers/` (11, incl. `MangaHandler`, `PageHandler`, `ApiMangaParser`, `FilterHandler`, `FollowsHandler`, `SimilarHandler`, and the 7 external-aggregator handlers), `service/` (`MangaDexService`, `MangaDexAuthService`, `SimilarService`), network (`MangaDexAuthInterceptor`, `MangaDexLoginHelper`), `utils/` (`MdUtil`, `MdApi`, `MdConstants`, `MdLang`, `FollowStatus`, `MdExtensions`), and the follows/login/similar UI.
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

**Phase 1, MVP delegated source (browse/search/details/chapters/pages).** Port `MangaDexService` (native endpoints), `MangaHandler`, `PageHandler` (MangaDex at-home path only, skip external aggregators), `ApiMangaParser` (details + chapter parse), `FilterHandler`. Write `MangaDex.kt` as `DelegatedHttpSource` and register it in `DELEGATED_SOURCES` (`::MangaDex`, `factory = true`, package `eu.kanade.tachiyomi.extension.all.mangadex`). **Re-type the big delta here:** Komikku's split `getMangaDetails` + `getChapterList` collapse into Reikai's combined `getMangaUpdate`; no `SManga.copy(args)`; DTO title/description are language maps, not strings (see EXH port memory). Acceptance: install the MangaDex extension on-device, toggle delegate on, browse/search/open details/read a chapter through the enhanced source; confirm it matches the raw extension plus MangaDex metadata parsing. Verify the delegate id lands in `MANGADEX_IDS` (dump the installed MangaDex source id, like the Comick lesson) or the wrap never activates.

**Phase 2, metadata + details rendering.** `MangaDexSearchMetadata : RaisedSearchMetadata`; wire `MangaDex : MetadataSource<MangaDexSearchMetadata, Triple<MangaDto, List<String>, StatisticsMangaDto>>`; `ApiMangaParser.parseIntoMetadata` populates it (tags with `Demographic`/`Content Rating`/`Tags` namespaces, cross-tracker ids from `manga.attributes.links`, rating from statistics). Store via the existing `search_metadata` tables. Add a MangaDex branch to `GalleryInfoBox` (E-Hentai's curated layout is the template; generic `getExtraInfoPairs` is the fallback). Acceptance: details show MangaDex tags/rating/namespaced chips; metadata persists and reloads from DB.

**Phase 3, OAuth login + MDList tracker.** Port `MangaDexAuthInterceptor` (OAuth2 PKCE: bearer header, refresh-on-401), `MangaDexLoginHelper` (auth-url + code-exchange + logout), `MangaDexLoginActivity` (extend `BaseOAuthLoginActivity`). Store the OAuth blob in `TrackPreferences.trackToken(mdList)` (same as AniList). Port `MdList : BaseTracker` and register in `TrackerManager`; `bind/refresh/update` proxy to the source's follow-status + rating endpoints via `MangaDexAuthService`. Acceptance: log in, MDList appears as a tracker, bind a MangaDex title, status/rating round-trips to the account (device-verified against a real MangaDex account).

**Phase 4, follows sync.** Port `FollowsHandler` + the authed follows endpoints. Import ("Sync Follows to Library", filtered by the selected follow statuses) and push ("Sync Library to MangaDex", flips UNFOLLOWED->READING for local MD favourites). Drive from a worker. Acceptance: import brings followed titles into the library as favourites with metadata + chapters; push updates the account.

**Phase 5, settings hub.** A `SearchableSettings` MangaDex screen (or embed in the source's `ConfigurableSource.getPreferenceScreen()`): Login, Preferred MangaDex language id, Sync Follows to Library, Sync Library to MangaDex. Back the 8 advanced per-language prefs (`dataSaverV5_`, `usePort443_`, `blockedGroups_`, `blockedUploader_`, `thumbnailQuality_`, `tryUsingFirstVolumeCover_`, `altTitlesInDesc_`, `finalChapterInDesc_`); Komikku leaves these UI-less, decide whether to surface them.

**Phase 6, optional polish (defer / separate).** Similar-manga recs (`SimilarHandler` + `SimilarService`, feeds the roadmap MangaDex-similarity carousel); the 7 external-aggregator page handlers (MangaPlus, Bilibili, Comikey, Azuki, MangaHot, Namicomi, chapters hosted off-site); cover-quality / data-saver / first-volume-cover niceties. None block a useful enhanced source.

## Post-port audit (run after each phase lands, and a full pass before ship)

Once a phase's files are ported, audit the Reikai port against the Komikku source to confirm behavioural parity, treating the divergences below as **expected** (a match, not a gap) and anything else as a finding. Diff each ported file against its Komikku original symbol-by-symbol; for each Reikai difference, classify it as an expected divergence, an intentional Reikai improvement, or a real porting bug. Spawn an audit agent (Komikku file as reference, Reikai file as subject) per file group so it stays grounded in `file:line`.

**Expected divergences (do NOT flag these):**

- **`getMangaUpdate` collapse.** Split `getMangaDetails`/`getChapterList` become one `getMangaUpdate` guarded by `fetchDetails`/`fetchChapters`.
- **`SManga.create().apply {}`** in place of Komikku's `SManga(...)` constructor; no `copy(args)`.
- **`MdUtil` incremental.** Methods absent in an early phase are deferred by design (source-discovery -> Phase 1, OAuth -> Phase 3, i18n desc helpers -> Phase 2), not missing.
- **Reikai i18n.** `SYMR.strings.*` (Komikku moko) re-pointed to Reikai's i18n resources; string keys/values may be renamed.
- **`exh.util.under` / `nullIfZero`** ported or inlined rather than imported.
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
- **Missing tiny helpers.** Reikai lacks `exh.util.under` (2-line infix, needed by `MdExtensions` in Phase 0) and `exh.util.nullIfZero` (needed by `getEnabledMangaDex` in Phase 1). Port the one-liners or inline.
- **`MdUtil` forward deps.** Ported incrementally, not whole (see Phase 0). Its source-discovery / OAuth / i18n methods land with their phases.
- **DTO maps.** MangaDex title/description are per-language maps in the API JSON; parse with `MdLang` fallback, not a bare `String`.
- **OAuth2 PKCE.** Real code-verifier/challenge; store tokens via `TrackPreferences.trackToken`, not raw SharedPreferences; refresh on 401.
- **MDList tracker id.** Komikku uses `60L`. Pick a stable, unused Reikai tracker id (Reikai's go up to Suwayomi `9L`); ids are persisted with tracks, so choose once and never change. Matching Komikku's `60L` is the safe default if free.
- **Extension availability (the Comick lesson).** Confirm the MangaDex extension is installed and its source id is in `MANGADEX_IDS` before trusting the wrap. MangaDex, unlike stock Comick, is actively distributed, so this is expected to hold; still verify on-device.
- **Library-update exclusion.** Decide whether MangaDex favourites should skip the normal update sweep (E-Hentai does); if so, add to `LIBRARY_UPDATE_EXCLUDED_SOURCES`.
- **Minified build.** Net-new `exh.md` package uses Injekt generics; it is under the existing `exh.**` keep, but verify a minified `:app:assemblePreview` after Phase 0 anyway.

## Decisions and open questions (resolve as you build)

1. **Scope for a first ship.** Phases 1-3 (delegated source + metadata + login/MDList) already deliver a strong enhanced source; follows sync (4) and settings (5) can trail. Similar/aggregators (6) are separate. Recommend shipping 1-2 first (no account needed), then 3-5.
2. **Follows-sync worker shape.** Reuse Komikku's `LibraryUpdateJob` target approach vs a dedicated `WorkManager` job like the shipped one-way E-Hentai favorites backup. Prefer the dedicated job (smaller blast radius on `LibraryUpdateJob`).
3. **Advanced prefs UI.** Surface the 8 per-language prefs (data-saver, blocked groups, cover quality) or keep them hidden like Komikku. Surfacing data-saver + cover-quality is the highest-value subset.
4. **External aggregators.** Port all 7 handlers, only MangaPlus, or none for v1? Chapters hosted on those sites won't read without them, but they add ~490 LOC and per-site fragility.
5. **`MdList` naming.** Reikai i18n names should say "MangaDex" (the tracker), not "MDList", if that reads clearer to users.
