# LN plugin host compatibility notes

Snapshot of what the WebView-backed lnreader plugin host can and can't run, based on
on-device soak against `lnreader-plugins` v3.0.0. Used as input for the Phase 3 NovelSource
API design.

## Soak results

| Source | Imports | Outcome |
|---|---|---|
| `novelbin` | fetch, novelStatus, cheerio, htmlparser2 | ✅ All five methods. Baseline. |
| `royalroad` | fetch, filterInputs, isAbsoluteUrl, novelStatus, storage, htmlparser2 | ✅ All five. First real `@libs/storage` exercise. First `ExcludableCheckboxGroup` filter shape. |
| `scribblehub` | fetch, filterInputs, cheerio, dayjs | ✅ All five (after `FormData` shim, see below). First real `cheerio.load()` execution. First dayjs UMD usage. |
| `webnovel` | fetch, filterInputs, storage, cheerio | ⚠️ Load works; popular blocked by Cloudflare Turnstile (auto-bypass times out; same limitation Tachiyomi has against CF-protected manga sources). Not a host bug. |
| `archiveofourown` | defaultCover, fetch, filterInputs, cheerio | ⚠️ Load works. First popular OK (validates `CheckboxGroup` filter pass-through). parseNovel + repeated popular hit AO3 server-side rate limiting (HTTP/2 stream timeout, no headers within 30 s). Likely also needs `view_adult` cookie for explicit works. |
| `novelfull` | fetch, novelStatus, cheerio, htmlparser2 | ✅ All five. Same `readnovelfull` template family as novelbin. |

## Host changes made during the soak

- `feat(novel): expose popularNovels options JSON field` — probe screen needs per-source filter defaults; calling `popularNovels(1, {})` crashes plugins that read `options.filters.X.value`.
- `fix(novel): pass Activity context to WebView; surface load errors` — `applicationContext` silently breaks asset loading; `onPageFinished` never fires.
- `fix(novel): dispatch JS calls via main Looper, not webView.post` — `View.post` queues runnables in a deferred queue until the view attaches to a window. The probe's WebView is never attached, so the queue never drained. Every Kotlin → JS call hung until timeout.
- `feat(novel): convert FormData and URLSearchParams bodies in fetchApi shim` — bridge accepts only string bodies; `JSON.stringify(formData)` gives `'{}'`. scribblehub's `parseNovel` POSTs `FormData` for chapter pagination. Conversion to `application/x-www-form-urlencoded` covers the string-keyed-string-value case (no File/Blob support yet).
- `fix(novel): make the probe screen vertically scrollable` — outer Compose column was `fillMaxSize` without scroll; once inputs + buttons + output overflowed the viewport, lower fields and the output area were unreachable.

## `@libs/*` coverage

| Module | Status |
|---|---|
| `@libs/fetch` (`fetchApi`, `fetchText`, `fetchProto`) | `fetchApi` and `fetchText` implemented. `fetchProto` stub throws. No plugin in the soak set used `fetchProto`. |
| `@libs/filterInputs` (`FilterTypes` enum) | ✅ Data constants. |
| `@libs/novelStatus` (`NovelStatus` enum) | ✅ Data constants. |
| `@libs/defaultCover` (string) | ✅ AO3 references it; works. |
| `@libs/storage` (`storage`, `localStorage`, `sessionStorage`) | ✅ Backed by per-plugin `SharedPreferences`. Royal Road / webnovel exercise `.get` at init. `.set` and persistence-across-process untested but the implementation is trivial. |
| `@libs/isAbsoluteUrl` (`isUrlAbsolute`) | ✅ Tiny regex shim. Royal Road imports but smoke-test passed. |
| `@libs/utils` (`utf8ToBytes`, `bytesToUtf8`) | ✅ TextEncoder/TextDecoder. Not exercised by any soak source. |
| `@libs/aes` (`gcm`) | ❌ Stub Proxy throws on use. No soak source needed it; add `@noble/ciphers` vendor bundle when one does. |

## npm packages (vendor bundles)

| Package | Source | Size | Status |
|---|---|---|---|
| `cheerio` 1.0.0 | esbuild IIFE bundle (local) | 343 KB | ✅ Validated by scribblehub. |
| `htmlparser2` 9.1.0 | esbuild IIFE bundle (local) | 116 KB | ✅ Validated by novelbin, royalroad, novelfull. |
| `dayjs` 1.11.13 | jsDelivr UMD | 7 KB | ✅ Validated by scribblehub. |
| `urlencode` | hand-rolled `encodeURIComponent`/`decodeURIComponent` shim | inline | Not exercised by soak (no plugin imported it). |
| `lodash-es` | not bundled | — | Not imported by any soak source. Add when needed. |
| `@noble/ciphers` | not bundled | — | Couples with `@libs/aes`; add together when needed. |
| `protobufjs` | not bundled | — | Couples with `fetchProto`. |

## Known limitations (for Phase 3)

These are real-world plugin issues the host doesn't currently solve. Each is a Phase 3 product concern, not a host shim fix.

- **Cloudflare Turnstile** (webnovel.com, others): `NetworkHelper.client` includes a `CloudflareInterceptor` that auto-solves classic CF challenges. Turnstile requires user interaction; the interceptor times out. Need an "open source in WebView to clear challenge" UX, mirroring how the manga side handles this; the `cf_clearance` cookie persists in `AndroidCookieJar` once obtained.
- **Server-side rate-limit** (AO3): no retry / backoff in the host. Plugins (and Phase 3 UI) should debounce repeated calls and surface a retry-friendly error.
- **Age-gate cookies** (AO3 `view_adult`): some sources gate adult content behind a one-click cookie. Likely needs the same WebView-clear flow as Cloudflare.
- **Non-UTF-8 response decoding**: `fetchText`'s `encoding` arg is ignored; the bridge always returns body as UTF-8. Add base64 transport + `TextDecoder(encoding)` on the JS side when a Big5 / GBK / Shift-JIS source enters the test set.
- **Multipart bodies with File/Blob values**: `FormData` shim only handles string values. File uploads (likely none for LN sources, but flagged anyway) would need real multipart encoding in Kotlin.
- **Cookie persistence across calls**: `AndroidCookieJar` is shared by `NetworkHelper.client`, so cookies set during one fetch should carry into the next. Unverified by the soak (no session-required source completed end-to-end).

## What the host is ready for

The shim contract is wide enough to load and exercise plugins that use the common slice of the lnreader plugin API. The next phase can safely:

- Design `NovelSource` mirroring the `Plugin` interface (`popularNovels`, `parseNovel`, `parseChapter`, `searchNovels`, plus `filters` and `imageRequestInit` metadata).
- Parse the `plugins.min.json` registry shape.
- Build the add-repo screen as a parallel of `ExtensionRepoScreen.kt`.
- Defer the WebView-clear UX for Cloudflare / age-gate sources until the library / browse / details screens land — those screens are also where the user would naturally interact with such sources.
