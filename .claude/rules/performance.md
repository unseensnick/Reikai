# Performance

The main thread draws every frame, so anything that holds it longer than a few milliseconds drops
frames, and five seconds of it is an ANR. A library holds thousands of entries with several sources
each and a series can have a thousand chapters, so per-entry work multiplies. These rules bind every
change on a hot path: the library pipeline, the details chapter list, both readers, recents, the
browse grids, and the update and download jobs.

## The rules

- **No I/O on Main.** Database, file, archive, network, bitmap decode, a JSON decode of a stored blob,
  and JS evaluation all go behind `withIOContext` / `launchIO` (architecture.md "Coroutines"). A
  `Preference.get()` reads an in-memory cache and is fine once per screen; it is not fine per row or
  per frame. `runBlocking` is allowed only on a thread that is already a worker (a WebView request
  thread, a dedicated engine thread), with a comment saying which.
- **Library-sized CPU work runs on `Dispatchers.Default`.** A sort, group, filter, map or regex pass
  over a whole list is `flowOn(Dispatchers.Default)` before its `stateIn`. `flowOn` moves only the
  upstream: the `stateIn` collector runs on `viewModelScope`, which is Main, and so does the `equals`
  a `StateFlow` runs on every new value. Put `distinctUntilChanged()` upstream of `flowOn`, and keep a
  big State's equality cheap (identity, when upstream dedup already guarantees a new instance means a
  change).
- **Nothing blocking or whole-list in composition.** No DB or preference read in a `@Composable`
  (screen-conventions.md), and no whole-list pass inside `remember {}`. A `stateIn` seeded by calling
  the mapper (`stateIn(scope, started, toNeutral(source.value))`) runs that mapper on Main; seed it with
  a `Loading` state and let the Default pass produce the first loaded one.
- **Anything that starts a collector or holds a resource is built once.** A `PreferenceMutableState`,
  a `Flow` chain, a `Paint`, a formatter: `remember` it in composition or hoist it to the model, never
  call its factory per recomposition. `PreferenceMutableState` launches a collector that lives as
  long as its scope, so an un-remembered call leaks one per frame.
- **Stable params on anything drawn many times.** Grid cells and list rows take `@Immutable` models,
  lazy items carry a stable `key` and a `contentType`, a row's lambdas read live state at tap time
  rather than capturing the snapshot, and a composable collects the field it reads, not the whole
  state. One-off dialogs are exempt.
- **No query per entry or per chapter.** A query inside a `forEach` / `map` over a library or a
  chapter list is one query with an `IN` clause, a join, or a view instead (database.md for the
  SQLDelight side).
- **Startup stays off the first frame.** Nothing new runs synchronously in `App.onCreate` or
  `MainActivity.onCreate`: it goes on the app scope (`@AppCoroutineScope`) or WorkManager, and a
  singleton's constructor launches its loading rather than doing it.
- **Writes from a per-frame or per-item source are coalesced.** One write per settled position, one
  transaction per batch. A table-level change signal that fans out to every open screen (SQLDelight
  notifies per table, not per row) is debounced or narrowed at the listener, never answered with a
  full reload per write.
- **Expensive engines are bounded and released.** No QuickJS engine or WebView per item, a pool with a
  cap rather than one live per source after a fan-out, and each one closed with its owner. A cost paid
  per process start (an engine per installed plugin) is cached against its input.

## Measuring

**A speed claim, either way, comes from a release-type build.** The `debug` build is unminified and
mostly interpreted, so its frame times are noise: one measured library scroll was 21% janky on debug
and 1.3% on a release-like build. Use the `benchmark` variant (`initWith(release)`, profileable):
to keep a device's library, set its `applicationIdSuffix` to `.dev` for the build only, run
`:app:assembleBenchmark`, restore the file, `adb install -r` over `app.reikai.dev`, then
`adb shell cmd package compile -m speed-profile -f app.reikai.dev`. Measure with `adb shell dumpsys
gfxinfo <package> reset`, the gesture, then `dumpsys gfxinfo <package> framestats`, or a Perfetto
trace of the main thread, never while a profiler runs, since that costs frames itself. A claim with
no measurement says "unmeasured".

## Before finishing a hot-path change

1. For every flow the diff adds or changes, name the dispatcher of each stage (`flowOn`, the
   `stateIn` scope, the `launch`), and write it in the plan when it is not obvious.
2. Run the `performance-reviewer` agent (`.claude/agents/`) on the diff and settle each finding:
   fixed, or left with the reason.
3. When the change is meant to speed something up, or could slow a library-sized path, measure it
   before and after on a release-type build and put both numbers in the commit body.
