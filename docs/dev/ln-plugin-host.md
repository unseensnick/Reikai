# Light-novel plugin host: cold-start handbook

This is the navigation map for the light-novel (LN) plugin host. A developer picking the LN
vertical back up after a break should be able to find the code, build it, run the host integration
test, and add a new shim from this doc alone.

This is a handbook, not a decision record. The "why headless QuickJS instead of a WebView" rationale,
the alternatives that were rejected, and the legal/maintenance posture live in
[plans/novel-plugin-host.md](plans/novel-plugin-host.md). Read that for the reasoning; read this for
the layout.

## Where things stand

The LN host ships on the `design/mihon-rebase` branch (the branch that becomes `main` when the rebase
lands). The vertical is complete and on-device verified: installing lnreader plugins from a repo,
browsing/searching a source, saving novels to the library, reading chapters, downloading, multi-source
merge, tracking, and backup are all wired. The host runs lnreader plugins unmodified in a headless
QuickJS engine: no WebView and no Activity, so a source works the same on a screen or in a background
worker (library updates, downloads). Plugin source code is not bundled in the APK; the user pastes a
registry URL at runtime.

## Architecture

### The engine

`LnPluginHost` (`reikai.novel.host.LnPluginHost`) owns one QuickJS engine from
`com.dokar.quickjs` (`io.github.dokar3:quickjs-kt`, see `gradle/libs.versions.toml` `quickjs-kt`).
QuickJS is not thread-safe, so the host confines every native call to a single-thread executor
(`Executors.newSingleThreadExecutor`, thread name `LnPluginHost`) turned into a coroutine dispatcher,
and serializes all access through a `kotlinx.coroutines.sync.Mutex`. The engine is created lazily on
first use in `engine()`: it binds the host functions, seeds browser globals, evaluates the vendor
bundles and `headless.js`, then caches the `QuickJs` instance.

Each public method (`loadPlugin`, `popularNovels`, `searchNovels`, `parseNovel`, `parsePage`,
`parseChapter`, `resolveUrl`) is suspending, takes the mutex, and is wrapped in a timeout: `loadPlugin`
gets `LOAD_TIMEOUT_MS` (30s, CPU-only), method calls get `CALL_TIMEOUT_MS` (180s, because a call can
issue HTTP that routes through the shared CloudflareInterceptor, which itself can run a WebView solve
plus a Flaresolverr fallback). Success decodes to a typed Kotlin value; failure throws
`LnPluginException`.

`callMethod` works around the fact that `evaluate` returns the Promise object, not its settled value:
it stashes a sentinel on `globalThis.__lnPending`, kicks off the async plugin call with `.then`
handlers that overwrite that global, lets `evaluate` pump the job queue (including the suspend
`__lnFetch` binding), then reads `__lnPending` back as the result envelope (`LnCallResult`). The mutex
makes the shared global safe.

### The Kotlin<->JS bridge

`LnHostBridge` (`reikai.novel.host.LnHostBridge`) is the engine-agnostic host service layer: HTTP via
OkHttp, per-plugin storage via the injected `PreferenceStore`, and logging. It touches no WebView.
`LnPluginHost.engine()` binds four host functions to the runtime:

- `__lnLog(level, message)`: sync. `bridge.log(...)` to logcat.
- `__lnGetStorage(pluginId, key)`: sync, returns `String?`.
- `__lnSetStorage(pluginId, key, value)`: sync, `null` value deletes the key.
- `__lnFetch(url, optsJson)`: async (`asyncFunction`), runs `bridge.runFetch(...)` on `Dispatchers.IO`,
  returns a JSON `FetchResponse` string.

`runFetch` issues the OkHttp call on the shared `NetworkHelper.client`, so the Cloudflare interceptor,
Flaresolverr fallback, and cookie jar all apply to LN traffic for free. It supports a string body, a
multipart body (FormData posts), and a base64 binary body (gRPC-web / protobuf via `fetchProto`), and
returns the final post-redirect URL (Madara plugins read `response.url` to detect Cloudflare
redirects). It defaults the device's real WebView User-Agent via `applyNovelDefaults`
(`reikai.novel.network.NovelRequestHeaders`) unless the plugin set its own; Mihon's generic UA makes
some LN sources serve degraded pages.

Per-plugin storage is namespaced keys over `PreferenceStore` (raw SharedPreferences is forbidden),
keyed `ln_storage::<pluginId>::<key>`. `clearPluginStorage` deletes every key with that prefix; the
uninstall flow and a Clear-data action call it. `getSetting` / `setSetting` on the host read and write
the same `storage:` scope a plugin reads through `@libs/storage`, wrapped in lnreader's `{value: ...}`
envelope, so a value the settings UI writes is exactly what the plugin sees at runtime.

### The JS runtime

`assets/lnhost/headless.js` is the runtime evaluated once per engine. It provides:

- Browser-global polyfills QuickJS lacks: `console`, `URLSearchParams`, `URL` (RFC 3986 base
  resolution), `FormData`, `TextEncoder` / `TextDecoder`, `btoa` / `atob`, `Headers`, `setTimeout` /
  `clearTimeout` (microtask, delay ignored), and a minimal non-locale-aware `Intl`. Each polyfill
  carries a comment naming the plugin(s) that needed it.
- The `@libs/fetch` shim (`fetchApi`, `fetchText`, `fetchProto`) and a `makeResponse` that synthesizes
  the Response surface plugins use (`text`, `json`, `headers.get/has/forEach`, `url`, `ok`). The global
  `fetch` is aliased to `fetchApi` so plugins calling `fetch()` directly still route through the Kotlin
  bridge (never native fetch, which would bypass the interceptors). `fetchApi` merges lnreader's
  default browser-like headers under the plugin's headers, and converts a `FormData` body to a
  multipart field-pair list and a `URLSearchParams` body to a urlencoded string before posting.
- The `@libs/storage` shim (`makeStorage` over `__lnGetStorage` / `__lnSetStorage`) with the three
  lnreader scopes (`storage` / `local` / `session`) stored as the `StoredItem` envelope so booleans,
  arrays, and objects round-trip with their type.
- A `require()` resolver (`makeRequire`) mapping the `@libs/*` aliases and vendor modules plugins
  import: `cheerio`, `htmlparser2`, `dayjs`, `protobufjs`, `urlencode`, `@libs/novelStatus`,
  `@libs/fetch`, `@libs/isAbsoluteUrl`, `@libs/filterInputs`, `@libs/defaultCover`,
  `@/types/constants`, `@libs/aes`, `@libs/utils`, and `@libs/storage`.
- `loadPlugin`, a two-pass loader: pass 1 runs the plugin with `@libs/storage` shadowed by no-ops to
  discover the plugin's intrinsic `id`; pass 2 loads for real with the storage scope set to that
  canonical id and registers the instance in a `plugins` Map keyed by `plugin.id`. This keeps the
  storage scope tied to the plugin's own id, not the caller-supplied (URL-derived) id.
- `callMethod`, which dispatches one method on a loaded plugin and returns the `{ok, value}` /
  `{ok, error}` envelope as a JSON string.

`loadPlugin` and `callMethod` are exposed to Kotlin as `globalThis.__lnLoadPlugin` and
`globalThis.__lnCallMethod`.

### Vendor bundles

Evaluated in `engine()` before `headless.js`, in `assets/lnhost/vendor/`:

| File | What it provides |
|---|---|
| `dayjs.min.js` | `dayjs` date parsing/formatting that plugins use for release times. |
| `htmlparser2.min.js` | The parser backing cheerio (and used directly by some plugins). |
| `cheerio.min.js` | jQuery-like HTML scraping (`cheerio.load`), the workhorse for parsing source pages. |
| `protobuf.min.js` | protobuf.js, powering `@libs/fetch`'s `fetchProto` for gRPC-web sources (e.g. WuxiaWorld). |
| `noble-ciphers.min.js` | `@noble/ciphers` AES-GCM, backing `@libs/aes` (e.g. WTRLAB decrypts chapter bodies). |

Before the vendors load, `engine()` seeds `globalThis.self` / `globalThis.window` in an IIFE so UMD
bundles (protobuf.js) that probe `typeof self/window` attach their global correctly.

### End-to-end call flow

A `popularNovels` call (the others are analogous):

1. UI calls `source.popularNovels(page, optionsJson)` on an `LnPluginSource`.
2. The adapter delegates to `host.popularNovels(info.id, page, optionsJson)`.
3. The host takes the mutex, builds the args as `JsonElement`s, and calls `callMethod` which evaluates
   `globalThis.__lnCallMethod(pluginId, "popularNovels", argsJson)` and pumps the job queue.
4. `headless.js` `callMethod` invokes `plugin.popularNovels(page, options)` and awaits it.
5. The plugin's `require('@libs/fetch').fetchApi(url, init)` calls `await __lnFetch(url, optsJson)`.
6. Kotlin's `__lnFetch` binding runs `bridge.runFetch` on `Dispatchers.IO`: OkHttp on the shared
   client (interceptors + cookie jar inherited), response serialized to a JSON `FetchResponse` string.
7. The Promise resolves, the plugin parses the HTML with cheerio/htmlparser2, returns the list.
8. `callMethod` stringifies `{ok: true, value}`; the host reads it back off `__lnPending`, decodes the
   envelope, and deserializes the value to `List<NovelItem>`.

## File map

### Host (`app/src/main/java/reikai/novel/host/`)

| File | Purpose |
|---|---|
| `LnPluginHost.kt` | The engine owner: lazy QuickJS creation, host-function binding, mutex serialization, typed suspend methods, per-plugin settings accessors, `LnPluginException`. |
| `LnHostBridge.kt` | OkHttp fetch (`runFetch`), namespaced `PreferenceStore` storage, logging; the `FetchOpts` / `FetchResponseDto` wire types. |
| `LnPluginLoader.kt` | Downloads a plugin `.js` and caches it under `cacheDir/lnplugins/<sha>.js`. |
| `LnPluginModels.kt` | Wire DTOs: `LnPluginInfo`, `LnCallResult`, `NovelItem`, `ChapterItem`, `SourceNovel`, `SourcePage`, `ChapterContent`. |

### Source, install, registry, network, download, update (`app/src/main/java/reikai/novel/`)

| Path | Purpose |
|---|---|
| `source/NovelSource.kt` | The source contract; method names track lnreader's `Plugin` for cross-grep. |
| `source/LnPluginSource.kt` | The only `NovelSource` impl: routes each call to a plugin id on an `LnPluginHost`. |
| `source/NovelSourceManager.kt` | In-memory registry of loaded `LnPluginSource`s, keyed by `String` id (disjoint from the manga `SourceManager`'s `Long` ids). |
| `install/LnPluginInstaller.kt` | `installFromUrl` / `uninstall` / `ensureLoaded` / `loadInstalled` / `fetchRepo`, plus the top-level `canonicalizePluginUrl` and per-URL storage-scope derivation. Owns the app-scoped host's load lifecycle. |
| `registry/LnRegistry.kt` | `LnRegistry.parse()` and the `LnRegistryEntry` DTO for an lnreader `plugins.min.json`. |
| `network/NovelRequestHeaders.kt` | `deviceWebViewUserAgent` + the `applyNovelDefaults` request-builder extension shared by the bridge and the cover fetcher. |
| `download/` | `NovelDownloadManager`, `NovelDownloadJob`, `NovelDownloadProvider`, `NovelDownloadStore`, `NovelDownloadNotifier`, `NovelDownload`, `NovelChapterImageInliner`. |
| `update/` | `LnPluginUpdateChecker`, `LnPluginVersion` (plugin-version comparison for update checks). |

### Domain + data (`app/src/main/java/reikai/domain/novel/`, `app/src/main/java/reikai/data/novel/`)

Immutable domain models live in `reikai/domain/novel/model/` (`Novel.kt`, `NovelChapter.kt`,
`NovelTrack.kt`, `LibraryNovel.kt`, etc.); repository interfaces and interactors in
`reikai/domain/novel/` and its `interactor/` package; `NovelPreferences.kt` (including
`installedPluginUrls()`, `installedPluginMetadata()`, `addedRepoUrls()`). Repo implementations and
SQLDelight-row mappers live in `reikai/data/novel/` (`NovelRepositoryImpl`, `NovelChapterRepositoryImpl`,
`NovelTrackRepositoryImpl`, `NovelHistoryRepositoryImpl`, `NovelCategoryRepositoryImpl`, `NovelMapper`,
`NovelMapping`, plus the update jobs `NovelUpdateJob` / `LnPluginUpdateJob`).

### Presentation (`app/src/main/java/reikai/presentation/`)

LN screens follow Mihon's Voyager `Screen`/`ScreenModel` conventions.

| Path | Purpose |
|---|---|
| `presentation/novel/browse/` | Browse + search a source (`NovelBrowseScreen` + `NovelBrowseScreenModel`), grid cell, filter/settings sheets, library-add, duplicate dialog. |
| `presentation/novel/details/` | Novel details (`NovelScreen` + `NovelDetailsScreenModel`), cover dialog, merge-source chips, manage-sources / page-selector. |
| `presentation/novel/reader/` | Chapter reader (`NovelReaderScreen` + `NovelReaderScreenModel`), the WebView-based rendering surface (`NovelReaderWebView` / `NovelReaderHtmlBuilder` / `NovelReaderWebInterface`), reader settings. |
| `presentation/novel/globalsearch/` | Cross-source global search. |
| `presentation/novel/migrate/` | Migrate a novel between sources. |
| `presentation/novel/track/` | Tracker info dialog. |
| `presentation/novel/notes/` | Per-novel notes. |
| `presentation/library/novels/` | Novel library surface: `NovelLibraryScreenModel`, library item/sort/settings, category filter, merge-collapse. |

### Assets

`app/src/main/assets/lnhost/headless.js` and `app/src/main/assets/lnhost/vendor/*.js` (see the vendor
table above).

### Database (`data/src/main/sqldelight/tachiyomi/data/`)

Novel tables parallel the manga tables: `novels.sq`, `novel_chapters.sq`, `novel_history.sq`,
`novel_tracks.sq`, `novel_categories.sq`, `novels_categories.sq`. Migrations live in
`data/src/main/sqldelight/tachiyomi/migrations/`; the highest present migration is `21.sqm`. A new
table or view must ship its own `.sqm` migration (an existing install does not pick up a new
`CREATE TABLE`/`CREATE VIEW` otherwise).

### DI registration (Injekt `addSingletonFactory`, never Koin)

- `eu/kanade/tachiyomi/di/AppModule.kt`, inside the `// RK -->` / `// RK <--` island: `LnPluginHost`,
  `NovelSourceManager`, `LnPluginLoader`, `LnPluginInstaller`, `LnPluginUpdateChecker`,
  `NovelDownloadProvider`, `NovelDownloadManager`. `LnPluginHost` is an app-scoped singleton (the
  headless engine has no Activity dependency), constructed `LnPluginHost(app, NetworkHelper.client, get())`.
- `eu/kanade/domain/DomainModule.kt`: `NovelRepository`, `NovelChapterRepository`,
  `NovelTrackRepository` and the novel interactors.

## Build and run

JDK 21 (Temurin 21.0.11; `JAVA_HOME` must be set, run Gradle via PowerShell on this machine). The
quickjs-kt dependency is wired in `app/build.gradle.kts` (`implementation(libs.quickjs.kt)`, with the
`libquickjs` native lib).

- Compile check: `./gradlew :app:compileDebugKotlin`.
- Host unit tests (no device): `./gradlew :app:testDebugUnitTest` covers the pure host helpers, e.g.
  `reikai.novel.install.CanonicalizePluginUrlTest`, `reikai.novel.registry.LnRegistryTest`,
  `reikai.novel.update.LnPluginVersionTest`.
- Host integration test (on-device / network, NOT a CI test): `HeadlessJsIntegrationTest`
  (`app/src/androidTest/java/reikai/novel/host/HeadlessJsIntegrationTest.kt`), run with
  `./gradlew :app:connectedDebugAndroidTest` against a connected device/emulator. It pulls the live
  lnreader registry (the `plugins/v3.0.0/.dist/plugins.min.json` URL), then loads + exercises a sample
  through the real `LnPluginHost` (no WebView, no Activity). Anchor plugin ids that prove the harder
  paths: `novelhall`, `scribblehub`, `novelbin` (URL + object-init `URLSearchParams`), `wuxiaworld`
  (`fetchProto`), `WTRLAB` (`@libs/aes` via noble-ciphers + `atob`). It asserts every fetched plugin
  loads and at least one completes the full search -> parseNovel -> parseChapter chain headlessly. Read
  the per-plugin breakdown from logcat tag `HeadlessJsTest`. A second test,
  `javaScriptEngineEvaluatesSyncSnippets`, covers the manga-side `JavaScriptEngine` (now dokar-backed).

## Extending: add a shim or vendor bundle

When a plugin imports something the runtime does not provide, the warning path is
`makeRequire`'s `_require` logging `require: unknown module "<name>"`. To add support:

1. Find the upstream definition in the lnreader reference: `@libs/*` helpers in
   `refs/lnreader-main/src/plugins/helpers/`, the resolver shape in
   `refs/lnreader-main/src/plugins/pluginManager.ts`, plugin source in `refs/lnreader-plugins/`.
2. Pure-JS `@libs/*` (constants or trivial functions): add it to the `packages` map in `makeRequire`
   in `headless.js`, mirroring the existing entries (`@libs/novelStatus`, `@libs/filterInputs`,
   `@libs/isAbsoluteUrl`, `@libs/defaultCover`, `@libs/utils`).
3. Host-backed behavior (HTTP, storage, anything Kotlin must do): add a method to `LnHostBridge`, bind
   it as a host function in `LnPluginHost.engine()` (sync via `q.function`, async via
   `q.asyncFunction` like `__lnFetch`), and call it from the JS shim in `headless.js`. Match the
   `__lnFetch` JSON-string-in/JSON-string-out contract so values cross the boundary cleanly.
4. A new npm dependency: bundle it as a self-contained IIFE/UMD that attaches to a `globalThis` name,
   drop it in `assets/lnhost/vendor/`, add a `q.evaluate(asset("lnhost/vendor/<file>"))` line in
   `engine()` before `headless.js`, and reference the global from the `packages` map or a shim. If the
   bundle probes `self`/`window` at load, the seeding IIFE in `engine()` already covers it.
5. A new browser global a plugin assumes: add a guarded polyfill block (`if (typeof globalThis.X ===
   "undefined")`) in `headless.js`, alongside the existing `URL` / `Headers` / `FormData` blocks, with
   a comment naming the plugin that needed it.

After any change, run `:app:compileDebugKotlin`, then re-run `HeadlessJsIntegrationTest` on a device
(at minimum the anchor ids) to confirm no engine regression.
