# Light-novel plugin host — cold-start handbook

This doc is the single-source recipe for picking up the LN plugin host work after a break. A fresh session reading this from scratch should be able to navigate, build, and extend the host without consulting prior conversation history.

Start by reading **Where things stand**, then **Architecture**, then jump to whatever section your task touches.

## Where things stand

The work lives on the **`feat/ln-plugin-host-spike` branch**, not on `main`. The branch bundles the hot spike + the compatibility soak + Phase 3 product work, planned to ship as one cohesive "LN plugin host MVP" PR.

```bash
git checkout feat/ln-plugin-host-spike
```

Phase 1 (hot spike — host bridge proves novelbin works) and Phase 2 (compatibility soak — host validated against 8 lnreader plugins) are **done and on origin**. Phase 3 (`NovelSource` Kotlin interface + plugin registry parser + add-repo Compose screen + library / browse / details / reader / tracking) is **not started**.

Per-plugin compatibility status lives in [`app/src/main/assets/lnhost/COMPATIBILITY.md`](../../app/src/main/assets/lnhost/COMPATIBILITY.md). Read that first when designing Phase 3 — it captures every known gap, every `@libs/*` method's status, every limitation the host doesn't solve.

## Why this exists

Reikai is a Kotlin/Android manga reader (Tachiyomi / Mihon lineage). The goal is to expand it into a unified manga + light-novel reader **without abandoning the Tachiyomi manga extension ecosystem** and **without taking on maintenance of a forked light-novel plugin tree**. Decision matrix:

- **A: Native Kotlin LN module + new Kotlin extension contract.** Would mean a brand-new plugin ecosystem from zero. Rejected — too much per-source porting work.
- **B (chosen): Native Kotlin LN module that runs upstream lnreader plugins unmodified.** Reimplement lnreader's `require('@libs/*')` resolver in JS, host plugins in a WebView, bridge HTTP / storage to Kotlin via `@JavascriptInterface`. Plugin-side surface area stays at zero maintenance.
- **C: React Native shell + Kotlin bridge for Tachiyomi extensions.** Killer downside: the Tachiyomi extension contract assumes Android Activity, Coil, OkHttp, etc. Bridging to a RN host is months of glue plus re-implementing every Reikai screen.
- **D: Codemod lnreader TypeScript plugins to Kotlin.** Generates Kotlin source files we'd then own — every upstream plugin update means re-running the transpiler and patching the 20% it gets wrong. Rejected: we'd be maintaining a fork-by-translation indefinitely.

We picked B specifically because the maintenance load is bounded: pulling new plugin code is `git pull` in the registry, never a code change in Reikai.

Plugin source code is **not bundled** in the APK. Same legal distance as the Tachiyomi manga extension model: the user pastes a repo URL at runtime.

## Architecture

Four layers, top-down:

```
┌─────────────────────────────────────────────────────────────┐
│  Compose UI / Phase 3 product (browse, details, reader)    │  Kotlin
├─────────────────────────────────────────────────────────────┤
│  LnPluginHost.kt   — facade with suspend popularNovels/... │  Kotlin
│  LnPluginLoader.kt — downloads + caches plugin .js         │  Kotlin
├─────────────────────────────────────────────────────────────┤
│  LnHostBridge.kt   — @JavascriptInterface; OkHttp fetches   │  Kotlin ↔ JS
├─────────────────────────────────────────────────────────────┤
│  bootstrap.js      — _require() resolver, @libs/* shims    │  JS in WebView
│  vendor/*.js       — cheerio, htmlparser2, dayjs bundles   │  JS in WebView
│  upstream plugin   — runs unmodified, exports default      │  JS in WebView
└─────────────────────────────────────────────────────────────┘
```

The probe screen ([`LnPluginHostProbeScreen.kt`](../../app/src/main/java/yokai/presentation/novel/probe/LnPluginHostProbeScreen.kt)) sits beside the product UI as a debug-only testbench. It instantiates an `LnPluginHost`, accepts a plugin URL + an id + an options-JSON blob, and exercises the five plugin methods.

### How a popularNovels call flows

1. Probe screen → `host.popularNovels(pluginId, 1, optionsJson)` (suspend).
2. Host generates a callback id, stashes a `CancellableContinuation` keyed by it, calls `webView.evaluateJavascript("window.__lnhost.callMethod(pluginId, 'popularNovels', argsJson, callbackId)")` via the main `Handler`.
3. `bootstrap.js` `callMethod` invokes `plugin.popularNovels(1, options)` and awaits its Promise.
4. The plugin's body calls `require('@libs/fetch').fetchApi(url, init)`. The shim's `fetchApi` posts to `LnHostBridge.fetch(url, optsJson, cbId)`.
5. Kotlin runs OkHttp on `Dispatchers.IO` via `NetworkHelper.client` (Cloudflare interceptor + cookie jar inherited), serializes the response as JSON, and calls back via `webView.evaluateJavascript("window.__lnhost.resolveFetch(cbId, response)")`.
6. JS Promise resolves, plugin parses HTML (htmlparser2 / cheerio), returns the novel list.
7. `callMethod` JSON-stringifies the result and calls `LnHostBridge.resolveResult(callbackId, json)`.
8. Kotlin resumes the stashed continuation, the suspend function returns, the probe renders the JSON.

Per-call timeout: 30 s via `withTimeout(TIMEOUT_MS)`.

## Files map

Edit-touched per future work:

- **Kotlin host** ([`app/src/main/java/yokai/novel/host/`](../../app/src/main/java/yokai/novel/host/)):
  - `LnPluginHost.kt` — facade. Holds the WebView, manages plugin registration, exposes suspend methods. Owns the `mainHandler` (`Handler(Looper.getMainLooper())`) because the WebView is never attached to a window — `View.post` would queue forever.
  - `LnHostBridge.kt` — `@JavascriptInterface` class. Methods: `fetch`, `resolveResult`, `getStorage`, `setStorage`, `log`.
  - `LnPluginModels.kt` — Kotlin mirrors of lnreader types (`NovelItem`, `SourceNovel`, `ChapterItem`, `LnCallResult`, `LnPluginInfo`).
  - `LnPluginLoader.kt` — downloads `.js` from a URL via OkHttp, caches under `context.cacheDir/lnplugins/<sha256-of-url>.js`.

- **JS host** ([`app/src/main/assets/lnhost/`](../../app/src/main/assets/lnhost/)):
  - `bootstrap.html` — minimal page that loads vendor + bootstrap.js.
  - `bootstrap.js` — defines `window.__lnhost`: `_require()` resolver, `loadPlugin`, `callMethod`, `resolveFetch`, the `@libs/*` shims, the `fetchApi` wrapper (with FormData → urlencoded conversion).
  - `vendor/cheerio.min.js` — esbuild IIFE bundle (~343 KB).
  - `vendor/htmlparser2.min.js` — esbuild IIFE bundle (~116 KB).
  - `vendor/dayjs.min.js` — jsDelivr UMD (~7 KB).

- **Probe screen** ([`app/src/main/java/yokai/presentation/novel/probe/LnPluginHostProbeScreen.kt`](../../app/src/main/java/yokai/presentation/novel/probe/LnPluginHostProbeScreen.kt)) and its bridge controller ([`debug/LnPluginHostProbeController.kt`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/debug/LnPluginHostProbeController.kt)). Entry point: open the app → settings → Debug menu → "LN plugin host probe". Debug-builds-only.

Read-only references:
- [`COMPATIBILITY.md`](../../app/src/main/assets/lnhost/COMPATIBILITY.md) — soak results, what works, what doesn't.
- [`refs/lnreader-main/src/plugins/pluginManager.ts`](../../refs/lnreader-main/src/plugins/pluginManager.ts) — the upstream lnreader resolver our `_require` mimics. Match its shape when adding `@libs/*` modules.
- [`refs/lnreader-plugins/`](../../refs/lnreader-plugins/) — upstream plugin sources (TypeScript). The compiled `.js` files used at runtime live on the `plugins/v3.0.0` branch of that repo under `.dist/`.

## The probe screen

Quick reference for testing against any registry plugin:

1. Find the plugin's compiled `.js` URL: `https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json`, look up the entry by `id`, grab its `url` field.
2. The plugin id field in the probe **must match the registry's `id`**, not the URL filename. The probe auto-derives from filename which often differs (e.g. `Bookhamster%5Bifreedom%5D` vs `bookhamster`); fix it manually.
3. The popularNovels options-JSON field needs to match the plugin's `filters` shape. Without per-key defaults, many plugins crash inside their own `popularNovels` body. Read the plugin's compiled source for `this.filters={...}` to know what to pass.
4. Output area is selectable — long-press to copy JSON / error stacks.

Logcat filter while soaking:
```
package:eu.kanade.tachiyomi.debugY2k & (tag:LnHost | tag:chromium)
```

## Adding a new @libs/* shim or vendor bundle

When a tested plugin imports something we don't have:

1. **Locate the upstream definition** in `refs/lnreader-main/src/plugins/helpers/` (for `@libs/*`) or its `package.json` (for npm deps).
2. **For a small `@libs/*` (pure JS data or trivial functions)**: add the implementation inline in `bootstrap.js`'s package map. Examples: `@libs/novelStatus`, `@libs/filterInputs`, `@libs/isAbsoluteUrl`.
3. **For Kotlin-backed `@libs/*`**: extend `LnHostBridge` with new `@JavascriptInterface` methods. The shim object on the JS side posts to the bridge and either expects a synchronous return value (rare; OK for storage-style lookups) or a Promise-based async response (use the same callback-id pattern as `fetch`).
4. **For an npm dep**: bundle as IIFE via esbuild and place in `assets/lnhost/vendor/`. Follow the cheerio / htmlparser2 pattern in git history — `npm install`, write a tiny entry file that does `globalThis.X = require('X')`, run `npx esbuild --bundle --format=iife`. dayjs ships UMD directly so no bundling step.

After any shim addition, run the regression: the smallest validated source from the soak (`novelbin`) should still pass all five methods.

## Common pitfalls (observed during the soak)

- **WebView must be created with an Activity context, not `applicationContext`.** Application context silently breaks asset loading; `onPageFinished` never fires; every JS call times out. See commit `5663e2f5f`.
- **`View.post` on an unattached WebView never fires.** Our probe WebView is created off-screen and never attached to a window, so the View's deferred run queue holds runnables forever. Always dispatch via `Handler(Looper.getMainLooper())`. See commit `a7cf0613c`.
- **The bridge accepts only string bodies.** Plugins that pass `FormData` or `URLSearchParams` hit `JSON.stringify(formData) === '{}'`. The shim's `fetchApi` converts both to `application/x-www-form-urlencoded` before posting to Kotlin. Multipart with `File`/`Blob` values is still unsupported. See commit `e6f632944`.
- **Plugin id field must match the registry's `id`**, not the URL filename. Phase 3's add-repo UX will eliminate this by reading `id` directly from the registry JSON. Until then, probe operators have to type it manually.
- **Cloudflare Turnstile breaks** for the same reason it breaks Tachiyomi manga sources: the auto-bypass interceptor times out on interactive challenges. Two soak sources hit this (webnovel, boxnovel). Fix is product work: an "open source in WebView to clear challenge" affordance that lets the user solve Turnstile once, after which the `cf_clearance` cookie persists in `AndroidCookieJar`.
- **AO3 server-side rate-limits aggressively.** First popular OK, repeated calls 30-second-time-out. Real-world plugins need debounce / retry-with-backoff at the product layer.
- **Probe screen UI**: lift `verticalScroll` to the outer `Column` (`Modifier.fillMaxSize().verticalScroll(...)`), not the inner output `Text` — `Text` has no bounded height to scroll within. Wrap the output `Text` in `SelectionContainer` so long-press select + copy works.

## Phase 3 roadmap

Concrete sequence to build on top of the validated host:

1. **`NovelSource` Kotlin interface** — mirror of `HttpSource` for novels. Methods: `popularNovels(page)`, `searchNovels(query, page)`, `getNovelDetails(novel)`, `getChapterList(novel)`, `getChapterText(chapter)`, plus `filters` and `imageRequestInit` metadata. Lives somewhere like `source/api/.../novel/NovelSource.kt`.
2. **`LnPluginSource(host, pluginInfo)` adapter** — implements `NovelSource` by delegating to `LnPluginHost` calls. One adapter instance per installed plugin.
3. **`NovelSourceManager`** — parallel to existing `SourceManager`. Holds installed `NovelSource` instances keyed by id.
4. **Plugin registry parser** — JSON deserialization of lnreader's `plugins.min.json` shape (matches `LnPluginInfo` mostly; add `lang`, `iconUrl`, `customJS`, `customCSS`).
5. **Add-repo Compose screen** — parallel of [`ExtensionRepoScreen.kt`](../../app/src/main/java/yokai/presentation/extension/repo/ExtensionRepoScreen.kt). User pastes a repo URL, app fetches the index, lists available sources, install downloads each plugin's `.js` to the cache.
6. **Database `content_type` column** — start with adding it to the existing `mangas` table (smallest diff, semantic fudges acceptable for MVP). Migrate to parallel `novels` / `novel_chapters` tables later if the surface area justifies the migration. See [docs/dev/settings-compose-migration.md](settings-compose-migration.md) for migration patterns.
7. **Library tab / browse / details / chapter list** — Compose screens; reuse existing manga shells where possible, content-type filter at the query layer.
8. **Text reader UI** — brand-new Compose screen. No `PageLoader`; instead a `ChapterText` fetcher. Font / theme / size controls, scroll-percent progress, bookmark, chapter navigation. Reuses chapter-history and tracking-update machinery.
9. **Tracking media-type** — AniList and MyAnimeList have separate `media_type: NOVEL` filters; pass content type through tracker API payloads. Track table itself stays content-agnostic.
10. **Cloudflare-clear UX** — the "open source in WebView" affordance for sources blocked by Turnstile. Same UX could serve age-gate cookies (AO3's `view_adult`).
11. **Backup proto extension** — add `content_type` field to `BackupManga` proto (default value = 1 = MANGA so old backups restore correctly).

Items deferred until a real plugin demands them: `@libs/aes` real implementation (`@noble/ciphers` IIFE bundle), non-UTF-8 `fetchText` decoding, multipart bodies with File/Blob values, `fetchProto`, `customJS` / `customCSS` per-plugin scripts.

## Verification at the end of any host-side change

1. `./gradlew :app:compileDevDebugKotlin` clean.
2. `./gradlew :app:testDevDebugUnitTest --tests "yokai.domain.preference.PreferencesKeyUniquenessTest"` green.
3. Open the probe, paste the novelbin URL, run all five methods. Baseline regression.
4. If the change targets a specific shim or vendor library, also run the source that originally surfaced the need (e.g. scribblehub for FormData, bookhamster for Cyrillic, royalroad for `@libs/storage`).
5. Strip any per-step diagnostic `Logger.i` calls before committing — keep only the `lnhost bootstrap ready` baseline log, the `loaded plugin <id>` summary, and error-path logs. Bootstrap-side: keep the cheerio/htmlparser2/dayjs vendor-presence sanity check and remove anything finer.

## Cold-start checklist for tomorrow

- [ ] `git fetch && git checkout feat/ln-plugin-host-spike && git pull` to be on the latest spike state.
- [ ] Read [`COMPATIBILITY.md`](../../app/src/main/assets/lnhost/COMPATIBILITY.md) to recall what's validated and what's open.
- [ ] Read this doc's **Phase 3 roadmap** section. Pick one numbered item as the next branch of work.
- [ ] Open task `P3-1` in the active task list (set during last session's wrap). It's parked as `pending` waiting for a plan-mode session.
- [ ] Enter plan mode for Phase 3 step 1 (`NovelSource` interface) or whichever item the day starts with.
