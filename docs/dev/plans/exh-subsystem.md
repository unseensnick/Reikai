# Adult-source (EXH) subsystem

## Goal

Bring the E-Hentai / ExHentai and enhanced-adult-source experience to Reikai: built-in EH/ExHentai browsing and reading, gallery-metadata indexing with library tag search, a metadata viewer, account settings/login with server-profile sync, a favorited-gallery update checker, and a favorites backup to the account. Off by default behind the "Enable adult sources" gate. User-facing reference: [docs/adult-sources.md](../../adult-sources.md).

## Why

The EXH subsystem is a large, well-proven body of work in the Tachiyomi-SY lineage that Reikai's audience expects. Rebuilding it from scratch made no sense, so it is ported from Komikku rather than reinvented. The value Reikai adds is the re-typing onto the current Mihon base (Komikku tracks an older source-API) and the integration glue that makes adult sources behave correctly inside Mihon's library, extension, and update machinery.

## Approach

In plain English: a handful of adult sites get "enhanced" treatment. Instead of the third-party extension's bare listing, Reikai wraps the source so that fetching a gallery also captures its structured metadata (namespaced tags, uploader, rating, page count, dates) into a side store, which then powers library tag search and a metadata viewer. E-Hentai itself ships built-in (no extension needed) with the full filter set and tag autocomplete, plus an account settings screen that syncs your preferences up to your E-Hentai profile. Everything is gated so none of it appears unless the user turns adult sources on.

Mechanism:

- **Delegation core.** `EnhancedHttpSource` / `DelegatedHttpSource` wrap a base extension source; the wrapped source captures metadata on fetch into the gallery-metadata store (`search_metadata` / `search_tags` / `search_titles` tables, SQLDelight migration 23). The enhanced set: nHentai, Pururin, 8Muses, LANraragi (free), plus net-new wrappers for HentaiFox, AsmHentai, and Koharu (SchaleNetwork) that re-parse each site's details into namespaced tags. Delegated sources match by **source name**, not class, so R8-minified factory extensions still wrap (see the `delegated-sources-match-by-name` memory).
- **Built-in E-Hentai / ExHentai.** `EHentai` (under `source/online/all/`) is registered for every EH/ExHentai language id (`EHENTAI_EXT_SOURCES` / `EXHENTAI_EXT_SOURCES` in `SourceIds`), so there is no extension to install and all language variants resolve. Anonymous browse + read, full gallery filters, gallery versions surfaced as chapters. ExHentai access goes through a WebView login (`EhLoginActivity`). **Known browse gap (found 2026-06-30):** the rebase port collapsed Komikku's EH paging + metadata carrier into Mihon's generic paging, so browse loads only the first page and renders bare rows; tracked as the [adult-source browse parity](adult-browse-parity.md) initiative.
- **Tags + search + viewer.** The full E-Hentai tag catalogue (`exh/eh/tags/`) drives browse-side `namespace:tag` autocomplete; saved galleries' tags feed library tag search; `MetadataViewScreen` renders the captured `EHentaiSearchMetadata` as a read-only info panel reachable from gallery details.
- **Account config (uconfig).** `EHConfigurator` / `EhUConfigBuilder` push image-quality, Hentai@Home, and tag-threshold choices to the E-Hentai server profile and persist the session. Surfaced in `SettingsEhScreen` (its own top-level Settings category, gated by the pref).
- **Favorited-gallery update checker.** `EHentaiUpdateWorker` (WorkManager) re-checks favorited EH galleries for a newer version and reconciles the version chain locally via the disk-backed `EHentaiUpdateHelper` (merging chapters, read state, history, categories). EH galleries are deliberately excluded from the normal library sweep (see below), so this is their only update path.
- **Favorites backup (one-way).** `EhFavoritesBackupJob` pushes the library's EH galleries to the account's favorites (a chosen slot, throttled via `ThrottleManager`). It is a backup, not a sync: it never pulls account -> library. See Decisions.

### Reikai integration islands (`// RK`)

These are the Mihon-file edits that wire the subsystem in (grep `// RK`):

- `AndroidSourceManager`: the `currentDelegatedSources` map and `nHentaiDelegatedSourceIds` runtime derivation; the blacklist skip for stock-EH ids.
- `ExtensionManager`: reactive blacklist filter hiding the stock E-Hentai extension while built-in EH is on (`BlacklistedSources`).
- `LibraryUpdateJob`: a `filterNot` dropping `LIBRARY_UPDATE_EXCLUDED_SOURCES` (all EH/ExH language ids + Pururin) and the derived nHentai ids from the sweep, so saved galleries are not re-fetched on every refresh (rate-limit / ban risk).
- `MangaScreenModel` / `MangaScreen`: the favorites-backup hook + the remove-from-account confirm dialog, and the metadata-viewer entry point.
- `reikai/presentation/library/ReikaiLibraryBadges`: the EH-logo badge for built-in EH/ExH source rows (which ship no extension icon).

## Key files

- Delegation + ids: `app/src/main/java/exh/source/` (`EnhancedHttpSource`, `DelegatedHttpSource`, `BlacklistedSources`); `source-api/src/commonMain/kotlin/exh/source/SourceIds.kt`.
- Built-in source: `app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentai.kt`.
- Metadata model + viewer: `source-api/.../exh/metadata/metadata/EHentaiSearchMetadata.kt`; `app/src/main/java/exh/ui/metadata/`.
- Update checker: `app/src/main/java/exh/eh/` (`EHentaiUpdateWorker`, `EHentaiUpdateHelper`, `EHentaiUpdateNotifier`).
- Account config: `app/src/main/java/exh/uconfig/`; settings in `SettingsEhScreen` + `EhLoginActivity`.
- Favorites backup: `app/src/main/java/exh/favorites/` (`EhFavoritesBackupJob`, `ThrottleManager`).
- Tag catalogue: `app/src/main/java/exh/eh/tags/`.

## Status

Shipped on `main`, on-device verified on emulator-5554 (account push/remove and deep version-reconciliation are faithful ports verified by compile + non-account paths; live triggering needs an ExHentai login, user-side). Phase commits:

- Phase 1 (delegation core + metadata store + 4 free enhanced sources + URL import): `08d9f2bfa`, `24a51cb03`, `87abe5245`.
- Phase 2 (built-in EH/ExHentai, adult-sources toggle, ExHentai login, settings + uconfig): `41e85aac8`, `fc73b2adc`, `29d05b931`, `497b8d9f1`, `1f9ec4cab`, `692c98200`.
- Phase 3 (tag autocomplete, library tag search, metadata viewer): `2e3a1a892`, `15a3a4832`, `b6655ae08`.
- Phase 4 (HentaiFox / AsmHentai / Koharu wrappers + match-by-name fix): `0633f75a2`, `01e027dab`, `846e7e85d`.
- Phase 5a (favorited-gallery update checker): in the EXH commit range above.
- Phase 5b (one-way favorites backup): in the EXH commit range above.
- Settings + presentation polish: `f80cfc5b3`, `6a4422c23`, `90060670e`.
- Stock-EH suppression: `4a4c7c0bb`. Library-update exclusion of adult galleries: `1a0c8b35e`.

## Decisions & tradeoffs

Reikai ships a deliberately lighter slice of Komikku's subsystem. The gaps are tracked in [ROADMAP.md](../../../ROADMAP.md) ("Adult / EXH parity") as a candidate post-release-cut "widen toward Komikku parity" initiative:

- **One-way favorites backup, not two-way sync.** Komikku's `FavoritesSyncHelper` pulls the account's favorites into the library and mirrors removals both ways. Reikai shipped only the push direction (plus opt-in remote remove), because two-way sync is the one EXH feature that would mutate the library from a remote source; it stays parked. The `EHentaiUpdateHelper` notes the omitted `FavoriteEntryAlternative` upsert inline.
- **No bulk import entry points.** Komikku's `InterceptActivity` (share/open a gallery link) and `BatchAddScreen` (paste many URLs) are not ported; the add path is the trimmed `GalleryAdder` URL import. Parked.
- **No EXH library search engine.** The browse-side `namespace:tag` autocomplete is ported, but Komikku's richer library query language (wildcards, exclusions, exact match, namespace aliases in `exh/search`) is not. Parked.
- **`EXHMigrations` source-id remapper not needed.** Reikai registers a built-in `EHentai` for every stock-extension language id, so the old-id remap Komikku does on backup restore is unnecessary (see the ROADMAP parked note for the full reasoning).
- **Adult sources scoped to four enhanced wrappers.** Luscious, HentaiNexus, 3Hentai, and Hitomi.la were evaluated and parked (no stock extension to wrap, or too little structured metadata to justify one).

Provenance: the entire user-facing subsystem is Komikku's, faithfully ported; the Reikai-specific work is the re-typing onto Mihon and the `// RK` integration islands above. The [README](../../../README.md) credits it under "Adapted from Komikku" accordingly.
