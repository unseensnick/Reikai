# Light-novel plugin host — cold-start handbook

This doc is the single-source recipe for picking up the LN feature after a break. A fresh session reading this from scratch should be able to navigate the code, build and run, and pick up where the previous session left off without consulting prior conversation history.

Start with **Where things stand**, then **Architecture**, then jump to whatever section your task touches.

## Where things stand

The work lives on the **`feat/ln-plugin-host-spike` branch**. Currently 27 commits ahead of `main`. The branch bundles the host spike + compatibility soak + Phase 3 product work and ships as one cohesive PR when it's ready.

```bash
git checkout feat/ln-plugin-host-spike
git pull
```

**Phase 1** (host bridge, novelbin proof), **Phase 2** (8-source compatibility soak), and **Phase 3 items 1–8** of the original roadmap are **done**. Functional end-to-end LN reader (debug-only entry points): install a plugin from a repo → browse a source → save a novel to library → open it from the library → read a chapter with paragraph-by-paragraph rendering and scroll-progress resume across launches.

Per-plugin soak status: [`app/src/main/assets/lnhost/COMPATIBILITY.md`](../../app/src/main/assets/lnhost/COMPATIBILITY.md). Read that first when designing further work — it captures every known gap, every `@libs/*` method's status, every limitation the host doesn't solve.

## User-facing flow

LN entry points live exclusively under **Settings → Light novels (Beta)** during the design-prototype + Compose-Library-foundation work. The previous first-cut bottom-nav Novels tab was rolled back because the upcoming unified Library (manga + novels in one Compose surface, content-type filter chip) supersedes it. See the Library Compose foundation plan for the new direction.

| Settings → Light novels (Beta) entry | Purpose |
|---|---|
| **LN plugin host probe** | Bridge testbench. Paste plugin .js URL, exercise the four lnreader methods. Used for soaking new shim additions. |
| **LN plugin repo browse** | Paste a registry URL (lnreader's `plugins.min.json`), list available plugins, install / uninstall per row. Persists URLs in `NovelPreferences.installedPluginUrls`. |
| **LN browse** | Pick an installed source, popularNovels list, tap novel for parseNovel + chapter list + Save-to-library toggle, tap chapter for paragraph reader. 4-state machine, internal back-stack. |
| **LN library** | Saved-novels list (`NovelLibraryScreen`). Renders `NovelLibraryContent` with a back-arrow Scaffold. Will be replaced by the unified Library surface in the Compose Library work. |
| **LN track probe** | AniList / MAL / Kitsu NOVEL search + `novel_tracks` bind/read panel. No production placement yet; novel-detail tracker UI is a later slice. |

## Why this exists

Reikai is a Kotlin/Android manga reader (Tachiyomi / Mihon lineage). The goal is a unified manga + light-novel reader **without abandoning the Tachiyomi manga extension ecosystem** and **without taking on maintenance of a forked light-novel plugin tree**. We picked option B (host upstream lnreader plugins unmodified inside a WebView with Kotlin shims) over native ports (too much per-source work), a React Native rewrite (months of bridge glue), and a transpiler approach (perpetual codemod maintenance). Maintenance load is bounded: pulling new plugin code is `git pull` in the registry repo, never a code change in Reikai.

Plugin source code is **not bundled** in the APK. Same legal distance as the Tachiyomi manga extension model: the user pastes a repo URL at runtime.

## Architecture

Five layers, top-down:

```
┌────────────────────────────────────────────────────────────────┐
│  Compose UI                                                    │
│    yokai/presentation/novel/{probe,repo,browse,library,details}│  Kotlin
├────────────────────────────────────────────────────────────────┤
│  Source contract + install layer                               │
│    yokai/novel/source/{NovelSource, LnPluginSource, Manager}   │
│    yokai/novel/install/LnPluginInstaller (+ canonicalize URL)  │  Kotlin
│    yokai/novel/registry/LnRegistry                             │
├────────────────────────────────────────────────────────────────┤
│  Data (SQLDelight, parallel to mangas/chapters)                │
│    data/.../sqldelight/.../data/{novels,novel_chapters}.sq     │
│    migrations/32.sqm                                           │  Kotlin / SQL
│    domain/.../yokai/domain/novel/* (Novel, NovelChapter, repos)│
│    app/.../yokai/data/novel/* (impls, mappers)                 │
├────────────────────────────────────────────────────────────────┤
│  Plugin host facade                                            │
│    yokai/novel/host/LnPluginHost  — facade, suspend methods    │
│    yokai/novel/host/LnPluginLoader — download + cache .js      │
│    yokai/novel/host/LnHostBridge  — @JavascriptInterface       │  Kotlin ↔ JS
├────────────────────────────────────────────────────────────────┤
│  JS in WebView                                                 │
│    assets/lnhost/bootstrap.js — _require + @libs/* shims       │
│    assets/lnhost/vendor/{cheerio,htmlparser2,dayjs}.js         │  JS
│    upstream plugin .js (runs unmodified, exports default)      │
└────────────────────────────────────────────────────────────────┘
```

### How a popularNovels call flows

1. Compose screen → `source.popularNovels(page, optionsJson)` (where `source` is an `LnPluginSource` adapter).
2. Adapter delegates to `LnPluginHost.popularNovels(pluginId, page, optionsJson)`.
3. Host generates a callback id, stashes a `CancellableContinuation` keyed by it, calls `webView.evaluateJavascript("window.__lnhost.callMethod(...)")` via the main `Handler`.
4. `bootstrap.js` `callMethod` invokes `plugin.popularNovels(page, options)` and awaits its Promise.
5. The plugin's body calls `require('@libs/fetch').fetchApi(url, init)`. The shim's `fetchApi` posts to `LnHostBridge.fetch(url, optsJson, cbId)`.
6. Kotlin runs OkHttp on `Dispatchers.IO` via `NetworkHelper.client` (Cloudflare interceptor + cookie jar inherited), serializes the response as JSON, and calls back via `webView.evaluateJavascript("window.__lnhost.resolveFetch(cbId, response)")`.
7. JS Promise resolves, plugin parses HTML (htmlparser2 / cheerio), returns the novel list.
8. `callMethod` JSON-stringifies the result and calls `LnHostBridge.resolveResult(callbackId, json)`.
9. Kotlin resumes the stashed continuation, the suspend function returns, Compose state updates.

Per-call timeout: 30 s via `withTimeout(TIMEOUT_MS)`.

### How a reader chapter flow works

1. User taps chapter in `NovelDetails` (browse or library entry — both screens use the same composable).
2. `loadChapterForReading(source, novel, chapter, novelRepo, chapterRepo)` runs:
   - Upserts a `novels` row (favorite=false if not already saved) — anchor for chapter FK.
   - Upserts a `novel_chapters` row — anchor for `last_text_progress`.
   - Calls `source.parseChapter(path)` → raw HTML.
   - Runs `htmlToParagraphs(html)` (Jsoup-based; `<p>` tags primary, blank-line splitting fallback).
3. Returns a `ChapterRead(chapterId, initialProgress, paragraphs)`.
4. `ChapterReader` Composable renders paragraphs in a `LazyColumn`; on `LaunchedEffect` scrolls to `initialProgress` (0..10000 → paragraph index).
5. Auto-save: `snapshotFlow { lazyListState.firstVisibleItemIndex } |> debounce(1s) |> distinctUntilChanged()` writes `chapterRepo.setLastTextProgress(id, percent)`. No back-stack save needed; the latest debounced write is the persisted value.

## Files map

### Kotlin

| Path | Purpose |
|---|---|
| `app/src/main/java/yokai/novel/host/` | WebView host: `LnPluginHost`, `LnPluginLoader`, `LnHostBridge`, `LnPluginModels` |
| `app/src/main/java/yokai/novel/install/` | `LnPluginInstaller` (install/uninstall/loadInstalled/fetchRepo) + `canonicalizePluginUrl` |
| `app/src/main/java/yokai/novel/registry/` | `LnRegistry.parse()` + `LnRegistryEntry` DTO |
| `app/src/main/java/yokai/novel/source/` | `NovelSource` (interface), `LnPluginSource` (adapter), `NovelSourceManager` (in-memory registry) — note: source manager is in `yokai/novel/source/` while the same-purpose Manga `SourceManager` lives in `eu/kanade/tachiyomi/source/`; they're disjoint |
| `app/src/main/java/yokai/novel/text/` | `htmlToParagraphs` Jsoup helper |
| `app/src/main/java/yokai/data/novel/` | Repo impls + `Mappers.kt` (SQLDelight row → domain, incl. `novelTrackMapper`) + `NovelMapping.kt` (SourceNovel → Novel, ChapterItem → NovelChapter, `NovelStatusCode` enum) + `NovelTrackRepositoryImpl` |
| `domain/src/commonMain/kotlin/yokai/domain/novel/` | Repository interfaces (`NovelRepository`, `NovelChapterRepository`, `NovelTrackRepository`) + `models/{Novel,NovelChapter,NovelTrack}` |
| `app/src/main/java/yokai/domain/novel/NovelPreferences.kt` | `installedPluginUrls()` Set<String> accessor + reader prefs (`readerFontSize`, `readerLineSpacing`, `readerTheme`) |
| `app/src/main/java/eu/kanade/tachiyomi/data/track/{anilist,myanimelist,kitsu}/` | Each tracker has a tiny `*MediaType { MANGA, NOVEL }` enum + a `searchNovels` method on the tracker class. The API class branches its result filter (or GraphQL fragment for AniList) on the enum. Manga `search` paths are untouched. |
| `app/src/main/java/yokai/presentation/novel/probe/` | Debug bridge testbench |
| `app/src/main/java/yokai/presentation/novel/repo/` | Add-repo screen (`LnRepoBrowseScreen`) |
| `app/src/main/java/yokai/presentation/novel/browse/` | 4-state browse screen + shared `NovelDetails`, `ChapterReader`, `loadChapterForReading`, `buildDefaultOptions` (all `internal` so the details screen can reuse them) |
| `app/src/main/java/yokai/presentation/novel/library/` | Library list (`NovelLibraryScreen`) |
| `app/src/main/java/yokai/presentation/novel/details/` | Library-tap entry point (`NovelDetailsScreen`), 2-state machine `Loading → Viewing → Reading` |
| `app/src/main/java/yokai/presentation/novel/track/` | `NovelTrackProbeScreen`, multi-tracker (AniList / MAL / Kitsu) search + bind probe writing into `novel_tracks` |
| `app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/debug/` | One Conductor bridge controller per Compose screen, plus `DebugController` entries |

### JS assets

| Path | Purpose |
|---|---|
| `app/src/main/assets/lnhost/bootstrap.html` | Loads vendor + bootstrap.js |
| `app/src/main/assets/lnhost/bootstrap.js` | `window.__lnhost`: `_require()`, `loadPlugin` (two-pass: discovery + real), `callMethod`, `resolveFetch`, `@libs/*` shims, `fetchApi` with FormData/URLSearchParams → urlencoded |
| `app/src/main/assets/lnhost/vendor/cheerio.min.js` | esbuild IIFE bundle (~343 KB) |
| `app/src/main/assets/lnhost/vendor/htmlparser2.min.js` | esbuild IIFE bundle (~116 KB) |
| `app/src/main/assets/lnhost/vendor/dayjs.min.js` | jsDelivr UMD (~7 KB) |

### Read-only references

- [`COMPATIBILITY.md`](../../app/src/main/assets/lnhost/COMPATIBILITY.md) — soak results, what works, what doesn't.
- [`refs/lnreader-main/src/plugins/pluginManager.ts`](../../refs/lnreader-main/src/plugins/pluginManager.ts) — upstream resolver our `_require` mimics. Match its shape when adding `@libs/*` modules.
- [`refs/lnreader-plugins/`](../../refs/lnreader-plugins/) — upstream plugin source (TypeScript). Compiled `.js` lives on the `plugins/v3.0.0` branch under `.dist/`.

## DI surface

All Koin-registered, all singletons (declared in `AppModule.kt` and `DomainModule.kt`):

- `NetworkHelper` (existing) — OkHttp client used by `LnPluginLoader` + `LnPluginInstaller.fetchRepo`.
- `LnPluginLoader(app, NetworkHelper.client)` — per-app, application-context for cache dir.
- `LnPluginInstaller(NetworkHelper, LnPluginLoader, NovelSourceManager, NovelPreferences)`.
- `NovelSourceManager()` — empty until each screen's `LnPluginHost` opens and `installer.loadInstalled(host)` populates it.
- `NovelPreferences(PreferenceStore)`.
- `NovelRepository` ← `NovelRepositoryImpl(DatabaseHandler)`.
- `NovelChapterRepository` ← `NovelChapterRepositoryImpl(DatabaseHandler)`.

`LnPluginHost` is **NOT** a singleton. It's constructed per Compose screen (`remember { LnPluginHost(context, networkHelper.client) }`) because the WebView needs an Activity context and its lifecycle tracks the screen. Each screen that needs sources calls `LnPluginHost.installer.loadInstalled(host)` in a `LaunchedEffect(host)` to re-register sources into the manager bound to the current host.

## Adding a new @libs/* shim or vendor bundle

When a tested plugin imports something we don't have:

1. **Locate the upstream definition** in `refs/lnreader-main/src/plugins/helpers/` (for `@libs/*`) or its `package.json` (for npm deps).
2. **For a small `@libs/*` (pure JS data or trivial functions)**: add inline in `bootstrap.js`'s package map. Examples: `@libs/novelStatus`, `@libs/filterInputs`, `@libs/isAbsoluteUrl`.
3. **For Kotlin-backed `@libs/*`**: extend `LnHostBridge` with new `@JavascriptInterface` methods. The JS shim posts to the bridge and either expects a synchronous return (rare; OK for storage-style lookups) or a Promise-based async response (use the same callback-id pattern as `fetch`).
4. **For an npm dep**: bundle as IIFE via esbuild and place in `assets/lnhost/vendor/`. Pattern: `npm install`, write a tiny entry file that does `globalThis.X = require('X')`, run `npx esbuild --bundle --format=iife`. dayjs ships UMD directly so no bundling step needed.

After any shim addition, run the regression: the smallest validated source from the soak (`novelbin`) should still pass all five plugin methods in the host probe.

## Common pitfalls (observed during the soak + Phase 3)

- **WebView must be created with an Activity context, not `applicationContext`.** Application context silently breaks asset loading; `onPageFinished` never fires; every JS call times out. Fixed in commit `5663e2f5f`.
- **`View.post` on an unattached WebView never fires.** Our screen WebViews are created off-screen and never attached to a window, so the View's deferred run queue holds runnables forever. Always dispatch via `Handler(Looper.getMainLooper())`. Fixed in commit `a7cf0613c`.
- **The bridge accepts only string bodies.** Plugins that pass `FormData` or `URLSearchParams` hit `JSON.stringify(formData) === '{}'`. The shim's `fetchApi` converts both to `application/x-www-form-urlencoded` before posting to Kotlin. Multipart with `File`/`Blob` values is still unsupported. Fixed in commit `e6f632944`.
- **Two-pass loadPlugin keeps storage scope = plugin.id.** A first discovery pass runs with no-op `@libs/storage` shims to extract `plugin.id`; a second real pass uses that id as the storage prefix. Without this, `@libs/storage` ended up scoped by the caller-supplied id (URL-derived in practice) which would orphan storage on a URL-disjoint reinstall. See commit `4ab524568`.
- **URL canonicalization for install state.** Registry URLs leave reserved path characters literal (`NovelBin[readnovelfull].js`); historically-stored URLs were percent-encoded (`%5B...%5D`). The membership check `entry.url in installedPluginUrls` is exact equality. `canonicalizePluginUrl` in `yokai/novel/install/` forces `[` → `%5B` / `]` → `%5D` on every store / lookup path. Tested by `CanonicalizePluginUrlTest`.
- **Source identity differs from Tachiyomi's.** Tachiyomi extension source ids are `Long`. Lnreader plugin ids are `String`. The `NovelSourceManager` is keyed by `String`, disjoint from the Manga `SourceManager`'s `Long` map. Don't try to unify.
- **Cloudflare Turnstile breaks** for the same reason it breaks Tachiyomi manga sources: the auto-bypass interceptor times out on interactive challenges. Two soak sources hit this (webnovel, boxnovel). Fix is product work: an "open source in WebView to clear challenge" affordance that lets the user solve Turnstile once, after which the `cf_clearance` cookie persists in `AndroidCookieJar`.
- **AO3 server-side rate-limits aggressively.** First popular OK, repeated calls 30-second-time-out. Real-world plugins need debounce / retry-with-backoff at the product layer.
- **`buildDefaultOptions` is the cheapest way to keep `popularNovels` from crashing.** Many sources read `options.filters.X.value` and crash on `{}`. The helper walks `source.filters` and emits `{key: {value: <default>}}` for each. Used by both the browse screen and any future filter-aware UI.
- **The lightnovelplus 404** isn't a host bug; it's a real-world source-side issue (the readnovelfull template's URL pattern for that site is stale). Errors propagate cleanly from the plugin through the bridge into the screen's error message.

## Phase 3 roadmap, status

Items 1 through 8, 10, and 11 done on this branch; item 9 partially done (AniList / MAL / Kitsu NOVEL search through a debug probe). Polish items partially shipped (covers, search, reader settings).

| # | Item | Status |
|---|---|---|
| 1 | `NovelSource` interface | ✅ `yokai/novel/source/NovelSource.kt` |
| 2 | `LnPluginSource` adapter | ✅ `yokai/novel/source/LnPluginSource.kt` |
| 3 | `NovelSourceManager` | ✅ `yokai/novel/source/NovelSourceManager.kt` (StateFlow-backed; 7 tests) |
| 4 | Plugin registry parser | ✅ `yokai/novel/registry/LnRegistry.kt` (7 tests) |
| 5 | Add-repo Compose screen | ✅ `LnRepoBrowseScreen` (debug menu) |
| 6 | Database parallel tables | ✅ `novels.sq` + `novel_chapters.sq` + migration `32.sqm` (chose parallel tables over a content-type column on `mangas`) |
| 7 | Library tab + browse + details + chapter list | ✅ `NovelBrowseScreen` 4-state machine + `NovelLibraryScreen` + `NovelDetailsScreen` (library-tap entry point) |
| 8 | Text reader | ✅ `ChapterReader` Composable (Jsoup paragraph extraction + scroll-progress auto-save + chapter-row upsert) |
| 9 | Tracking media-type | 🟡 Partial. AniList + MAL + Kitsu NOVEL search wired via `searchNovels` + per-tracker `MediaType` enum; `novel_tracks` table (migration `33.sqm`) stores rows keyed by `(novel_id, sync_id)`. Validated via `LN track probe` debug screen. Add/update/remove mutations and user-facing tracker UI on the novel detail screen are still open. |
| 10 | Cloudflare-clear UX | ✅ "Open in WebView" affordance renders below the error text on `NovelBrowseScreen` (any of the four states) and on `NovelDetailsScreen` Failed state. Tapping it launches `WebViewActivity` at the source's `site` URL; cookies set there flow through `AndroidCookieJar` shared with `NetworkHelper.client`, so the next LN fetch picks them up automatically. Flaresolverr + the silent in-app challenge solver already applied to LN traffic by virtue of the shared OkHttp client; this slice just surfaces the manual escape hatch. |
| 11 | Backup proto extension | ✅ `Backup` carries `backupNovels: List<BackupNovel>` at `@ProtoNumber(200)` (unused range; safe across forks since unknown tags are dropped by kotlinx.serialization.protobuf). Each `BackupNovel` nests `chapters: List<BackupNovelChapter>` and `tracking: List<BackupNovelTracking>`. Create/restore via `NovelBackupCreator` + `NovelBackupRestorer`; restorer is idempotent on `(source, url)` and merges chapter progress + bookmark flags rather than overwriting. New "Light novels" checkbox in the Create-Backup dialog (Compose Data screen + legacy controller both pick it up via `BackupOptions.getEntries()`). Per-novel `installedPluginUrls` survives via the existing `BackupPreference` round-trip, so restored novels reload their source plugins automatically on next LN screen open. |

### Deferred trackers (gated on novel-detail tracking UI)

NovelUpdates, Shikimori, and Bangumi are not wired into the probe today even though they likely have better LN coverage than AniList / MAL / Kitsu for specific regions (NovelUpdates: broad translated catalog; Shikimori: Russian; Bangumi: Chinese-original). Reason: they only become useful once the novel detail screen has the same tracker bottom sheet the manga side has, including bind / unbind / status / progress / score. Adding more search-only chips to the probe before that lands is busywork. When item 9's user-facing UI ships and reaches parity with the manga track sheet:

- **Shikimori** (good Russian coverage): `/api/mangas` supports a `kind` query param (`light_novel,novel`). Same parameterization pattern as AniList / MAL / Kitsu; small slice.
- **Bangumi** (strongest for Chinese-original web novels): search already uses `type=1` (book) which unifies manga + LN, so the "novel" branch is just a post-filter on the subject `type` returned from the detail call. Small slice.
- **NovelUpdates** (broadest LN catalog overall): no public API. Cookie-based auth and HTML scraping required. Multi-slice integration: new tracker class, new login flow (`WebViewActivity` cookie capture), new search parser. Worth doing only if NovelUpdates remains the canonical LN tracker by the time this gets to the top of the queue.

### Polish items (not on the original roadmap)

- ✅ Card UI with covers via Coil on browse + library + details (slice J).
- ✅ Search bar on novel browse list (slice K).
- ✅ Font / line-spacing / theme controls on the reader (slice L); settings button lives in the reader's `TopAppBar` actions slot.
- 🟡 Production placement of LN entries: the first-cut bottom-nav Novels tab was reverted; LN entries live under **Settings → Light novels (Beta)** until the unified Library Compose surface lands (separate plan).
- ⏳ Compose-side Settings search integration once upstream solves it.

Items deferred until a real plugin demands them: `@libs/aes` real implementation (`@noble/ciphers` IIFE bundle), non-UTF-8 `fetchText` decoding, multipart bodies with File/Blob values, `fetchProto`, `customJS` / `customCSS` per-plugin scripts.

## Verification at the end of any host-side change

1. `./gradlew :app:compileDevDebugKotlin` clean.
2. `./gradlew :app:testDevDebugUnitTest --tests "yokai.novel.*" --tests "yokai.domain.preference.PreferencesKeyUniquenessTest"` — 19 tests should pass (4 canonicalize + 7 manager + 7 registry + 1 regression).
3. Open the host probe with novelbin URL, run all five methods. Baseline regression.
4. If the change targets a specific shim or vendor library, also run the source that originally surfaced the need (e.g. scribblehub for FormData, bookhamster for Cyrillic, royalroad for `@libs/storage`).
5. Strip any per-step diagnostic `Logger.i` calls before committing — keep only the `lnhost bootstrap ready` baseline log, the `loaded plugin <id>` summary, and error-path logs.

## Cold-start checklist

- [ ] `git fetch && git checkout feat/ln-plugin-host-spike && git pull`.
- [ ] Read [`COMPATIBILITY.md`](../../app/src/main/assets/lnhost/COMPATIBILITY.md) for soak results and known limitations.
- [ ] Skim the **Phase 3 roadmap** above. Items 9, 10, 11 plus the polish list are open. Pick one as the next slice.
- [ ] Rebuild the debug APK once before coding so all four debug-menu LN entries are present.
- [ ] Smoke test: launch app → Debug → LN library should show whatever was saved last session → tap an entry → details + chapters render → tap a chapter → paragraphs + resume work.
