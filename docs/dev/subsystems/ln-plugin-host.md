# LN plugin host

## Purpose

The LN plugin host runs LNReader's JavaScript plugins, unmodified, in headless QuickJS engines inside the app: no WebView and no Activity. A light-novel plugin therefore works the same on a screen and in a background worker (library updates, downloads, the plugin update check), and the user installs plugins from a registry URL they add at runtime, since no plugin code ships in the APK. This doc covers the engine, the JavaScript runtime and its polyfills, and plugin install, load and update; how a plugin becomes a `NovelSource` beside the two APK source kinds is [browse-and-sources.md](browse-and-sources.md).

## How it works

### Engines and slots

`LnPluginHost` is an app-scoped singleton on the Metro graph. It runs `com.dokar.quickjs` engines (`io.github.dokar3:quickjs-kt`), one per plugin. Each plugin has an `EngineSlot`: its own single-thread executor (thread `LnPlugin-<id>`), its own `Mutex` and its own `QuickJs`. QuickJS is not thread-safe, so every native call for a slot runs on that executor behind that mutex, and the host makes the hop itself (`EngineSlot.onThread`) because dokar evaluates on the calling thread. No caller needs its own `flowOn` or `launchIO` to keep engine creation, the asset read or a plugin's code off the main thread. Calls to different plugins run in parallel.

Engines are lazy and short-lived. A slot builds its engine on its first call and an idle sweeper (`ensureSweeper`) closes any engine unused for 60 seconds, retiring its thread, so an idle plugin holds no native engine. Building an engine binds the host functions, seeds `globalThis.self` / `globalThis.window`, evaluates `RUNTIME_ASSETS` (the five vendor bundles, then `headless.js`, read from assets once and cached as strings), and replays the plugin's load from its retained `LoadArgs`. The engine is published to the slot only once all of that succeeded; a failure closes it and surfaces as `LnPluginException`, so the next call rebuilds from scratch. `QuickJs.create` itself is serialized across slots by `engineCreationMutex`, because the native init caches process-wide JNI global refs and two concurrent creations abort the runtime.

`loadPlugin` (info extraction at install and app start) runs on one shared loader slot instead, so a bulk load of every installed plugin costs one engine. It decodes the returned `LnPluginInfo`, derives `supportsLatest` from the source text (`derivesLatestSupport`: the plugin reads `showLatestNovels`), stores the `LoadArgs` under the plugin's own id and closes any live engine still running the previous code.

### Calls

The public methods are `loadPlugin`, `popularNovels`, `searchNovels`, `parseNovel`, `parsePage`, `parseChapter` and `resolveUrl`, all suspending. `loadPlugin` gets a 30 second budget (CPU only); a method call gets 180 seconds, because its HTTP can route through the shared Cloudflare interceptor's WebView solve and then the FlareSolverr fallback. `withPluginTimeout` turns the deadline into an `LnPluginException`, never a bare `TimeoutCancellationException`. A failed call throws `LnPluginException`, with the last fetch failure the slot saw attached as its cause, which is how an error tells offline from a plugin bug. `parsePage` and `resolveUrl` are optional in the format: a missing or throwing method returns null.

`evaluate` returns the Promise, not its settled value, so `callMethod` starts `__lnCallMethod(...)` with `.then` handlers that write the `LnCallResult` envelope into a per-call slot on `globalThis.__lnResults` keyed by a call id, then polls that slot, pumping the job queue (including the async `__lnFetch` binding) until it settles. A per-call key means a call that timed out and settles later can never be read as the next call's answer; the map is reset past 64 pending slots.

### Host functions

`LnHostBridge` is the engine-agnostic service layer: HTTP, storage and logging, with no WebView. The host binds five functions into each engine:

- `__lnLog(level, message)`, sync, to logcat.
- `__lnGetStorage(pluginId, key)` and `__lnSetStorage(pluginId, key, value)`, sync; a null value deletes.
- `__lnFetch(url, optsJson)`, async, runs `runFetch` on `Dispatchers.IO` and returns a JSON response string.
- `__lnDelay(ms)`, async, suspends for the delay capped at 30 seconds; it backs the `setTimeout` polyfill that plugins use for rate-limit pauses and retry backoff.

`runFetch` issues the request on the shared `NetworkHelper.client`, so the Cloudflare interceptor, FlareSolverr fallback and cookie jar apply to plugin traffic as they do to manga. It takes a string body, a multipart field list (FormData posts) or a base64 binary body (`fetchProto`), sends an empty body for a bodyless POST, PUT or PATCH, echoes the jar's `XSRF-TOKEN` cookie as `X-XSRF-TOKEN` unless the plugin set it, and returns the post-redirect URL, which Madara-family plugins read to detect a challenge redirect. `applyNovelDefaults` sets the device's real WebView User-Agent unless the plugin set one; the bridge resolves that UA per request through a supplier, because reading it loads the WebView provider, which blocks for seconds on a cold device while the host's singleton lock is held.

### The runtime, `headless.js`

Evaluated once per engine after the vendors, it provides:

- **Polyfills** for the browser globals QuickJS lacks, each guarded by `typeof` and commented with the plugins that needed it: `console`, `URLSearchParams` (string, record and pairs init), `URL` (RFC 3986 base resolution, no IDNA), `FormData`, `TextEncoder` / `TextDecoder`, `btoa` / `atob`, `Buffer` (base64, hex, utf8), `Blob`, `Headers`, `setTimeout` / `clearTimeout` over `__lnDelay` (`setInterval` is a no-op), and a non-locale-aware `Intl`.
- **`@libs/fetch`**: `fetchApi`, `fetchText`, `fetchProto`. `makeResponse` builds the Response surface plugins read (`url`, `status`, `ok`, `headers`, `text`, `json`, `arrayBuffer`, `blob`). `fetchApi` merges LNReader's default browser-like headers under the plugin's, flattens a `Headers` instance, sends `FormData` as multipart and `URLSearchParams` urlencoded. The global `fetch` is `fetchApi`, so a plugin calling `fetch()` still goes through Kotlin. `fetchProto` frames a gRPC-web request with protobuf.js and returns the decoded message, enums numeric.
- **`@libs/storage`**: `storage` over the storage functions in LNReader's `{created, value, expires}` envelope, so booleans, arrays and objects keep their type and expiry works; `localStorage` and `sessionStorage` as LNReader's keyless, read-only `get()` over the site storage captured from a WebView (below).
- **`makeRequire`**, the module map: `cheerio`, `htmlparser2`, `dayjs`, `protobufjs`, `urlencode`, `buffer`, `@libs/novelStatus`, `@libs/fetch`, `@libs/isAbsoluteUrl`, `@libs/filterInputs`, `@libs/defaultCover`, `@/types/constants`, `@libs/aes` (noble-ciphers GCM), `@libs/utils` and `@libs/storage`. An unknown name logs `require: unknown module` and returns undefined.
- **`loadPlugin`**, two passes: the first runs the plugin with `@libs/storage` shadowed by no-ops to discover its own `id`, the second loads it for real with storage scoped to that id and registers it in a `plugins` map. The returned info carries the filter and settings schemas untouched, the image headers from `imageRequestInit`, and `webStorageUtilized`.
- **`callMethod`**, which dispatches one method and returns `{ok, value}` or `{ok, error}` as a JSON string.

The vendor bundles in `assets/lnhost/vendor/` are `dayjs`, `htmlparser2`, `cheerio`, `protobuf` (for `fetchProto`) and `noble-ciphers` (for `@libs/aes`). UMD bundles find their global through `typeof self` / `typeof window`, hence the seeding before they load.

### Storage and settings

A plugin's storage is `PreferenceStore` keys `ln_storage::<pluginId>::<key>` (`lnStorageScope`), scoped by the plugin's own id; the URL-derived id from `scopeIdFromUrl` is used only when id discovery throws. The settings UI reads and writes the same `storage:` keys through `LnPluginHost.getSetting` / `setSetting` and the same `{value: ...}` envelope, so what the UI saves is what the plugin reads. Uninstalling leaves the keys, as Mihon leaves an extension's source preferences, so a reinstall gets its settings and logins back.

Site storage follows LNReader's contract. When the in-app browser is opened for a plugin (a plugin row, or a novel, chapter or catalogue of a plugin source), `rememberPluginStorageCapture` runs `WEB_STORAGE_SCRIPT` after every page load and `PluginWebStorageViewModel` keeps the newest storage per host. When the screen's model is cleared it resolves the plugin through `webStoragePlugin`, which answers only for an installed plugin declaring `webStorageUtilized`, picks the newest page on the plugin's site or a subdomain (`pluginSiteStorage`, over `isSameSite`), and hands it to `LnPluginHost.storeWebStorage`. That writes `webview:local` / `webview:session` and marks the slot for a rebuild, since a plugin can read the storage once, in its constructor. The captured storage holds the site's sign-in, so `PreferenceBackupCreator` backs it up only with sensitive settings included (`isSensitivePluginKey`).

### Install, load and update

`LnPluginInstaller` owns the host's load lifecycle and is the app's `LnRegistryFetcher`. `LnPluginLoader` keeps each installed script at `filesDir/lnplugins/<half sha256 of url>.js`, with the chapter stylesheet the registry names beside it as `.css`, written atomically; a script without `exports.default` reads as not installed, and one an older install left in `cacheDir` is adopted.

- **Install** (`installFromUrl`) downloads the script, loads it, and stores it only once it loads, so a broken new version leaves the installed one in place. It then replaces any other installed URL with the same plugin id, writing the URL set, the `LnInstalledPluginMetadata` record and the source registration in one `registryMutex` section.
- **Load** (`ensureLoaded`) loads every installed URL not yet loaded this process, in parallel, behind `loadMutex`; a failure lands in `failures` (`LnPluginLoadFailure`: Missing, Malformed or Failed) for the Extensions list and the crash log, and is retried on the next `ensureLoaded`. A missing script is downloaded again from its installed URL. `awaitFirstLoad` runs one pass if none has run and never retries, which is what a source lookup uses (`NovelSourceManager.get`). Screens and workers call `ensureLoaded` at their start; `NovelUpdateWorker` does, so a cold process has its sources.
- **Uninstall** removes every URL mapped to the plugin id and unregisters the source.
- **Updates.** `LnPluginUpdateChecker.check` fetches every added repo through `fetchEach` and diffs it with `findPluginUpdates` against the version each plugin last reported when it loaded (`recordLoadedVersion`), compared by `LnPluginVersion`. `runIfStale` gates it to once per six hours on app launch and Browse open; `LnPluginUpdateWorker` runs it every 12 hours and posts the shared extension-update notification through `LnPluginUpdateNotifier`.
- **Registries.** `LnRepoRegistries` holds every added repo's outcome (`Reached` or `Unreachable`) for the screens, fetched once and on refresh. A restore never installs plugins: `BackupFileValidator` lists the backup's missing ones for the user to install, and `AppPreferenceCarry` skips the installed-plugin keys.

### Manga's JavaScript engine

Only one QuickJS ships, because two bindings both build `libquickjs.so` and collide at native-lib merge. Manga extensions compile against `app.cash.quickjs.QuickJs`, so `core/common` carries a class of that name over the same dokar engine, confined to its own thread, and Mihon's `JavaScriptEngine` keeps its body over it. The shim converts a JS array back to `Object[]` (dokar returns a `List`); objects stay dokar's `Map`, since Cash never returned one.

## Key files

- `app/src/main/java/reikai/novel/host/LnPluginHost.kt`: `LnPluginHost`, `EngineSlot`, `callMethod`, `ensureSweeper`, `storeWebStorage`, `RUNTIME_ASSETS`, `derivesLatestSupport`, `withPluginTimeout`, `LnPluginException`.
- `app/src/main/java/reikai/novel/host/LnHostBridge.kt`: `runFetch`, `getStorage`, `setStorage`, `parseFetchOpts`.
- `app/src/main/java/reikai/novel/host/LnPluginLoader.kt`: `download`, `installed`, `store`, `storeStylesheet`, `adoptCachedScript`.
- `app/src/main/java/reikai/novel/host/LnPluginModels.kt`: `LnPluginInfo`, `LnCallResult`, `NovelItem`, `ChapterItem`, `SourceNovel`, `SourcePage`.
- `app/src/main/java/reikai/novel/host/WebStorageSnapshot.kt`: `WEB_STORAGE_SCRIPT`, `parseWebStorage`.
- `app/src/main/java/reikai/novel/host/NovelTextSanitizer.kt`: `stripInvalidChars`, `decodeEntities`.
- `app/src/main/assets/lnhost/headless.js`: `makeRequire`, `loadPlugin`, `callMethod`, `fetchApi`, `fetchProto`, `makeStorage`, `makeWebStorage`.
- `app/src/main/assets/lnhost/vendor/`: the five vendor bundles.
- `app/src/main/java/reikai/novel/install/LnPluginInstaller.kt`: `installFromUrl`, `ensureLoaded`, `awaitFirstLoad`, `loadInstalled`, `uninstall`, `fetchRepo`, `canonicalizePluginUrl`.
- `app/src/main/java/reikai/novel/install/LnPluginLoadFailure.kt` and `LnPluginCrashLog.kt`: `LnPluginLoadFailure.Reason`, `novelPluginCrashLogEntries`.
- `app/src/main/java/reikai/novel/registry/LnRegistry.kt` and `LnRepoRegistries.kt`: `LnRegistryEntry`, `fetchEach`, `pluginRepos`, `LnRepoRegistries`.
- `app/src/main/java/reikai/novel/update/LnPluginUpdateChecker.kt`: `findPluginUpdates`, `runIfStale`; `LnPluginVersion.kt`, `LnPluginUpdateNotifier.kt`.
- `app/src/main/java/reikai/data/novel/update/LnPluginUpdateWorker.kt`: `setupTask`.
- `app/src/main/java/reikai/novel/source/LnPluginSource.kt`: the `NovelSource` adapter over the host.
- `app/src/main/java/reikai/novel/source/PluginStorageKeys.kt`: `lnStorageScope`, `WEB_STORAGE_KEY_PREFIX`, `isSensitivePluginKey`.
- `app/src/main/java/reikai/presentation/webview/PluginWebStorage.kt`: `rememberPluginStorageCapture`, `pluginSiteStorage`, `webStoragePlugin`.
- `core/common/src/main/kotlin/app/cash/quickjs/QuickJs.kt`: the Cash API shim over dokar.

## Invariants and traps

- **A missing or wrong polyfill fails silently.** A plugin does not throw on a bad shim; it builds an empty query and returns zero results, which looks like a broken site. A string-only `URLSearchParams` once dropped every object-init param across about thirty plugins. When a source that worked returns nothing, check polyfill parity against the plugin's source before blaming the site.
- **No plugin request bypasses Kotlin.** The global `fetch` is aliased to `fetchApi`; exposing a native fetch would skip the Cloudflare interceptor, FlareSolverr and the cookie jar.
- **Every native call stays on its slot's thread, under its mutex.** A new host method goes through `callMethod` or `onThread`; calling `evaluate` from the caller's thread runs plugin code on a screen's main thread.
- **An engine is published only after its replay succeeded.** Publishing first leaves a live engine without the plugin, answering "plugin not loaded" until it idles out.
- **Each call reads its own result slot.** A shared slot lets a timed-out call's late settle become the next call's answer, writing one novel's metadata over another's row.
- **A screen that shows whether a source is installed reads `NovelSourceManager.loadedSources()`.** The registry starts empty, so the raw `sources` draws every installed plugin as missing until something loads them. The novel library keeps the raw flow, since it must not wait on a slow load.
- **Storage keys are the plugin's own id.** The URL-derived id stands in only when id discovery throws; scoping by URL would split a plugin's settings between repos.
- **The `webview:` key spelling is shared by Kotlin and JS.** `WEB_STORAGE_KEY_PREFIX` and `makeWebStorage` must agree, and `FilterTypes` member names must match LNReader's enum, since compiled plugins look them up by name.
- **A load and an install or uninstall of one plugin meet on its URL lock.** Lock order is URL lock, then `registryMutex`; uninstall takes several in sorted order. A load rechecks under the lock that its URL is still installed. Accepted residual: a load of a just-replaced URL racing an install from a new URL can leave the engine on the old script until its next load, while the registry and records stay right.

## Decisions

- **Headless QuickJS, not a WebView.** A WebView needs a live screen, so background updates and downloads could not reach a plugin. Void if plugins come to need a real DOM.
- **Polyfill what shipped plugins use, not a browser.** `document`, `navigator`, `XMLHttpRequest` and a global `crypto` are deliberately absent because no shipped plugin calls them; a new plugin needing one gets a shim.
- **One engine per plugin, lazily built and idle-closed, plus a shared loader engine.** Per-plugin engines let different sources run in parallel; the loader keeps a bulk load to one engine. A single shared engine serialized all plugin traffic app-wide.
- **One QuickJS for manga and novels.** Two QuickJS natives collide on `libquickjs.so`, so manga's `JavaScriptEngine` runs on dokar through the Cash shim. Void if dokar's value shapes drift from Cash's beyond arrays.
- **Plugin traffic uses Mihon's network stack.** The shared client gives plugins the Cloudflare and FlareSolverr handling with no novel-specific networking.
- **An update comes from the repo still listing the installed URL.** Accepting another repo's entry replaces the installed script with that repo's, and the listing is the only record of where a plugin came from. With no such listing the highest version wins, only once every repo was reached, since a down repo looks like one that dropped the plugin. Revisit if moving plugins between repos becomes wanted.
- **Every added repo is fetched through one fan-out, `fetchEach`.** Each repo gets its own outcome and a cancellation is never reported as a down repo; each caller reads Unreachable its own way.
- **Only a plugin declaring `webStorageUtilized` gets site storage, as in LNReader.** A plugin that never asked receives neither the site's tokens nor an engine rebuild on every browser close.
- **Only a page on the plugin's own site gives it storage, stricter than LNReader.** The capture runs in every window, popups included, so keeping the last page would hand a sign-in popup's or an off-site page's storage to plugin code that can make network calls.
- **The version recorded is the one the plugin reports when it loads, not the repo's.** A record from the repo, or a pasted URL without one, would hide an update.

## Upstream divergences

Plugin-host patches sit in `// RK` lines in these Mihon files: `core/common/build.gradle.kts` (the `quickjs-kt` dependency replacing Mihon's QuickJS), `WebViewScreen` and `WebViewActivity` (the storage capture and the novel source id), `PreferenceBackupCreator` (plugin storage rides Source settings, the captured sign-in only with sensitive settings), and `MainActivity` (the update check on launch). The worker is registered by Reikai's own `SetupLnPluginUpdateMigration`. `app/proguard-rules.pro` keeps `app.cash.quickjs.**`, which extensions call by name.

## Extending

- **A new browser global**: add a `typeof`-guarded polyfill in `headless.js` beside the existing blocks, with a comment naming the plugin that needed it.
- **A pure-JS `@libs/*` module**: add it to the `packages` map in `makeRequire`. LNReader's own helpers are in `refs/lnreader-main/src/plugins/helpers/`, the resolver in `refs/lnreader-main/src/plugins/pluginManager.ts`, plugin sources in `refs/lnreader-plugins/`.
- **Host-backed behaviour** (anything Kotlin must do): add a method to `LnHostBridge`, bind it in `EngineSlot.setUp` (`q.function` for sync, `q.asyncFunction` for async), and call it from a shim, keeping `__lnFetch`'s JSON-string-in, JSON-string-out shape.
- **A new npm dependency**: bundle it as a self-contained IIFE or UMD attaching a global, drop it in `assets/lnhost/vendor/`, add it to `RUNTIME_ASSETS` before `headless.js`, and reference the global from `makeRequire`.
- **A new plugin method**: add a suspending method on `LnPluginHost` over `callMethod`, a DTO in `LnPluginModels.kt`, and the `NovelSource` member in `LnPluginSource`.

Then run the host unit tests and `HeadlessJsIntegrationTest` on a device.

## Tests

JVM unit tests (`./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`): host `LnHostBridgeTest`, `LnPluginHostThreadTest`, `LnPluginLoaderTest`, `PluginTimeoutTest`, `LatestSupportTest`, `ChapterItemScanlatorTest`, `WebStorageSnapshotTest`, `NovelTextSanitizerTest`; install `LnPluginInstallerRaceTest`, `LnPluginLoadFailureTest`, `LnPluginCrashLogTest`, `LnRegistryFetchTest`, `CanonicalizePluginUrlTest`; registry and update `LnRegistryTest`, `LnRepoRegistriesTest`, `FindPluginUpdatesTest`, `LnPluginUpdateCheckerTest`, `LnPluginUpdateNoticeTest`, `LnPluginVersionTest`; the adapter `LnPluginSourceBrowseTest`, `LnPluginSourceSettingsTest`; site storage `PluginWebStorageTest`, `SameSiteTest`; the Cash shim `CashValueTest` (under `:core:common`). The JVM has no QuickJS native library, so none of these runs plugin code.

`HeadlessJsIntegrationTest` (instrumented, network, not CI) runs the real host on a device: it fetches the live LNReader registry and drives anchor plugins (`novelhall`, `scribblehub`, `novelbin`, `wuxiaworld`, `WTRLAB`) through search, `parseNovel` and `parseChapter`, and pins the replay rebuild, the storage envelope, the site-storage handoff, gRPC-web enums and the Cash shim. Run it with `adb shell am instrument`, as [on-device-testing.md](../on-device-testing.md) describes, and read logcat tag `HeadlessJsTest`.

## Related

- User doc: [extensions.md](../../faq/browse/extensions.md) (extension apps and plugins).
- The `NovelSource` seam over plugins and the two APK kinds: [browse-and-sources.md](browse-and-sources.md).
