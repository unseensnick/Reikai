# Testing

How to run Reikai's tests, what CI runs, the harness pieces tests are built from, and how to drive a
debug build on a device. The rules for writing a test (behaviour over implementation, one conformance
test over both content types, mutation checking) are in
[.claude/rules/testing.md](../../.claude/rules/testing.md) and
[.claude/rules/content-layer.md](../../.claude/rules/content-layer.md); this page is the how.

## Unit tests

Five modules carry JVM tests: `:app`, `:core:common`, `:data`, `:domain` and `:source-api` (each under
`src/test`). Every one is an Android module, so the tasks are AGP's: `test` runs every variant,
`testDebugUnitTest` runs only debug. The build logic puts every `Test` task on the JUnit Platform
(`configureTest` in `gradle/build-logic/src/main/kotlin/mihon/gradle/extensions/Project.kt`). The
test stack is JUnit 5 (Jupiter), Kotest assertions only (no Kotest specs) and MockK, from the `test`
bundle in `gradle/libs.versions.toml`.

```powershell
.\gradlew.bat :domain:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "reikai.domain.download.PausedNoticeConformanceTest"
.\gradlew.bat test                      # everything, as CI runs it
```

Run the one class you changed rather than the suite.

**On the maintainer's Windows machine, run Gradle through PowerShell, not Bash** (Bash fails with
"Unable to establish loopback connection"), and set the JDK on every call, since the machine-wide
`JAVA_HOME` is a JDK 17 the Metro compiler plugin rejects:

```powershell
$env:JAVA_HOME = "$HOME\.jdks\temurin-21.0.11"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

CI's JDK comes from `.github/.java-version` (21). Running the test suites and the release assemblies
in one invocation can run out of memory with an error that looks like a Kotlin `CompilationException`;
run them as separate calls. Never run CLI Gradle while Android Studio is building.

## What CI runs

| Workflow | Trigger | Checks |
|---|---|---|
| `build_check.yml` | pull request touching sources, `.kts`, `.pro` or `gradle/` | `spotlessCheck`, `scripts/di-interop-check.ps1`, `verifySqlDelightMigration`, `test` (JUnit report published), `assembleRelease` |
| `nightly.yml` | push to `main`, `feat/**`, `fix/**` on the same paths | `di-interop-check.ps1`, `verifySqlDelightMigration`, `test`, `assembleNightly`, `scripts/ci/release-notes-test.sh`, then publishes the nightly |
| `release.yml` | a `v*` tag | `test`, `assembleRelease`, `assembleFoss`, `release-notes-test.sh`, then a draft release |
| `commit-standards.yml` | every pull request | `.githooks/commit-msg` over each commit, `scripts/commit-msg-test.sh`, `.claude/hooks/tests/run-all.sh` |
| `docs-lint.yml` | pull request, push to `main`, on docs, scripts and Kotlin/SQL | `scripts/lint-docs-test.sh`, every blocking `scripts/lint-docs.sh` mode (the `history-words` warning runs only in the hook), `scripts/dup-check-test.ps1`, `scripts/dup-check.ps1` against a blobless Mihon clone |
| `dependency-availability.yml` | weekly, and a pull request touching the version catalogs | `scripts/check-external-artifacts.sh` |

No workflow runs instrumented tests; those run on a device by hand (below). The local `pre-commit`
hook runs the staged-content half of the same checks; its list is in
[.claude/rules/workflow.md](../../.claude/rules/workflow.md).

## Harness pieces

There is no shared test-fixtures module, so these live beside the tests that use them.

- **`MainDispatcherExtension`** (`app/src/test/java/reikai/presentation/`): a JUnit 5 extension that
  gives each test its own Main dispatcher (`UnconfinedTestDispatcher` by default). Register it with
  `@RegisterExtension val main = MainDispatcherExtension()` and pass each ViewModel to `track(model)`:
  Main is reset only after every tracked model's `viewModelScope` has been cancelled and joined, so a
  coroutine parked off Main by `flowOn` cannot resume into the next test.
- **In-memory SQLite:** `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)`, then
  `Database.Schema.create(driver).await()`, then `DatabaseBindings.providesDatabase(driver)`, which is
  production's database with its column adapters. Close the driver in `@AfterEach`.
  `data/src/test/java/reikai/data/track/TrackUpsertConformanceTest.kt` is a short example.
- **`SerialSqliteDriver`** (`app/src/test/java/reikai/presentation/reader/`): use it in place of the
  plain in-memory driver when a model queries from real threads. The JDBC driver shares one connection
  and one transaction across threads, which gives `SQLITE_BUSY`; this one locks the connection per
  statement and per transaction, as the device does.
- **`EmittingPreferenceStore`** (`app/src/test/java/reikai/presentation/recents/`): a preference store
  whose `changes()` flows actually emit. `InMemoryPreferenceStore` (in `:core:common` main, also used
  by previews) never emits, so code that combines preference flows produces nothing against it.
- **Restore and reader harnesses:** `MangaRestoreHarness.kt` (a `MangaRestorer` over production's
  repositories), `MangaReaderViewModelHarness.kt`, `NovelReaderViewModelHarness.kt`,
  `DownloadWorkerFixture.kt` and `LnPluginHarness.kt`, all under `app/src/test`.
- **Schema migrations with data:** `data/src/test/java/reikai/data/migration/SchemaChainMigrationTest.kt`
  copies the `43.db` snapshot, seeds rows and runs the real `.sqm` chain. Never hand-build an old schema
  ([.claude/rules/database.md](../../.claude/rules/database.md)).

### The conformance-test pattern

A rule both content types must obey is pinned by one test parameterized over a manga and a novel
adapter. The shape, from `app/src/test/java/reikai/domain/download/PausedNoticeConformanceTest.kt`:

```kotlin
@ParameterizedTest(name = "{0}")
@MethodSource("halves")
fun `a pause leaves a paused notice once its worker ends`(half: PausedNoticeHalf) = conformance(half) { ... }

companion object {
    @JvmStatic
    fun halves() = listOf(MangaPausedNoticeHalf(), NovelPausedNoticeHalf())
}
```

Each half implements a small interface that drives its own engine, and overrides `toString()` to
return `"manga"` or `"novel"` so the report names the type. Where a half needs only data (seed SQL, a
row shape), an `enum` with `@EnumSource` does the same job, as `TrackUpsertConformanceTest` does. A type
that cannot do the thing declares it unsupported in its half rather than being left out.

### Mutation checking

A new test is done only when the production clause it names has been deleted, the test seen red, and
the clause restored. There is no tool for this; do it by hand and say so in the commit. The
`/deep-audit` skill can run a few mutation checks in a throwaway worktree.

## Instrumented tests

`app/src/androidTest` holds the reader and WebView tests that need real views, layout passes or a real
WebView, plus the headless LN-plugin host test; `data/src/androidTest` holds `ForeignKeyEnforcementTest`,
which opens the driver with production's `sqlDriverConfiguration`. Both use `AndroidJUnitRunner`. These
may use `runBlocking`, bounded by real timeouts.

Prefer installing and instrumenting over `connectedAndroidTest`, which uninstalls the app (and its
library) afterwards and cannot fetch its UTP artifacts offline:

```powershell
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest --offline --console=plain
& $adb shell am instrument -w -e class reikai.presentation.reader.WebtoonResizeAfterZoomTest app.reikai.dev.test/androidx.test.runner.AndroidJUnitRunner
```

With an emulator and a phone both attached, set `ANDROID_SERIAL` so the install and the run hit the
same device.

### The headless LN-plugin test

`reikai.novel.host.HeadlessJsIntegrationTest#lnPluginsRunInProductionHeadlessHost` loads LNReader
plugins in the production QuickJS host (no WebView, no Activity) and runs search, `parseNovel` and
`parseChapter` on each. It is network-dependent and on demand only.

- It fetches the **published** registry (`REGISTRY_URL`, the `v3.0.0` plugins branch), so it tests
  plugins as published, not local edits.
- It always samples the `anchorIds` (`novelhall`, `scribblehub`, `novelbin`, `wuxiaworld`, `WTRLAB`),
  then up to `SAMPLE_SIZE` (30) more English plugins; it searches up to `SEARCH_CAP` (12) and full-chains
  up to `FULL_CHAIN_CAP` (6).
- It asserts every fetched plugin loads and at least one completes the chain. A site's 404 or a
  Cloudflare block during search is tolerated.

```powershell
& $adb logcat -c
& $adb shell am instrument -w -e class reikai.novel.host.HeadlessJsIntegrationTest#lnPluginsRunInProductionHeadlessHost app.reikai.dev.test/androidx.test.runner.AndroidJUnitRunner
& $adb logcat -d -s HeadlessJsTest:I
```

The per-plugin report is in logcat tag `HeadlessJsTest`; `am instrument` prints only pass or fail. Read
it by layer:

- `LOAD FAIL <id>`: the JS did not load at all, so the host lacks a browser global the plugin uses (an
  engine or polyfill gap). The most serious case.
- `search ERROR`, `parseNovel ERROR`, `parseChapter ERROR`: it loads but throws, a logic or selector
  bug in the plugin, or the site changed.
- `search -> 0 results`, `chapters=0`, `0 chars`: it runs but parses nothing, a selector mismatch or a
  block. Check logcat's OkHttp lines: a `<-- 403` is a block, a `<-- 200` with empty output is the parser.

To force a specific plugin into the sample, add its registry id to `anchorIds` in the test, rebuild the
androidTest APK and rerun (a test-only edit, not to commit). The sibling `lnPluginRegistrySweep` probes a
whole registry: pass `-e registryUrl <url>` (and optionally `-e anchorIds a,b`); without a URL it skips.
For a local or forked plugin, host the repo, add it in the app (Browse, Extensions, Novels, overflow,
Repos), install the plugin and drive it through the UI while watching logcat.

## Driving a debug build on a device

Run adb from the PowerShell tool, except a binary pull (below). The debug package is `app.reikai.dev`;
its instrumented test package is `app.reikai.dev.test`.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "$HOME\.jdks\temurin-21.0.11"; .\gradlew.bat :app:installDebug --offline --console=plain
& $adb devices
& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb shell monkey -p app.reikai.dev -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
```

A folded foldable freezes its inner display, so wake it first or a capture returns a stale frame and
input may not land. `monkey ... LAUNCHER 1` reopens the app on its last screen.

### Reading the screen and tapping

Use `uiautomator dump` as the read channel: it reports each node's `text`, `content-desc` and `bounds`
in real device pixels, and it is always live. Tap the centre of a node found by its label:

```powershell
& $adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
$ui = (& $adb shell cat /sdcard/ui.xml) -join "`n"
$target = "Search"   # visible text or content-desc
$m = [regex]::new('(?:text|content-desc)="([^"]*' + [regex]::Escape($target) + '[^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"').Match($ui)
if ($m.Success) {
    & $adb shell input tap (([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2) (([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2)
} else { "NOT FOUND: $target" }
```

- **Dump before every tap and confirm after it.** Sheets, dialogs and tab rows change height, so a
  coordinate from the last screen is stale, and a blind chain turns one bad tap into a wrong end state.
- Aim at row centres; a whole list row is clickable.
- "could not get idle state" means the UI is still animating; wait and retry.
- Compose may merge or omit semantics, and the content-type chip shows as `text` in some states and
  `content-desc` in others; match both, or read the XML.
- Other input: `input swipe x1 y1 x2 y2 ms` (a long-press is the same point held about 600 ms),
  `input text "query"` into a focused field, `input keyevent KEYCODE_BACK`. Back with no sheet open can
  leave the app, so close a sheet by tapping its scrim.
- **Pull-to-refresh does not fire from `input swipe`** on Compose `pullRefresh`; use the screen's
  Refresh action instead.

A screenshot is the fallback for an element with no label. `screencap` on a multi-display foldable
prefixes a warning line, and PowerShell's `>` corrupts binary, so capture through `Start-Process
-RedirectStandardOutput` and strip everything before the PNG magic bytes (`89 50 4E 47`). The image you
read back is downscaled, so map a tap as a fraction of it times the real `wm size`. A sandboxed session
can block the local write and process spawn this needs (`EPERM ... uv_spawn`); the dump-driven loop
needs neither.

### Checking app state

App state is a stronger signal than the screen, since it proves the write path ran:

```powershell
pwsh scripts/dump-prefs.ps1 -Match 'pref_filter_library_categories'   # main preferences, secrets masked
pwsh scripts/dump-prefs.ps1 -File active_novel_downloads.xml           # the novel download queue store
```

Read preferences only through `scripts/dump-prefs.ps1`, never `cat`: the main file holds tracker and source
secrets, and the script prints only their length. A queue store drains as the job runs, so
it shows what is pending, not a cumulative count.

The database is `databases/tachiyomi.db`. Pull it from the Bash tool, which keeps the bytes intact, with
its `-wal` and `-shm` beside it (or `VACUUM INTO` one file on the device first), then open it with a
local `sqlite3`:

```bash
adb exec-out run-as app.reikai.dev cat databases/tachiyomi.db > local.db
```

Novels live in `novels` and `novel_chapters`.

### Logcat for LN plugins

Plugins run in the headless QuickJS host (`LnPluginHost`, `LnHostBridge`) and fetch through OkHttp.
Clear, act, dump:

```powershell
& $adb logcat -c
# open the source, a novel, a chapter
& $adb logcat -d 2>&1 | Select-String -Pattern "LnHost|plugin cache|OkHttp|FATAL|Exception"
```

- `LnHostBridge: loaded plugin <id> v<ver>` and the `plugin cache hit <hash>.js for <url>` line show
  which bundle is running. The host caches by content hash, so an update that seems not to run is
  usually a stale bundle; reinstall the plugin or refetch the repo.
- `OkHttp: --> GET <url>` and `<-- <code> <url>` are the plugin's requests. A 403 is a block (Cloudflare,
  a refused User-Agent); a 200 with nothing parsed is a selector problem; a missing GET is a path bug.
- Host fetches send the device WebView's User-Agent, so covers or pages that differ from LNReader point
  at the agent or a Cloudflare gate before the parser.
- `FATAL EXCEPTION` / `AndroidRuntime: FATAL` is a crash.

To exercise an updated plugin end to end, install it (Browse, Extensions, Novels chip), browse the
source (popular, latest and search), open a novel (`parseNovel`, plus page 2 on a paged source) and open
a chapter (`parseChapter`), watching those lines at each step.
