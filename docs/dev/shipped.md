# Shipped

Terse done-log of what has landed, grouped by area. This is a developer record: it may name specific sources (unlike the forward-facing [ROADMAP.md](../../ROADMAP.md), which stays generic). User-facing release notes live in [CHANGELOG.md](../../CHANGELOG.md); per-feature detail lives in [plans/](plans/).

Items marked `(feat/exh-parity)` are on the held adult-parity branch, not yet on `main`.

## Foundation, identity & release
- Mihon base + Reikai identity (`eu.kanade.tachiyomi` + `.y2k`), source-api related-manga contract (P0-P1). See [rebase-overview.md](plans/rebase-overview.md).
- De-Mihon brand pass: logo, trimmed About links, donation removed, trackers rebranded, repo meta + JDK 21. Icon sources in `art/icon/`.
- README header logo + animated showcase WebP + reproduction kit. See [readme-showcase.md](readme-showcase.md).
- Signed release pipeline: AGP-native signing, `release.yml` / `preview.yml`, in-app updater re-pointed at Reikai repos (`a4ee2c401`, `1f7aecac4`, `09d04cd0b`, `1c68490da`).
- Releases cut + tagged `v0.1.0`-`v0.1.5` (0.1.3 = extension re-trust + migrate-from-update-errors; 0.1.4 = Cloudflare-bypass JSON/Byparr fix; 0.1.5 = one-tap merge coalescing). Stale inherited Yokai `v*` tags pruned; see the `stale-yokai-release-tags` memory.
- Preview pipeline prune fix: prune by build number, not identical `createdAt`, so previews publish again (`e1960e06e`).
- Commit-standard enforcement: `commit-msg` hook (`.githooks/commit-msg`), explicit `owner/repo#N` refs allowed (`7f4649d65`).
- Minified-build startup-crash fix: `reikai.*` added to the proguard keep list.
- Duplication cleanup, 3 tiers, no behavior change (`6c27c5923`, `85ff3326d`, `f783979b5`).
- Mihon upstream syncs, caught up to `refs/mihon` `a82ccea6f` (Voyager 1.x->2.x, Gradle 9.6.1); ledger in the `upstream-sync` memory.
- App-wide hardening pass (feat/exh-parity, `c8b5a46b6`, `78c4b478f`, `18a6d2a33`): source-parsing null guards, LN host / download-queue / recommendation edge cases, per-category resolve-once, redacted verbose logging.

## Manga
- Library screen carry: single-list + hopper, dynamic grouping, filter/sort, category sort order, opt-in update-errors screen (P2). See [library-screen-carry.md](plans/library-screen-carry.md).
- Manga details parity: cover-accent backdrop, two-finger range select, swipe-to-refresh, unified Display sheet (P3). See [manga-details-parity.md](plans/manga-details-parity.md).
- Pref-based multi-source merge engine + preferred-source ranking + tracker-link mirroring + FlareSolverr (P4). See [manga-merge-engine.md](plans/manga-merge-engine.md).
- Cloudflare bypass robustness (0.1.4): unwrap a solver's JSON-viewer to raw JSON, support sessionless solvers. See [flaresolverr.md](../flaresolverr.md).
- Merge coalescing fix (0.1.5): one tap merges every source of a series (manga + novels).
- Recommendations / related carousel: five streams, taste rerank, tracker cross-recs, See-all browse (P6). See [recommendations.md](plans/recommendations.md).
- Reader tweaks: configurable bottom bar, chapters sheet, cover tint, mark-read-on-skip, resume/preload (P7).
- Merge-aware reader: read a merged manga through all its sources (`d30cce03d`, `4bd3ca823`, `5b9f6d778`). See [merge-aware-manga-reader.md](plans/merge-aware-manga-reader.md).
- Category bulk-delete with deferred-undo (`9a320598e`).
- Downloads resume reliably after a kill or interruption (feat/exh-parity, `37b7bf22c`; Mihon sync `e3740fc3d`): in-flight downloads re-queue instead of being dropped.

## Library shell (manga + novels)
- Tabbed shell hosting a Manga tab and a Novels tab + repo / install / browse unification (P8/P9). See [library-tabbed-shell.md](plans/library-tabbed-shell.md).
- Remove every source of a merged series in one delete: an "All N grouped sources" opt-in on the library Remove dialog, for manga + novels (`1c7621dec`).

## Light novels (P5 vertical)
- Headless QuickJS plugin host for background-capable novel sources. See [novel-plugin-host.md](plans/novel-plugin-host.md), handbook [ln-plugin-host.md](ln-plugin-host.md).
- Browse + global search + installable LN plugins. See [novel-browse.md](plans/novel-browse.md).
- Details screen at manga parity. See [novel-details.md](plans/novel-details.md).
- Reader (WebView text canvas + Compose chrome). See [novel-reader.md](plans/novel-reader.md).
- Reader engine extras round 2: TTS with background playback + lock-screen media notification, bionic reading, remove-extra-spacing, tap-edges-to-scroll, swipe-between-chapters, auto-scroll, vertical progress seekbar; General/Display/TTS settings tabs (`6bd65d9be`, `4271172fc`, `9cde12b5f`, `705fa80bf`).
- Reader chrome + chapter-list parity (round 3): jump-to-chapter sheet, orientation picker, top-bar bookmark + WebView, seekbar percent labels, translucent chrome; WebView/Share open the novel's page (`23ed2a2e4`, `d8faad579`).
- Categories + hopper + tab-aware Display sheet. See [novel-categories.md](plans/novel-categories.md).
- Background update job (`NovelUpdateJob`). See [novel-update-job.md](plans/novel-update-job.md).
- Home-screen widget for manga + novel updates: sectioned Glance widget (`b2e4d1cb8`). See [unified-updates.md](plans/unified-updates.md).
- Cross-source merge + dynamic grouping. See [novel-merge.md](plans/novel-merge.md).
- Tracking on AniList / MyAnimeList / MangaUpdates / Kitsu, group-aware (`7c56e07eb`). See [novel-tracking.md](plans/novel-tracking.md).
- Backup proto + installed-sources backup (`3c52d4c97`). See [novel-backup.md](plans/novel-backup.md).
- Restored-plugin security gate (feat/exh-parity, `38298260a`): a crafted backup could inject plugin URLs the QuickJS host would auto-load and run; restored URLs are now revalidated against the added repos before loading (fail-closed if a repo is unreachable).

## Novel parity backlog
Per-item SHAs in [novel-parity-backlog.md](plans/novel-parity-backlog.md) unless noted.
- Core carry: History tab, source migration, per-novel notes, novels in Stats, orientation lock, keep-screen-on, incognito, downloaded-only, mark-read-on-skip, download retry + Wi-Fi-only, global-search add, collapse-at-bottom grouping, category filter, bulk-download, Last-read sort, source pinning + global-search chips.
- Surgical novel writes: favorite / cover / chapter-flag / orientation route through `UpdateNovel` / `SetNovelChapterFlags` / `SetNovelViewerFlags`.
- Download-queue reorder + sort (persisted to `NovelDownloadStore`); unified queue Sort hits manga + novels.
- Batch / library migration: one unified migration screen for 1..N novels, auto-search + accept/override, Copy / Migrate with flags.
- Migration redesign: covers + chapter-count regression signal, source-selection pre-step, comparison rows, cover-grid override picker (`bf5bd4f8f`, `94f7ef09d`, `8cb547a71`, `b66e3efd0`, `0ce7c95c1`). See [novel-migration-redesign.md](plans/novel-migration-redesign.md).
- Merge-aware migration (manga + novel): migrating a merged entry keeps the merge; carries tracker links (`ae0dbc191`).
- Migrate-merge source picker (manga + novel): choose which grouped source(s) to migrate (`dd0173b22`).
- Novels in Browse -> Migration: All / Manga / Novels chip, `seenNovelSources` cache (`cf4f65269`).
- Download settings parity: keep-last-N-read, don't-delete-bookmarked, exclude-categories, download-ahead.
- Per-title novel update notifications: one grouped notification per novel, `SHORTCUT_NOVEL` deep-link.
- Novel tracking private listing: `private` column (migration 25), "Track privately" for capable trackers (`62070e5e7`).
- Reader brightness + colour filter: novel-specific brightness + R/G/B/opacity/blend overlay (`cf7941723`).
- Migration carries cover + notes: `NovelMigrationFlag` COVER + NOTES (`2593e131c`).
- Restore skip-if-newer: `version` + `is_syncing` columns + trigger (migration 26), `BackupNovel` proto field (`6be6efe1c`).
- Round 2 sweep: novel default-category (`db116e592`), add-to-library on history rows (`658be0feb`), source enable/disable (`bf538dabf`), chapter "downloaded" filter (`143abf9cf`), update-error tracking + per-category manual update (`f28259d00`).

## Adult / EXH subsystem
Ported from `refs/komikku`, re-typed onto Mihon's models. Phases 1-4 + 5a + 5b are on `main`; the browse-parity and later items are on `feat/exh-parity` (held). See [adult-sources.md](../adult-sources.md).
- Phase 1: delegation core (`EnhancedHttpSource` / `DelegatedHttpSource`) + gallery-metadata store (`search_metadata` / `search_tags` / `search_titles`, migration 23) + 4 free enhanced sources (nHentai, Pururin, 8Muses, LANraragi) + URL import (`08d9f2bfa`, `24a51cb03`, `87abe5245`).
- Phase 2: built-in E-Hentai / ExHentai source (browse + read, gallery filters, version chapters), "Enable adult sources" toggle, ExHentai WebView login, EH settings + uconfig sync (`41e85aac8`, `fc73b2adc`, `29d05b931`, `497b8d9f1`, `1f9ec4cab`, `692c98200`).
- Phase 3: E-Hentai tag autocomplete, library search by gallery tags, Compose-native metadata viewer (`2e3a1a892`, `15a3a4832`, `b6655ae08`).
- Phase 4: three net-new enhanced wrappers (HentaiFox, AsmHentai, Koharu/SchaleNetwork) + delegated-source match-by-name fix (`0633f75a2`, `01e027dab`, `846e7e85d`). Scoped down from six (Luscious/HentaiNexus/3Hentai parked).
- Phase 5a: E-Hentai favorited-gallery update checker (WorkManager job + disk-backed `EHentaiUpdateHelper`), "Gallery update checker" settings. Version-reconciliation is a faithful port, not yet live-triggered.
- Phase 5b (scoped): E-Hentai favorites account backup (one-way push + opt-in remote remove), not full two-way sync (`EhFavoritesBackupJob`, `// RK` island in `MangaScreenModel`). Account paths need a live login to verify; non-account paths verified.
- EXH settings + presentation polish: EH promoted to its own Settings category with the `EhAssets.EhLogo` icon (`f80cfc5b3`), source rows show the logo (`6a4422c23`), and Incognito / Language / Front-page categories / updater-stats surfaced in `SettingsEhScreen` (`90060670e`).
- Hide the stock E-Hentai extension while built-in EH is on (`4a4c7c0bb`): `BlacklistedSources` + reactive `AndroidSourceManager` / `ExtensionManager` filtering. Defensive (that extension isn't in Keiyoushi); regression-verified.
- Browse parity (feat/exh-parity): built-in EH / ExHentai browse pages all the way through + rich rows (cover, uploader, rating, category badge, language, page count, date) (`247e1de01`, `888305ab1`, `1c7888b07`). See [adult-browse-parity.md](plans/adult-browse-parity.md).
- Rich gallery metadata on details (feat/exh-parity): namespaced tappable tag chips + a per-source gallery-info card (EH gets a rich card); net-new `NamespaceTags` + `GalleryInfoBox`. MangaDex branch deferred to the MangaDex initiative.
- Library tag-search engine (feat/exh-parity): structured `namespace:tag` query language (aliases, `*`/`?` wildcards, `-` exclusion, `$` exact, quotes), matched in memory; `Text.asRegex` added. See [library-tag-search.md](plans/library-tag-search.md).
- Built-in nhentai.net source (feat/exh-parity, `920b766b1`, `b535a3093`): standalone `NHentaiNet` HttpSource against nhentai's v2 JSON API (no extension needed), behind the adult-sources gate, excluded from the update sweep by fixed id.
- Built-in pururin.me source (feat/exh-parity, `c25b07150`, `92dd8f390`): standalone Pururin HttpSource (no extension needed) with its own logo, behind the adult-sources gate.
- Rate-limit the built-in adult sources (feat/exh-parity, `ed2f09e6a`): throttle requests to avoid bans.
- Gallery URL import + batch-add (feat/exh-parity, `ed59dd780`, `1a4592847`, `f0924164f`): add a gallery by opening its link (InterceptActivity) or paste many URLs (BatchAdd); 8Muses supported; imports get their title and cover.
- Skip adult galleries in the library update sweep (`1a0c8b35e`): EH / ExH / Pururin / nHentai default to `ALWAYS_UPDATE`; `LIBRARY_UPDATE_EXCLUDED_SOURCES` + a `// RK` `filterNot`, nHentai derived at runtime.
- Gallery page previews (feat/exh-parity): details thumbnail grid (`954644d06`) + full-screen paginated browser with go-to-page slider (`0b0e31db0`); disk-cached via a Coil fetcher/keyer; Appearance "Page preview rows" slider.
- EH gallery-update notification (feat/exh-parity, `6dbeed5bb`): progress notification matches the library updater + a tap-to-view error-log notification (`ID_EHENTAI_ERROR`, `writeErrorFile`).
- Built-in adult source library badges (feat/exh-parity, `7503e5ce5`): nhentai.net + Pururin show their own logo on the cover source badge.
- Merged-group refresh (feat/exh-parity, `07c1d3d1a`): a details Refresh now fetches every grouped source through its own path, not just the primary.
- E-Hentai image-quality options realigned (feat/exh-parity, `e99c0fcbb`): the picker tiers were stale versus the site's current `xr` set, so most options silently no-op'd on the account profile; now Auto / 800 / 1280 / 1920 / 2560 each apply. Verified on-device.
- Keep every gallery source's chapters in a merged group (feat/exh-parity, `a255588cf`): the cross-source `ChapterAggregation` deduped the "All" list by chapter number and dropped one source when two gallery sources both number their primary chapter 1; gallery / metadata sources now bypass that dedup. Serial-manga merges unchanged. Unit-tested + on-device verified.

## Unified surfaces
- Unified Updates tab: manga + novel interleaved, filters, by-category, group-by-series. See [unified-updates.md](plans/unified-updates.md).
- Unified reader: shared Compose chrome over the View-based manga reader (Option F); Phase 1 shipped. See [unified-reader.md](plans/unified-reader.md).
- History tab consolidated (manga + novel behind a content chip).
