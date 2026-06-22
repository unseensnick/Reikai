# Headless LN plugin host (QuickJS)

Run LNReader's JavaScript plugins in a headless QuickJS engine instead of an on-screen WebView, so light-novel sources work everywhere a manga source does: in the background (library updates, downloads) and on-screen, from one app-scoped runtime.

This is the developer-facing decision record for Phase 8c: what was decided and why. The deep navigation guide for working in the code (architecture layers, the per-call flow, the file map, the cold-start and shim-authoring recipes) lives in [ln-plugin-host.md](../ln-plugin-host.md).

## Goal

Replace the WebView-bound plugin host with a single app-scoped, headless QuickJS host that runs the same unmodified LNReader plugins, with identical results on a screen or on a background worker. Concretely: novel library updates and downloads run from a cold process (no novel screen ever opened), the host is created once per app, and plugins keep loading lazily without each screen building and tearing down its own runtime.

## Why

Background-capable novel sources need a JS runtime that does not depend on a live WebView.

A WebView is a UI widget. It needs an Activity-flavored context and a live screen to load assets and fire its page-finished callback, so a plugin running inside one only works while that screen is open. That blocked everything off-screen: a background library update launched into a cold process found no loaded sources and bailed, and downloads were a no-op stub. It was also wasteful on-screen, since each of the novel screens built its own throwaway host and reloaded every plugin on entry, with one screen leaking it.

QuickJS is a small embeddable JavaScript engine that runs on a plain background thread with no Activity and no UI. Running the plugins there decouples them from the screen lifecycle: one runtime serves the whole app, updates and downloads work cold, and screens just read from the shared source registry.

## Approach

There is exactly one plugin host for the whole app. It is created lazily the first time anything asks for a novel source, loads the supporting JavaScript once, and stays alive for the process. Plugins are loaded once and registered into a shared source list; any screen or background worker reads from that same list. Because the engine runs JavaScript on a single thread, every call into it is run one at a time behind a lock, so two callers can never clobber the shared engine state.

The single biggest correctness concern of this design is the polyfill surface: the old WebView gave plugins a full browser environment for free (things like `URLSearchParams`, `URL`, `fetch`, a DOM). QuickJS gives almost none of that. The headless host has to supply (polyfill) every browser feature a plugin actually uses, in a runtime script that loads before any plugin. If a polyfill is missing or subtly wrong, the plugin does not crash with a clear error: it fails silently (an empty query string, a 0-result search), which looks like a broken website rather than a missing shim. Polyfill completeness is the thing this phase lives or dies on. See the [polyfill parity note](../ln-plugin-host.md) and the `ln-host-polyfill-parity` memory for the audited list of what real plugins use versus what is safe to omit.

### The runtime

The engine is `com.dokar.quickjs` (quickjs-kt), bundled in the main APK. It is confined to one dedicated single-thread executor (`LnPluginHost.kt`) because QuickJS is not thread-safe, and the engine and its assets are loaded once on first use (`engine()` in `LnPluginHost.kt`). Only one QuickJS engine ships: the manga-side `JavaScriptEngine` extensions-lib API was migrated onto the same engine, because two QuickJS natives both build as `libquickjs.so` and collide at native-lib merge time. One engine, one native library.

### The polyfill surface

The runtime script `app/src/main/assets/lnhost/headless.js` is the headless analogue of the old WebView `bootstrap.js`: it provides the `require` shim that maps `@libs/*` and npm module names to their implementations, the two-pass plugin loader, the method dispatcher, and the browser-global polyfills QuickJS lacks (`fetch` via a host binding, `URLSearchParams`, `FormData`, `TextEncoder`, `console`, `window`/`self`, etc.). Vendor bundles loaded before it supply the heavier libraries plugins import (`LnPluginHost.kt`): cheerio and htmlparser2 (HTML parsing), dayjs (dates), protobuf.js (gRPC-web sources like WuxiaWorld, via `@libs/fetch`'s `fetchProto`), and noble-ciphers (AES-GCM, backing `@libs/aes` for sources that decrypt chapter bodies).

UMD bundles like protobuf.js attach themselves to a global they discover via `typeof self`/`typeof window`, which QuickJS does not define, so `globalThis.self`/`globalThis.window` are seeded before the vendors load, inside an IIFE so the completion value is not the self-referential global (dokar cannot marshal that back to Kotlin: "circular reference").

### Host bindings (the Kotlin side of fetch and storage)

`LnHostBridge` holds the engine-agnostic core (the OkHttp-backed `runFetch`, preference-backed storage, logging). The host binds these into the engine as native functions (`LnPluginHost.kt`): `__lnLog`, `__lnGetStorage`/`__lnSetStorage` (synchronous), and `__lnFetch` (async, runs OkHttp on `Dispatchers.IO`). Because fetch shares `NetworkHelper.client`, novel traffic inherits the same Cloudflare interceptor, cookie jar, and FlareSolverr fallback as manga traffic for free.

### How a call runs headless

A plugin method call (`callMethod` in `LnPluginHost.kt`) takes the lock, then evaluates `__lnCallMethod(...)` in the engine. That call is async (it issues HTTP through the `__lnFetch` binding), and `evaluate` returns the Promise rather than its settled value, so the result is stashed on a global the engine fills while `evaluate` pumps its job queue, then read back as a string. The lock makes that shared global safe. Per-call timeouts guard runaway plugins: 30s for the CPU-only plugin load, 180s for a method call (wide enough to cover a WebView Cloudflare solve plus a FlareSolverr fallback on the shared client).

### Load-once, app-scoped wiring

The host is an Injekt singleton (`addSingletonFactory { LnPluginHost(app, get<NetworkHelper>().client, get()) }` in `AppModule.kt`). `LnPluginInstaller` (also a singleton in `AppModule.kt`) owns it and exposes `ensureLoaded()` (`LnPluginInstaller.kt`), which loads every installed plugin into the shared `NovelSourceManager` exactly once per process behind its own load-mutex, retrying only the ones that failed. Screens and workers call `ensureLoaded()` and read sources from the manager; they no longer build their own host. The background path is wired the same way: `NovelUpdateJob` injects the installer and calls `ensureLoaded()` before iterating (`NovelUpdateJob.kt`), so a cold process finds populated sources.

There is no separate on-screen versus background code path anymore. Both go through the one app-scoped host. The only remaining WebView in novel territory is the unrelated "open in WebView" escape hatch for clearing Cloudflare/Turnstile challenges, which is not the plugin host.

## Key files

Confirmed present in the current tree (`reikai.*` package, post-rebase):

- [`app/src/main/java/reikai/novel/host/LnPluginHost.kt`](../../../app/src/main/java/reikai/novel/host/LnPluginHost.kt): the headless host: QuickJS creation, vendor + runtime load, the mutex-serialized `callMethod`, the public suspend methods (`loadPlugin`, `popularNovels`, `parseNovel`, `parseChapter`, `parsePage`, `resolveUrl`, `searchNovels`, settings/storage helpers, `destroy`).
- [`app/src/main/java/reikai/novel/host/LnHostBridge.kt`](../../../app/src/main/java/reikai/novel/host/LnHostBridge.kt): engine-agnostic host bindings: `runFetch` (OkHttp), storage, logging.
- [`app/src/main/java/reikai/novel/host/LnPluginLoader.kt`](../../../app/src/main/java/reikai/novel/host/LnPluginLoader.kt): download + cache the plugin `.js`.
- [`app/src/main/assets/lnhost/headless.js`](../../../app/src/main/assets/lnhost/headless.js): the runtime: `require` shim, two-pass loader, dispatcher, browser-global polyfills.
- [`app/src/main/assets/lnhost/vendor/`](../../../app/src/main/assets/lnhost/vendor/): `cheerio.min.js`, `htmlparser2.min.js`, `dayjs.min.js`, `protobuf.min.js`, `noble-ciphers.min.js`.
- [`app/src/main/java/reikai/novel/install/LnPluginInstaller.kt`](../../../app/src/main/java/reikai/novel/install/LnPluginInstaller.kt): owns the host; `ensureLoaded()` / `loadInstalled()`.
- [`app/src/main/java/reikai/novel/source/NovelSourceManager.kt`](../../../app/src/main/java/reikai/novel/source/NovelSourceManager.kt), [`LnPluginSource.kt`](../../../app/src/main/java/reikai/novel/source/LnPluginSource.kt): shared source registry + adapter.
- [`app/src/main/java/reikai/data/novel/update/NovelUpdateJob.kt`](../../../app/src/main/java/reikai/data/novel/update/NovelUpdateJob.kt): background update path; calls `ensureLoaded()` cold.
- [`app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`](../../../app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt): `// RK` Injekt registration of the host + installer singletons.

## Status

Shipped. The headless host is the live runtime for all novel sources; background novel updates run cold, and the per-screen WebView hosts are gone. Part of the P5 light-novel vertical (see [ROADMAP.md](../../../ROADMAP.md), P5 row).

## Decisions & tradeoffs

- **Polyfill completeness over a generic browser shim.** QuickJS lacks the browser globals the WebView gave for free, and the host only polyfills what shipped plugins actually use (audited list in the `ln-host-polyfill-parity` memory). Deliberately omitted because no shipped plugin uses them: `atob`/`btoa`, `Blob`, `document`, `navigator`, a real `XMLHttpRequest`, `Buffer`, a global `crypto`. The tradeoff is that adding a plugin that uses a missing global means adding a shim, but the alternative (shipping a full browser emulation) is far more surface to maintain for features nobody calls.

- **The silent-failure risk is the defining hazard.** An incomplete shim does not throw. A string-only `URLSearchParams` once dropped object-init params (`new URLSearchParams({novelId: id})`) and broke search and pagination across roughly 32 plugins with no error, just empty queries and 0 results. So "worked before, broken now" on a novel source almost always means a browser-global parity gap, not a site change. The defense is to verify polyfill parity against the real plugin sources rather than assuming the site changed.

- **One QuickJS engine, app-scoped, single-threaded + locked.** Two QuickJS natives cannot coexist (both `libquickjs.so`), so the manga `JavaScriptEngine` moved onto the same dokar engine; its public signature is unchanged, so third-party manga extensions do not recompile (residual risk is purely behavioral, covered by tests). One app-scoped host (not a per-screen or pooled set) keeps loading cheap and state shared; a worker pool was left out as YAGNI. The cost of single-threading is that all plugin calls serialize behind a mutex, which is the explicit correctness requirement that makes the shared global safe.

- **Reuse Mihon's network stack.** Fetch goes through the shared `NetworkHelper.client`, so the Cloudflare interceptor, cookie jar, and FlareSolverr fallback apply to novel traffic with no novel-specific networking code. The interactive Cloudflare/Turnstile cases still surface the same "open in WebView" escape hatch as manga; that WebView is not the plugin host.
