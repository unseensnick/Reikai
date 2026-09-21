# Content layer: the sources surface (compiled-APK novel extensions)

> **Standing rules for every session working this surface.** The program rules bind here and are not restated: they live in [.claude/rules/content-layer.md](../../../.claude/rules/content-layer.md), which loads every session (write-once, typed capability slots over nullable soup, derive-or-do-not-own, the pin-once ladder, decline expiry). For the program context read [content-layer-architecture.md](content-layer-architecture.md) first, and [content-layer-browse-surface.md](content-layer-browse-surface.md) for the catalogue this plugs into. Six more bind on this surface specifically. **One seam:** every novel feature reaches a source only through `NovelSource`, and the differences between the three source kinds (LN plugin, tachiyomi-format APK, IReader APK) are typed capabilities, never a kind check in shared code. **One extension pipeline:** store, install, trust, signature and library-version checks exist once, in Mihon's pipeline, as `// RK` islands, and there is never a parallel loader. **Identity is the prefixed text id** (`tachiyomi:<id>`, `ireader:<id>`), never the raw number, because the two ecosystems share 26 ids and seven more collide with keiyoushi manga sources. **IReader's API stays pinned at 1.5.1** until an upgrade has been checked for the duplicate `eu.kanade.tachiyomi` classes its `main` branch has since added. **Extension output is untrusted input** ([security.md](../../../.claude/rules/security.md)): APK chapter text is sanitized like LN plugin output. **A service with a Reikai tracker belongs to the tracker:** where a source's own tracking hooks and a Reikai tracker write the same site, the tracker is the only writer and the source's tracking settings are hidden, never left inert.

## Goal

Load the two compiled-APK novel ecosystems beside the LNReader plugins: tsundoku's novel extensions (the NovelSourcery repository) and IReader's extensions. A novel from either reads, downloads, updates, migrates and tracks exactly like one from an LN plugin, and the Extensions tab installs, trusts, updates and removes them like manga extensions.

## Why

Requested in `unseensnick/Reikai#31`. Reikai's novels come only from LNReader plugins run in QuickJS, and both repositories ship compiled APKs instead, which the app ignores: the manga loader only accepts the `tachiyomi.extension` feature. Measured against the plugin repository Reikai points users at, tsundoku's repository adds 42 sites the plugins do not cover (Wattpad and the five Syosetu sites among them), and IReader's adds at most 75, fewer after renamed and dead entries.

Grounding also found a defect that exists today: adding the NovelSourcery repository under the manga extension stores follows its `index_v2` pointer and lists all 149 novel APKs as installable manga extensions, none of which ever loads.

## Approach

### Three source kinds, one seam

`NovelSource` is already the only door: browse, global search, details and chapter refresh, the reader's text load, downloads, the library update job and migration all reach a source through it and `NovelSourceManager`. `NovelSourceManager.get` and `getAll` run `ensureLoaded` before answering, so widening `ensureLoaded` to register the APK sources reaches every consumer that looks a source up there. Several consumers also warm the LN installer directly first; those calls stay and become redundant rather than wrong. Each APK kind gets one adapter implementing `NovelSource`; nothing above the seam learns there is more than one kind.

What the plugins express as raw LNReader JSON becomes a typed capability, because an APK source speaks a different contract:

- **Filters** are either the LN schema or a Mihon `FilterList`. The catalogue's filter slot renders `NovelSourceFilterSheet` for the first and Mihon's `SourceFilterDialog` for the second, which already takes only a `FilterList` and callbacks. IReader's filter model maps one to one onto Mihon's (`Note` to a header, `Text`, `Check` to a checkbox or a tri-state when it allows exclusion, `Select`, `Group`, `Sort`, and `Title`/`Author`/`Artist`/`Genre` to text, with `Title` carried by the search query), so one sheet serves all three kinds. This changes the browse record's premise: the filter split was "manga means `FilterList`, novels mean JSON", and it is now per source kind (amendment below).
- **Search takes filters.** `NovelSource.searchNovels` has none today, while both APK contracts search with them.
- **Settings** are the LN schema (`NovelSourceSettingsSheet`), an Android preference screen (`ConfigurableSource`, hosted by `SourcePreferencesScreen` through a resolver keyed on the novel source's text id, since the number alone can name a manga source or two novel sources), or absent (IReader sources have no settings screen; their per-book commands are below).
- **Images:** covers and in-chapter images use the source's own client and headers, as `MangaCoverFetcher` does for manga. Today novel covers get only the device user agent and `Referer = site`. `inlineChapterImages` already accepts a client.
- **Listings:** an IReader source declares one or two listings, mapped onto Popular and Latest; `supportsLatest` is true only when a distinct Latest listing exists.

### One extension pipeline, three kinds

Mihon's `ExtensionLoader` learns the other two manifest features rather than gaining a sibling. Each kind has its own feature, meta-data keys and library gate:

| Kind | Feature | Class key | Library gate |
|---|---|---|---|
| Manga | `tachiyomi.extension` | `tachiyomi.extension.class` | 1.4 or 1.6 |
| tachiyomi-format novel | `tachiyomi.novelextension` | `tachiyomi.novelextension.class` (also `.nsfw`, `.novel`) | 1.4 or 1.6 |
| IReader | `ireader` | `source.class` (also `source.nsfw`) | major 2 only |

The `tachiyomix.*` keys are shared by the first two. An installed extension carries its kind, read from its feature, and `ExtensionManager` files novel kinds into maps of their own as they arrive, so its manga maps stay manga only and nothing that reads them (source registration and stub rows, the lists, the update count, backups) ever sees a novel APK. The install receiver and private installs accept every kind through the same feature check. Trust is unchanged: a store's signing key covers its extensions, and a keyless store's extensions are trusted per version by the user, which is where every IReader extension lands because its index carries no key.

Class loading is unchanged too (`DelegateLastClassLoaderCompat` over the app's loader), and for tsundoku-format APKs that is sufficient. They are keiyoushi-derived: their host-provided library bundle is identical to keiyoushi's manga bundle, and they compile against `tsundoku-otaku/extensions-lib` v1.6.0-3, which is tachiyomix 1.6 plus two interfaces. Every member name in that library exists in Reikai's source-api and `core/common` except `SourceTracker` and `RateLimited`, which the whole `madaranovel` theme, NovelUpdates and the `syosetu` theme implement, so those classes fail to load until source-api has them.

### Identity

Novels store their source as text. An APK source's number becomes `tachiyomi:<id>` or `ireader:<id>`, taken from the running source object (IReader's generated `Extension` class overrides `id` with the build-assigned value, which matches its index). Bare numbers are ruled out: 26 sources carry the identical id in both repositories (FreeWebNovel, NovelFull, Ranobes, LNMTL and more). None of the 279 LN plugin ids is numeric or contains a colon, and nothing parses a novel source id as a number. Download folders are named after the id through `DiskUtil.buildValidFilename`, which turns the colon into `_`.

Seven sources also share a number with a keiyoushi manga source (DragonTea, NovelCool and WoopRead from tsundoku; MangaTX, Toonily, ZinManga and Wuxiaworld from IReader). A tsundoku-format extension names its own settings file `source_<number>`, so a manga and a novel extension for the same site would share one file; no host choice can prevent that. IReader's preference bridge keys on the package name instead, as IReader itself does, so its 26 twins never share settings with their tsundoku copies.

### The tachiyomi-format adapter

Each APK ships its own `eu.kanade.tachiyomi.source.NovelSource` interface, a standalone copy the host cannot reference by type. The host therefore declares `fetchPageText(page)` on source-api's `Source` with a throwing default, and the extension's own method answers the interface call across class loaders. tsundoku's host and IReader's compatibility layer both load these APKs this way.

The adapter wraps a `CatalogueSource`: popular and search go to `getPopularManga` and `getSearchManga`, details and chapters to `getMangaUpdate`, and chapter text to `getPageList` then `fetchPageText`. Page URLs are the extension's to decide (a Madara theme returns an absolute URL, NovelUpdates the chapter URL), so the adapter always asks `getPageList`, and only builds `Page(0, url)` itself when that throws `UnsupportedOperationException`. Status maps through `NovelStatusCode`, whose values line up with `SManga`'s; the upload date becomes the release time the novel side already parses.

### The IReader runtime

IReader extensions expect the host to provide IReader's own API, published as `io.github.ireaderorg:source-api-android:1.5.1`, with Ktor 3.3.2, IReader's Ksoup build (package `com.fleeksoft.ksoup`), kotlinx-datetime and Kermit. Reikai depends on that artifact pinned. Version 1.5.1 contains only `ireader.core.*`; IReader's `main` has since added copies of `eu.kanade.tachiyomi.*` classes that would clash with Reikai's own source-api at build time. Koin arrives transitively but only `CloudflareBypassModule` uses it, so it is excluded, which keeps the architecture rule against Koin intact. The date library is binary-safe: 0.7.1's `Instant` and `Clock` are type aliases for the `kotlin.time` types, and 0.8.0 removed nothing else.

Every source is constructed from `Dependencies(HttpClientsInterface, PreferenceStore)`, and both are interfaces, so Reikai implements them itself. The HTTP clients are Ktor over Reikai's own OkHttp client through Ktor's OkHttp engine, so IReader sources inherit FlareSolverr, the shared cookie jar and the app's user agent. `BrowserEngine`, `SSLConfiguration` and `CookieSynchronizer` are final classes the artifact supplies; the browser engine is wired to a WebView fetch. The preference store bridges to `PreferenceStore`, prefixed by package.

A chapter arrives as a list of pages, a `Text` per paragraph and `ImageUrl` for pictures; the adapter assembles them into the chapter HTML the readers take. Details and chapters are requested with the source's default commands, and a source that offers commands gets the per-book commands sheet on the novel details screen (`Note`, `Text`, `Select`, `Toggle`, `Range`) plus the WebView fetch commands, which hand the source a page the user loaded in the in-app browser, the escape hatch for a Cloudflare-blocked translator site.

R8 has to keep what extensions call by name: `ireader.core`, `io.ktor`, `kotlinx.io`, `com.fleeksoft.ksoup`, `kotlinx.datetime` and Kermit join the existing keep list. The 36 new artifacts total 4.9 MB compressed, estimated at 3 to 5 MB on each APK against today's 31.7 MB arm64 build. There is no ceiling (owner); the minified build measures it and the number is recorded here.

### Source-side tracking

`SourceTracker` is a tsundoku-library interface through which an extension syncs reading to its own site. One dispatcher kernel runs it for both content types, because once the interface exists in source-api a manga extension can implement it too. It debounces chapter events per entry for three seconds, re-reads the chapters fresh when it fires, resolves category names (excluding the uncategorized one), checks for the interface by name and calls it reflectively across class loaders, as tsundoku does, and isolates each call's failure. It is called from the user-initiated choke points: `SetReadStatus` and `SetNovelReadStatus` for reads, and the favorite paths (`UpdateNovel.awaitUpdateFavorite` for novels, whose callers are every user add and remove). Migration triggers it only behind a preference, as tsundoku's `source_tracker_run_on_migration` does. Restore and merge-undo never do.

`RateLimited` is honoured for novels only, under the standing ruling that download pacing is novel-only: `minimumDelayMillis` floors `NovelDownloadPacing.floorFor`, and `recommendedDelayMillis` seeds the per-source delay the Pacing screen shows.

### NovelUpdates: the tracker owns the site

Reikai's NovelUpdates tracker and the NovelUpdates extension write the same site, and the extension's version is destructive: its notes sync reads the note and tags with a regex that stops at the first quote and writes the truncated text back, and on a failed read writes its progress line over the whole note. The tracker already decodes the note as JSON and refuses to write what it cannot parse. So the tracker does all NovelUpdates tracking:

- It gains **release marking**: the series' release list comes from `nd_getchapters` keyed by the same post id both sides use, releases are numbered with `ChapterRecognition.parseChapterNumber`, and the release is marked only on an exact single match. Otherwise the mark is skipped and the notes line still updates.
- It gains **unread push**, opt-in: marking chapters unread moves the release mark back. Nothing pushes to a novel tracker on unread today.
- It gains **never move backwards**, on by default: the mark only advances unless unread push is on.
- It **auto-binds** novels added from the NovelUpdates extension (auto-binding below), so those novels track with no setup, as they would with the extension.
- The dispatcher never calls the extension's tracking hooks, and `SourcePreferencesScreen` hides the six settings the tracker took over (`pref_enable_tracking`, `pref_track_last_read`, `pref_track_notes`, `pref_track_unread`, `pref_protect_highest`, `pref_reset_cache_toggle`) after the extension builds its screen. The key list lives in the same table that gives the tracker ownership of the site. "Return full HTML" stays, as a reading option.

The other extensions with tracking hooks (the Madara novel theme, Konkon, KuuPress, WtrLab) keep going through the dispatcher; Reikai has no tracker for their sites.

### Auto-binding trackers, for both content types

Manga auto-binds trackers today through `EnhancedTracker`; novels bind nothing automatically. The interface bundles two ideas: "this tracker is the source's own server" (a settings row only when that source is installed, a no-credentials login, no refresh button, hidden for other sources, excluded from Fill from tracker) and "this tracker auto-binds entries from its sources" (bind on add, progress sync on bind, match in the tracking dialog, re-point on migration). NovelUpdates wants only the second; implementing the interface would hide it from every other source and turn its real login into a no-op.

So `EnhancedTracker` stays untouched and a Reikai-owned auto-bind capability carries the second idea, written once:

- **Bind on add.** One shared routine takes every logged-in tracker whose capability accepts the entry's source, tries a match, binds it through the type's own write, and carries on past a failing tracker. `AddTracks.bindEnhancedTrackers` keeps its name and callers and hands its body to the routine (`// RK`); the three manga enhanced trackers reach it through an adapter, so their files stay untouched. Both library adders call it.
- **Progress sync on bind.** The routine decides when; the sync walks each type's own chapter table, manga through Mihon's `SyncChapterProgressWithTrack` and novels through its equivalent, pinned by one conformance test over both. The direction is the tracker's: a server tracker pulls its progress down, NovelUpdates pushes the local progress up, as binding it by hand does.
- **Match in the dialog.** `EntryTrackInfoDialog` loses its two manga-only gates and asks the capability for both types.
- **Migration.** Re-pointing a binding becomes one kernel both migrate use cases call, the manga call site as a `// RK` island; novel migration today copies bindings unchanged.
- The server-tracker behaviours become capabilities the shared screens read for both types; only a tracker that is a source's own server sets them.

## Sequenced steps

Each step is verified on the emulator before the next starts; the checks named are the ones that would catch it being wrong.

- **A1, the contract.** `fetchPageText` on `Source`, `SourceTracker` and `RateLimited` in source-api. Verify: compile, and the existing manga extensions still load.
- **A2, the tachiyomi-format kind in the pipeline.** Feature, keys and gate in `ExtensionLoader`, the kind on `Extension.Installed`, novel kinds kept in their own maps in `ExtensionManager`. Verify: install a NovelSourcery APK; it loads as a novel extension and no manga source or stub appears.
- **A3a, stores know what they serve.** Each index entry's field 8000 (or, in an old-format index, the novel package namespace) sets the kind of an available extension, and `ExtensionManager` keeps novel entries out of the manga list. Verify: with the NovelSourcery repository added, none of its 149 extensions reach the manga list.
- **A3b, the Repos screen.** A Reikai-owned screen shaped like the download queue: one card per repository with its format, content type and extension count, a details sheet, one Add that detects whether the address is an extension store or an LN plugin repository, and a refresh covering both. Mihon's five screen files are deleted and manifested. Verify: add, refresh and remove each kind on device, and the deep link.
- **A4, typed novel capabilities.** Filters, settings, search filters, image client and headers, listings. Verify: LN plugins behave exactly as before, device-walked on browse, search, filters, settings and covers.
- **A5, registry and the Extensions tab.** Adapters registered through `ensureLoaded`; novel APK rows using the manga APK actions and Not-loaded reasons.
- **B1, the tachiyomi-format adapter.** Verify: one conformance test over this adapter and `LnPluginSource` against the `NovelSource` contract, then a Madara-theme novel browsed, added, read, downloaded and updated on device.
- **B2, the tracking dispatcher**, both content types. Verify: unit tests over a fake tracking source, each mutation-checked.
- **B3, rate-limit hints** into novel pacing.
- **B4, auto-binding trackers** for both content types: the routine, the capability, the dialog, migration. Verify: manga auto-binding unchanged on device against a manga enhanced tracker, and the conformance test over both sync walks.
- **B5, NovelUpdates tracker ownership.** Release marking, unread push, never-backwards, auto-bind acceptance, the hidden extension settings. Verify: tests, then one live check on the Fold with the owner's OK on the day, on a novel the owner does not care about, unmarked afterwards.
- **C1, the IReader dependency.** Pinned artifact without Koin, R8 keeps. Verify: a minified nightly builds and launches; the APK size is measured and recorded here.
- **C2, IReader host services.** HTTP clients, preference bridge, browser engine.
- **C3, the IReader kind and adapter.** Loader construction through `(Dependencies)`, the IReader index parser (a flat array with no `repo.json` and icons named after the APK), listings, filter translation, pages to HTML. Verify: FreeWebNovel browsed, added and read on device, beside its tsundoku twin.
- **C4, commands.** The per-book commands sheet and the WebView fetch commands.
- **D, the behaviour inventory and records.** Walk what tsundoku's and IReader's hosts do with these APKs and mark each behaviour present, deliberately dropped or missing; update the FAQ in `docs/about.md`, `feature-ports.md` and the CHANGELOG; remove the roadmap line.

## Key files

- `source-api/.../source/Source.kt` (`fetchPageText`), and the new `SourceTracker.kt` and `RateLimited.kt` beside it.
- `app/.../extension/util/ExtensionLoader.kt` (`isPackageAnExtension`, `loadExtension`), `domain/.../extension/model/Extension.kt`, `app/.../source/AndroidSourceManager.kt`, `app/.../extension/util/ExtensionInstallReceiver.kt`.
- `data/.../extension/service/ExtensionStoreService.kt` (`fetch`), `data/.../extension/model/NetworkExtensionStore.kt`, `app/.../domain/extension/interactor/GetExtensionsByType.kt`.
- `reikai/novel/source/NovelSource.kt` and `NovelSourceManager.kt` (`ensureLoaded`); the adapters beside them.
- `reikai/presentation/browse/catalogue/EntryCatalogueScreen.kt` (the filter and settings slots), `app/.../ui/browse/source/browse/SourceFilterDialog.kt`, `app/.../ui/browse/extension/details/SourcePreferencesScreen.kt` (`populateScreen`).
- `reikai/presentation/browse/extension/ReikaiExtensionsTab.kt`, `ExtensionsProvider.kt`.
- `reikai/domain/novel/interactor/SetNovelReadStatus.kt`, `UpdateNovel.kt` (`awaitUpdateFavorite`), `app/.../domain/chapter/interactor/SetReadStatus.kt`, `reikai/novel/download/NovelDownloadPacing.kt` (`floorFor`).
- `app/.../domain/track/interactor/AddTracks.kt` (`bindEnhancedTrackers`), `SyncChapterProgressWithTrack.kt`, `reikai/presentation/track/EntryTrackInfoDialog.kt` (`registerEnhancedTracking`), `mihon/domain/migration/usecases/MigrateMangaUseCase.kt`, `reikai/domain/novel/interactor/MigrateNovelUseCase.kt`.
- `app/.../data/track/novelupdates/` (`NovelUpdates.push`, `NovelUpdatesApi`).
- `app/proguard-rules.pro`, `gradle/libs.versions.toml`.

## Status

In progress. A1 (the contract) has landed: `fetchPageText`, `SourceTracker` and `RateLimited` in source-api, pinned by `SourceApiContractTest`, with installed manga extensions (library 1.4 and 1.6) loading and browsing as before. A2 (the novel kind in the pipeline) has landed: BoxNovel, a Madara-theme APK implementing `SourceTracker`, loads as a novel extension once its store key trusts it, and before that sits as untrusted in the novel maps; neither state reaches a manga list, source or stub row. A3a (stores know what they serve) has landed: the NovelSourcery repository's 149 extensions no longer list as manga. A3b (the Repos screen) has landed, reading the listings `ExtensionManager` and `LnRepoRegistries` already keep, so opening it downloads nothing when the Extensions tab has fetched. Nothing displays or registers the novel maps until A5. Scouted and grounded 2026-09-21 against `refs/tsundoku` `b04f9a4d3`, `refs/IReader` `de8cf8b31` (API read at `548bd934f`, the 1.5.1 release), `NovelSourcery/extensions-source` `main`, `tsundoku-otaku/extensions-lib` `v1.6.0-3` and `IReaderorg/IReader-extensions` `master`. Revert point: tag `checkpoint-apk-novel-extensions-pre` on `e495ba3df`. This work holds the 0.4.0 cut.

## Decisions & tradeoffs

- **Both ecosystems, full version, in 0.4.0** (owner, 2026-09-21). IReader was offered as a separate item because it needs a second runtime; the owner ruled it in.
- **Ids are prefixed by format, not by app** (owner, 2026-09-21): `tachiyomi:` names what the extension is, a manifest format any repository can publish, where `tsundoku:` would mislabel a repository that is not tsundoku.
- **No APK size ceiling** (owner, 2026-09-21): measured and recorded rather than capped. Trimming the kept code to what extensions call today was declined, because an extension update calling something new would crash.
- **Tracking is verified by tests plus one live check** (owner, 2026-09-21), on the owner's account, with explicit consent on the day.
- **The NovelUpdates tracker owns the site** (owner, 2026-09-21), taking the extension's tracking features, rather than letting both write (declined: double writes, and the extension's notes sync is destructive) or skipping the extension only for bound novels (declined in favour of full ownership plus auto-binding).
- **Hide, never leave inert** (owner, 2026-09-21): the taken-over settings are removed from the extension's screen by key. If an extension update renames a key, that setting reappears doing nothing until the table is updated; nothing breaks. Asking NovelSourcery for a host flag that hides them at the source is open, and is the owner's call since it is an outward-facing request.
- **Auto-binding through a Reikai-owned capability** (owner, 2026-09-21), not by splitting `EnhancedTracker`, an upstream interface read at seven upstream sites, and not by special-casing NovelUpdates, which would leave novels without manga's behaviour.
- **Amendments to the rules file** (owner, 2026-09-21): a Sources row in the depth table, and the Browse row's filter split restated per source kind. The browse ruling "the filter dispatch stays split on mechanism, a typed `FilterList` against a plugin JSON schema" keeps its mechanism and loses its premise that the mechanism follows the content type.
- **Not taken from tsundoku:** its JS plugin host and its source list (the 2026-08-02 verdict in `feature-ports.md` stands), and novels-as-manga rows, which is how its own host stores these sources.
- **The groundwork is the extension library, not a host commit.** `feature-ports.md` points at tsundoku's `b1edfc8f4` as groundwork for this item; that commit only deprecates two host-side methods. The contract the APKs compile against is `tsundoku-otaku/extensions-lib`.
- **`isNovelSource` stays off `Source`.** Every NovelSourcery APK's own `NovelSource` copy gives it a default body, so a host default beside it would leave an extension that does not override it with two defaults, which fails at the call. The kind comes from the manifest instead. `fetchPageText` is safe because that copy leaves it abstract.
- **Novel kinds are split off in `ExtensionManager`, not skipped in `AndroidSourceManager`.** Sixteen readers of the manga maps would otherwise have listed a novel APK as manga (source registration and stub rows, the Extensions lists, the update badge and its obsolete flag, backups, the crash log), so filtering at the one place results arrive covers all of them and any reader added later. An APK declaring both features loads as manga, so nothing that loads today changes (`Extension.Kind.fromFeatures`, pinned by `ExtensionKindTest`).
- **An available extension's kind is per entry, never per store.** NovelSourcery's index carries field 8000 on each of its 149 extensions and nothing at store level, as tsundoku reads it too, so a store may mix both kinds and its content types are derived from what it lists rather than stored in a column. The novel package namespace answers for an old-format index, which has no field 8000 (`Extension.Kind.fromIndex`, pinned by `StoreIndexKindTest`).
- **The LN plugin registries are fetched once and shared** (`LnRepoRegistries`): on first use, when a repo is added, and on Refresh. The plugin list used to download every registry again on each return to the tab and after each install or uninstall; Available and Updates are now derived from the cached registries and the installed plugins' records. The update check and the post-restore trust check keep fetching on their own, since both need the registry as it is now. The manga side's own repeat fetches (Mihon's refresh downloading a v2 index twice, the stored `github.com/…/raw/` redirect) are left as upstream has them (owner).
- **The Repos screen follows the download queue** (owner, 2026-09-21): one card list with the type badge shown only when both types are present, a details sheet, one Add that detects the repository's kind (owner-approved), and Mihon's five screen files deleted and manifested (owner-approved), while the store data layer stays Mihon's and untouched.
- **The two interfaces carry their own R8 keep** in `source-api/consumer-proguard.pro`. The existing rule keeps members only on types extending `Source`, and neither does; a minified build without the keep was dexdumped with both interfaces empty. `DefaultImpls` needs no keep: `Source$DefaultImpls` is already stripped empty in minified builds while extensions keep working, because extensions built with current Kotlin (NovelSourcery sets no `jvm-default` flag) call the interface's real default methods.
- **Known limits carried forward:** an extension that uses NovelSourcery's `lib/synchrony` calls a QuickJS binding Reikai does not ship (Reikai's host uses dokar3's `quickjs-kt`), the same gap keiyoushi manga extensions already have. Twelve IReader index entries are built on library 1, which IReader's own loader refuses; they show as Not loaded here too.
