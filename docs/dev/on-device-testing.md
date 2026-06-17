# Driving the Reikai debug app on-device (adb) + LN-plugin debugging

A practical recipe for a Claude Code session to install, drive, screenshot, and read logs from the
Reikai **debug** app on a physical device, with a focus on debugging light-novel (LN) plugins after
updating them. Everything here is run through the **PowerShell tool** (NOT the Bash tool: Bash hits a
loopback failure for Gradle and mangles `/sdcard`-style paths via MSYS).

## 0. Facts you need first

- **adb:** `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` (there's a `sqlite3.exe` beside it for DB pulls).
- **Debug package:** `eu.kanade.tachiyomi.debugY2k` (the instrumented test package is `…debugY2k.test`).
- **Build + install** (JDK not on PATH, set JAVA_HOME inline): `$env:JAVA_HOME="$HOME\scoop\apps\temurin17-jdk\current"; & ".\gradlew.bat" :app:installDebug --offline --console=plain`. Compile-only: `:app:compileDebugKotlin`.
- **Confirm the device + that the app is installed:** `& $adb devices` and `& $adb shell pm path eu.kanade.tachiyomi.debugY2k`.
- **Screen geometry is device-specific.** Get it with `& $adb shell wm size` (the reference Z Fold reports `1856x2160`). You need the real resolution because a screenshot you read back is downscaled, so tap coordinates must be mapped against the REAL resolution (see §2/§3).
- **Foldables freeze the inner display when folded/asleep.** Always wake first (§1) or `screencap` returns a stale frozen frame and `input` may not land.

Define `$adb` once per command block: `$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`.

## 1. Wake + launch

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb shell monkey -p eu.kanade.tachiyomi.debugY2k -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
```

`monkey … LAUNCHER 1` reopens the app on its last screen. Installing a fresh APK restarts it.

## 2. Screenshots (binary-safe capture + map coordinates)

`screencap` to a file on the device is unreliable here, and PowerShell's `>` corrupts binary. Capture
to a local file via `Start-Process -RedirectStandardOutput`, then strip any bytes before the PNG magic
(`\x89PNG`): on a multi-display foldable, `screencap` prepends a `[Warning] Multiple displays…` line.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$raw = "E:\Code\yokai-y2k\app\.shot_raw.png"; $clean = "E:\Code\yokai-y2k\app\.shot.png"
Start-Process -FilePath $adb -ArgumentList 'exec-out','screencap','-p' -RedirectStandardOutput $raw -NoNewWindow -Wait
$bytes = [System.IO.File]::ReadAllBytes($raw)
$idx = -1
for ($i=0; $i -lt $bytes.Length-4; $i++) { if ($bytes[$i] -eq 0x89 -and $bytes[$i+1] -eq 0x50 -and $bytes[$i+2] -eq 0x4E -and $bytes[$i+3] -eq 0x47) { $idx=$i; break } }
if ($idx -ge 0) { [System.IO.File]::WriteAllBytes($clean, $bytes[$idx..($bytes.Length-1)]) } else { Copy-Item $raw $clean }
"png_at=$idx"
```

Then **Read** `$clean` (the Read tool renders the PNG). Clean up the temp files when done
(`Remove-Item` each path individually; a multi-path `Remove-Item` can trip a sandbox guard).

**Coordinate mapping (critical):** the screenshot you view is downscaled (~4x on the Z Fold), but
`input tap` uses REAL device pixels. Estimate the target as a FRACTION of the image, then multiply by
the real `wm size`. Example: a button at ~27% width / ~46% height on a 1856x2160 screen → tap
`(0.27*1856, 0.46*2160)` = `(501, 994)`. If a tap does nothing, your coordinates are usually the issue
(re-estimate the fraction), not the app. **This fraction estimate is the fallback: for anything with a
text label or icon description, get exact bounds via `uiautomator dump` instead (§3).**

**Image-read size limit:** a full 1856x2160 screenshot occasionally exceeds the image API limit when
several images are read in one turn. If a Read fails on size, crop + downscale a region with
`System.Drawing` (`Add-Type -AssemblyName System.Drawing`; `Graphics.DrawImage` into a smaller Bitmap)
and read the crop, e.g. to zoom a toolbar/empty-state message.

## 3. Driving input

```powershell
& $adb shell input tap <x> <y>                       # tap (real px)
& $adb shell input swipe <x1> <y1> <x2> <y2> <ms>     # swipe / scroll / long-press (same start=end, ~600ms)
& $adb shell input text "query"                       # type into a focused field
& $adb shell input keyevent KEYCODE_BACK              # back (dismisses keyboard first, then closes UI)
```

- **Long-press** (e.g. library multi-select): `input swipe x y x y 600` (same point, ~600ms hold).
- **Pull-to-refresh does NOT fire via `input swipe`** on Compose `pullRefresh`. Use the in-app action
  instead (e.g. a details overflow menu "Refresh"). This bites every time, do not chase the swipe.
- Type after tapping the search icon so the field has focus; `KEYCODE_BACK` once hides the keyboard,
  again closes the search bar.

### Precise tapping (the fix for Fold 6 miss-taps)

Eyeballing a fraction of the downscaled screenshot is the main source of missed taps. When the target
has a text label or an icon content-description, get its EXACT bounds from `uiautomator dump` (which
reports real device pixels) and tap the center. The dump is also always live, unlike a folded-display
`screencap`, so it doubles as a staleness check.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
$ui = (& $adb shell cat /sdcard/ui.xml) -join "`n"
$target = "Novel Bin"   # visible text OR an icon's content-desc, e.g. "Search", "More options"
$rx = [regex]::new('(?:text|content-desc)="([^"]*' + [regex]::Escape($target) + '[^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$m = $rx.Match($ui)
if ($m.Success) {
    $cx = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    $cy = ([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2
    & $adb shell input tap $cx $cy
    "tapped '$($m.Groups[1].Value)' at $cx,$cy"
} else { "NOT FOUND: $target  (read the dumped XML; the node may have no text/desc, or be merged)" }
```

Rules that kill the miss-taps:

- **Locate by text/desc, not by eye.** Use the screenshot to decide WHAT to tap; use the dump to get WHERE. Reserve the fraction-of-real-resolution method (§2) for bare elements with no text/desc, and always verify those.
- **Re-dump after every navigation.** Bottom sheets, dialogs, and tab rows change height, so a coordinate from the previous screen is stale. This caused most of the misses this session: a filter-sheet tab row sat at a different Y depending on how tall the active tab's content was.
- **Confirm, don't chain.** After a tap, screenshot or re-dump and verify the expected change before the next tap. A blind chain turns one bad coordinate into a wrong end state several steps later.
- **Aim at row centers.** List rows are fully clickable; tap the row center (a large target) rather than a small chevron or checkbox, unless you specifically need that control.
- If `uiautomator dump` prints "could not get idle state", the UI is still animating: wait ~500ms and retry (it usually still writes the file).

The regex assumes `text`/`content-desc` precedes `bounds` in a node (uiautomator emits `bounds` last,
so it holds). Compose can merge or omit semantics on some nodes; if a target isn't found, read the
dumped XML to find the real text/desc/bounds, or fall back to the §2 fraction method.

## 4. logcat — the main tool for LN-plugin debugging

LN plugins run in a headless QuickJS host (`LnPluginHost` / `LnHostBridge`); all source HTTP goes
through OkHttp. logcat is how you see what an updated plugin actually does. The pattern: **clear,
act, dump**.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c                                   # clear
# … now drive the app: open the source / a novel / a chapter …
& $adb logcat -d 2>&1 | Select-String -Pattern "LnHost|plugin cache|OkHttp|FATAL|Exception"
```

What to look for:

- **`LnHostBridge: loaded plugin <id> v<ver>`** and **`lnhost headless runtime ready; vendor present: …`** confirm the host loaded the (updated) plugin. `UndispatchedCoroutine: plugin cache hit <hash>.js for <github-raw-url>` shows exactly which plugin JS bundle loaded and from where, useful to confirm your update is the one running (the host caches by content hash, so a stale cache = old code).
- **`OkHttp: --> GET <url>`** then **`OkHttp: <-- <code> <url> (Nms)`** are the plugin's network calls (`parseNovel` / `parsePage` / `parseChapter`). This is the highest-signal view when debugging a plugin: you see every URL it builds and the response code. `<-- 200` = ok; `<-- 403` = blocked (often Cloudflare / a wrong User-Agent / a search endpoint that refuses the client); a missing GET means the plugin isn't requesting what you expect (selector/path bug).
- **JS / parse errors** surface in logcat as exceptions; filter by the plugin name plus `Exception`, or widen the pattern. A plugin that loads but returns nothing usually shows a 200 with no follow-up sync, pointing at a parsing (cheerio selector) problem in the plugin JS.
- **`FATAL EXCEPTION` / `AndroidRuntime: FATAL`** = an app crash (rare from plugin work; count them with `… | Select-String "FATAL EXCEPTION|AndroidRuntime: FATAL" | Measure-Object`).
- Reikai sends the **device WebView User-Agent** on host fetches (some sources serve different HTML/covers to a generic UA). If a plugin's covers or pages differ from LNReader, suspect the UA or a Cloudflare gate, not the parser.

To debug an updated plugin end to end: install/refresh it (Browse → Extensions → Novels chip; the
host `ensureLoaded()` picks it up), then **browse the source** (popular/latest + search), **open a
novel** (`parseNovel`, and page 2+ for paginated sources via the page selector), and **open a
chapter** (`parseChapter`), watching the OkHttp + LnHost lines at each step.

## 5. Headless plugin test — the fast way to triage updated LNReader plugins

`reikai.novel.host.HeadlessJsIntegrationTest#lnPluginsRunInProductionHeadlessHost` loads plugins in the
production QuickJS host (no WebView, no UI, no Activity) and runs the full **search → parseNovel →
parseChapter** chain. It's the quickest "did an updated plugin break loading or parsing" check, with no
screenshotting or tapping.

What it actually does ([HeadlessJsIntegrationTest.kt](../../app/src/androidTest/java/reikai/novel/host/HeadlessJsIntegrationTest.kt)):

- Fetches the **published** LNReader registry (`…/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json`), so it tests plugins as published on the `v3.0.0` branch, NOT local edits (see the local-edits note below).
- Samples 5 anchors (`novelhall`, `scribblehub`, `novelbin`, `wuxiaworld`, `WTRLAB`) plus up to 30 more English plugins (`SAMPLE_SIZE`), searches up to 12 (`SEARCH_CAP`), full-chains up to 6 (`FULL_CHAIN_CAP`).
- Asserts every fetched plugin LOADS and at least one completes the full chain. Search-time site 404s / Cloudflare blocks are tolerated (environmental, not engine faults).

Run it (install the app + the androidTest APK, then instrument just the LN method):

```powershell
$env:JAVA_HOME="$HOME\scoop\apps\temurin17-jdk\current"
& ".\gradlew.bat" :app:installDebug :app:installDebugAndroidTest --offline --console=plain
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am instrument -w -e class reikai.novel.host.HeadlessJsIntegrationTest#lnPluginsRunInProductionHeadlessHost eu.kanade.tachiyomi.debugY2k.test/androidx.test.runner.AndroidJUnitRunner
& $adb logcat -d -s HeadlessJsTest:I       # the per-plugin breakdown
```

(If CLI `connectedAndroidTest` can't fetch UTP artifacts offline, the `installDebugAndroidTest` +
`am instrument` path above still works; or run the test from Android Studio.)

Read the per-plugin report from **logcat tag `HeadlessJsTest`** (the `am instrument` stdout only shows
overall pass/fail). The report line for a plugin tells you which layer broke:

- `LOAD FAIL <id>: <msg>` — the JS didn't load in the host at all = an **engine/polyfill gap** (the plugin uses a browser global the headless host doesn't shim). Most serious; see the `ln-host-polyfill-parity` memory.
- `search ERROR` / `parseNovel ERROR` / `parseChapter ERROR` — loads but throws at runtime = a **logic/selector bug** in the plugin JS (or the site changed).
- `search -> 0 results`, or `parseNovel -> … chapters=0`, or `parseChapter -> 0 chars` — loads and runs but parses nothing = a **selector mismatch or a site block**. Cross-check with §4: a `<-- 403` means blocked; a `<-- 200` with empty output means the parser/selector is wrong.

**To debug a SPECIFIC updated plugin** that the sample might skip: add its registry `id` to the
`anchorIds` list at [HeadlessJsIntegrationTest.kt:61](../../app/src/androidTest/java/reikai/novel/host/HeadlessJsIntegrationTest.kt:61) so it is always fetched, loaded, searched, and chained; rebuild the androidTest APK and re-run. (Test-only edit, don't ship it.)

**Local / forked plugin edits:** the test pulls the **published** v3.0.0 registry, so editing a plugin
in a local `refs/lnreader-plugins` clone is NOT picked up here. To exercise local plugin code, host it
(push to a fork or serve the repo), add that repo in-app (Browse → Extensions → Novels → overflow →
Repos), install the plugin, then drive it through the UI (§1-§3) while watching logcat (§4). The
headless test is for triaging the published set; the UI + logcat path is for iterating on edits.

## 6. Inspecting the on-device DB (when logcat isn't enough)

No on-device `sqlite3`. Pull the DB binary-safe and query locally. Binary pulls must use the **Bash**
tool (relative remote path; PowerShell `>` corrupts binary), then `sqlite3.exe` locally:

```bash
adb exec-out run-as eu.kanade.tachiyomi.debugY2k cat databases/tachiyomi.db > local.db
# pull the -wal and -shm alongside it (WAL-aware), or VACUUM INTO a single file on-device first
```

Novel rows live in `novels` / `novel_chapters` (chapters carry a `page` transport tag for paginated
sources). See the `reference_adb_db_access` and `p5-ln-staging` memories for the edit-and-push-back
flow and the pagination `page`-column gotcha.

## 7. Quick gotchas

- PowerShell tool for all adb (not Bash), except the binary DB `cat` pull (Bash).
- Wake the foldable before any screencap/input.
- **Tap by exact bounds from `uiautomator dump` (§3), not by eyeballing screenshot fractions** — this is the single biggest miss-tap fix. Re-dump after each navigation; confirm each tap landed before the next.
- Map tap coords against the real `wm size` only as a fallback for unlabeled elements.
- Pull-to-refresh: use an in-app action, not `input swipe`.
- Strip the `[Warning] Multiple displays` prefix to the PNG magic before reading a screenshot.
- Crop + downscale if a full screenshot exceeds the image-read size limit.
- Plugin caches by content hash: if your update doesn't seem to run, confirm the `plugin cache hit`
  line points at the new bundle (a reinstall / repo re-fetch refreshes it).
