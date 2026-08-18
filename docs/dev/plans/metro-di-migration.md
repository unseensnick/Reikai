# Metro DI migration (Injekt to Metro)

> **Status: phases 0 to 2 landed, phases 3 to 7 remain.** Research completed 2026-08-16 against upstream
> `b2015d1ef`; re-verified and corrected 2026-08-17 before phase 0. Every count below was measured,
> not estimated; the commands are given so a cold session can re-derive them before trusting them.

## Goal

Take Mihon's move from Injekt to [Metro](https://github.com/zacsweers/metro) (mihonapp/mihon#3608,
mihon `b2015d1ef`), across Reikai's own code as well as the Mihon files upstream's diff covers. No
user-facing change. The payoff is staying on upstream's DI architecture so later syncs of any file
that resolves a dependency apply cleanly, plus the removal of a whole class of R8 bug.

## Why

It is the first commit above the current synced base, and it is not a routine sync: 314 upstream
files against 264 Injekt-importing files here, of which about 90 are Reikai-owned with no upstream
diff to copy. Deferring it means every future sync of nearly any file fights a base that no longer
matches, which is the same trap the ViewModel migration documented and paid for.

The second reason is minification. Reikai's recurring "Internal error: TypeReference constructed
without actual type information" crash exists because R8 strips the generic signature Injekt's
`FullTypeReference` reflects on, which is why `app/proguard-rules.pro` keeps whole package trees.
Metro resolves the graph in the compiler and ships only `-assumenosideeffects` rules of its own
(`META-INF/proguard/metro-runtime.pro` inside `dev.zacsweers.metro:runtime-jvm:1.4.2`, inspected
2026-08-16), so the hazard and its keeps go away with the last `Injekt.get<T>()` in those packages.

## Status

**Phase 0 has landed.** The Metro plugin is on `:app` and the new `:core:metro` module, `AppGraph`
owns exactly one binding (`Json`), and `MetroInteropModule` hands that instance back to Injekt while
everything else still resolves the old way. Verified on the emulator with a minified `preview` build:
the app boots to a populated library, and `Json` is resolved from Injekt during startup through the
`DownloadManager` warm-up, so the handoff works under R8. 1064 tests, 0 failures.

**Phase 1 has landed.** All four leaf modules carry the plugin and their annotations: 60 classes in
`domain`, 13 repository implementations in `data`, 5 in `core/common` and 3 in `source-local`.
Nothing resolves through Metro yet.

**Unreachable contributions are pruned, not validated** (measured 2026-08-17). Annotating
`MangaRepositoryImpl` with `@ContributesBinding(AppScope::class)` while `AppGraph` had no `Database`
binding compiled clean, with `AppGraph` re-merged in that same run. So a class can be annotated long
before the graph can build it, which is what makes leaves-first safe.

**Phase 2 has landed.** The graph owns the four infrastructure providers (`SqlDriver`, `Database`,
`XML`, `ProtoBuf`) plus the sixteen leaf types the two Injekt module files used to register, and
hands every one of them back through the interop module. `AppModule` and `PreferenceModule` shrank
rather than disappearing, because 31 of their registrations and 93 of `DomainModule`'s name classes
declared under `app/` that later phases annotate.

**Interop entries are Providers, not instances** (owner-visible design divergence from upstream).
Upstream registers eager instances, which would construct `Database` at import time and defeat
`LegacyYokaiDbImporter`, which must move an incompatible database aside before anything opens it.
Registering `addSingletonFactory { provider() }` preserves exactly the laziness the Injekt factories
had.

`SqlDriver` and `AndroidStorageFolderProvider` get no interop entry: their only consumers were the
registrations that moved into the graph alongside them.

**Phase 3 is three commits, not one** (owner, 2026-08-17). Entry points cannot convert until their
dependencies are graph-constructible, and the dependency graph does not respect the ownership split:
`LibraryUpdateJob` is upstream-tracked and injects four Reikai-owned types, and 8 of the 15 workers
inject Reikai types directly. So 3a annotates and moves the upstream-tracked app classes, 3b does the
Reikai-owned ones (re-scoped from a tidy-up to a prerequisite, and now including the two `exh`
classes in `core/common`), and 3c converts the entry points.

**3a has landed.** 28 upstream interactors annotated, 12 app singletons and 12 `data` repository
implementations moved into the graph, and `scripts/di-interop-check.ps1` added with a `pre-commit`
hook so a type can never again be graph-owned and Injekt-registered at once.

**3b has landed.** The 80 Reikai classes are annotated, 45 more types moved into the graph, and the
four upstream classes 3a had to defer came with them once `ExhPreferences` was annotated.
`PreferenceModule` is gone: it registered nothing once its last entry moved.

**A third cycle was found while scouting 3b**, which the plan had not recorded:
`NovelMergeManager` to `PropagateNovelTrackerLinks` to `GetNovelTracks` and back. All three cycles
run through the merge manager's `onBeforeDissolve` lambda, which is only ever invoked inside a
suspend function, so `ReikaiBindings` supplies that lambda from a `Provider` of the propagator and
cuts all three at one edge.

`ReikaiBindings` also assembles the tracker-library fetcher list, because each fetcher wants a
concrete tracker and those are properties of the `TrackerManager` singleton rather than bindings of
their own. Binding them separately would build second tracker instances carrying their own login
state.

**Workers inject in an `init` block, not at the top of `doWork`.** They were eager property
initializers before, so `init` preserves the old semantics, and it removes the hazard that WorkManager
can call `getForegroundInfo` before `doWork`: `NovelDownloadJob` reads an injected field there.
Upstream's own `LibraryUpdateJob` has the shape this avoids.

**Three install paths are still untested across the whole port** (2026-08-18): fresh install, upgrade
from a shipped build, and the `:error_handler` crash process. Every device pass so far ran on one
emulator with existing state. The upgrade path is what the `Provider`-not-instance interop rule
protects, and that rule is reasoned rather than exercised.

**The gates prove "nothing broke", not "nothing was missed."** Six update-error interactors were
walked past in 3b and every gate still passed, because they were still Injekt-registered. That is why
`scripts/di-interop-check.ps1` now fails on a registered class with no graph annotation, alongside its
original no-double-registration check. Both halves are mutation-tested.

Phases 4 to 7 remain, with two corrections found by the 2026-08-17 audit:

- **Phase 5 must inject the migration set as a `Provider`**, never eagerly. A `Set<Migration>` built
  at `graph.inject(this)` constructs every migration, and one of them pulls `Database`, which would
  open the database before `LegacyYokaiDbImporter` can move an incompatible one aside.
- **Phase 7 cannot drop the `reikai.**` / `exh.**` proguard keeps.** The novel reader stays on Injekt
  by design with reified generic lookups on `reikai.*` types, and `source-api` reads
  `DelegateSourcePreferences`, an `exh.pref` type, from three places. Those keeps leave with the
  tsundoku reader migration, not with this port. Phase 7 keeps the baseline profiles and the rules
  files only.
- Relatedly, **phase 6 does not shrink the interop module for the novel reader's subgraph**: keeping
  `NovelReaderScreenModel` on Injekt means its 17 dependencies must be handed back, and only one is
  today.

Owner rulings, 2026-08-16:

- **Port everything, in two commits.** Upstream-mirroring work lands first; the Reikai-owned trees
  (`reikai/`, `exh/`) follow as a direct follow-up commit, not a later roadmap item.
- **The R8 question was settled before starting** rather than left to a build (see Why).
- **The novel reader stays on Voyager `ScreenModel`**, held for the tsundoku migration as before.
  `metrox-viewmodel` only covers `androidx.lifecycle.ViewModel`, so `NovelReaderScreenModel` keeps
  resolving through Injekt and its dependencies stay interop-registered until that migration deletes
  the file. Its sequence position is therefore "never", not "last".

## Approach

### What upstream actually built

A hybrid, not a replacement. Metro owns the object graph; Injekt survives as a runtime facade so
installed extensions keep working.

- **`:core:metro`**, a new three-file module: `GraphProvider<T>` (an interface the `Application`
  implements), an `@Qualifier annotation class IsDebugBuild`, and
  `fun <T> Context.metroGraph(): T = (applicationContext as GraphProvider<T>).graph`.
- **`AppGraph`**, a `@DependencyGraph(scope = AppScope::class, bindingContainers = [AppBindings::class])`
  interface that extends `ViewModelGraph`. It carries 14 `fun inject(x)` members for Android entry
  points, about 40 read accessors for code that cannot be constructor-injected, and a
  `@DependencyGraph.Factory fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean)`.
- **`AppBindings`**, an `@BindingContainer object` holding the five things that cannot be an
  annotated class: `SqlDriver`, `Database`, `Json`, `XML`, `ProtoBuf`.
- **`MetroInteropModule`**, a Metro-constructed `InjektModule` that re-registers nine Metro
  singletons back into Injekt for the extension contract.
- **`App`** implements `GraphProvider<AppGraph>`, builds the graph lazily, calls `graph.inject(this)`,
  then `setupInjekt()`: `patchInjekt()`, `Injekt.addSingleton<Application>`, `addSingleton<Context>`,
  `importModule(interop)`.
- The three module files (`AppModule` 127 lines, `PreferenceModule` 79, `DomainModule` 194) and
  `Migrations.kt` are deleted in the same commit.

### The per-class pattern

Almost everything is a two to five line edit, and the shape is uniform:

| Kind | Change |
|---|---|
| Interactor | `@Inject` |
| `*Preferences`, caches, managers | `@Inject` + `@SingleIn(AppScope::class)` |
| Repository impl | those two plus `@ContributesBinding(AppScope::class)` |
| ViewModel, no runtime args | `@Inject` + `@ViewModelKey` + `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())` |
| ViewModel with runtime args | `@AssistedInject`, args `@Assisted`, nested `@AssistedFactory @ManualViewModelAssistedFactoryKey @ContributesIntoMap` |
| Migration | `@Inject` + `@ContributesIntoSet(AppScope::class)`, dependencies as constructor params |
| Activity, worker, receiver | a graph field plus `@Inject lateinit var`s and `graph.inject(this)` |
| Composable read | `remember { context.appGraph.x }`, which means adding an accessor to `AppGraph` |
| Object / static | the companion function gains a `Context` parameter and reaches the graph through it |

In every case the constructor defaults (`= Injekt.get()`) are deleted, which is what makes the diff
large: 508 such defaults here.

### ViewModels

Hand-written factories go away. Screens call `metroViewModel<T>()` or
`assistedMetroViewModel<T, F> { create(...) }`, and every Compose entry point must provide
`LocalMetroViewModelFactory` from the graph (upstream does it inside `setComposeContent`).
`ReaderViewModel` is the one model that keeps a `CreationExtras` path, through Metro's
`ViewModelAssistedFactory` rather than a hand-written `viewModelFactory`.

Two consequences worth stating: the rule that a bare-resolved model must never be `private` stops
applying on the Metro path, because the factory looks the class up in a generated map rather than
instantiating it reflectively; and upstream un-`private`d `TrackStatusSelectorScreen` for the
opposite reason, its factory needing to be visible.

### What stays on Injekt

`source-api` is deliberately left out of the Metro plugin list because it is the contract installed
extensions compile against. Upstream keeps 41 files on Injekt: the contract itself, the trackers
(hand-constructed inside `TrackerManager`, so not graph nodes), the interop bridge, and a tail it
had not converted. Seven upstream files carry Metro annotations and an Injekt call at once, so a
partially migrated file is legal.

**Reikai's contract surface is wider than upstream's**, and this is the one failure with no compile
error: `DelegateSourcePreferences` is read from `source-api/.../online/HttpSource.kt:591`,
`exh/source/EnhancedHttpSource.kt:203` and `exh/metadata/metadata/EHentaiSearchMetadata.kt:59`, and
`source-api/.../online/MetadataSource.kt:24-26` exposes three interactors as interface `get()`
accessors. Every one of those must be interop-registered or delegated sources throw on first use.

## Inventory, measured 2026-08-16

Re-derive with `grep -rl --include=*.kt "uy.kohesive.injekt" app domain data core core-metadata source-api source-local presentation-core presentation-widget telemetry`.

**Injekt surface: 265 files.** app 258, source-api 5, source-local 1, data 1, presentation-widget 1.
704 `Injekt.get` occurrences, 267 `by injectLazy()`, 506 constructor defaults. Pass
`--exclude-dir=build` or a stale copy under `app/build/spotless-clean/` inflates every count by one.

**Ownership split: 175 upstream-tracked, 89 Reikai-owned** (`reikai/` 71, `exh/` 16, two
`source-api/exh` files, plus one androidTest file). The first number has an upstream diff per file;
the second does not.

**Registrations: 218 across three files, 82 of them Reikai-only.** Count registrations with import
lines excluded, or each file reads two or three high.

| File | Lines | Registrations | Reikai-only |
|---|---|---|---|
| `eu/kanade/domain/DomainModule.kt` | 439 | 162 | 65 |
| `eu/kanade/tachiyomi/di/AppModule.kt` | 189 | 33 | 9 |
| `eu/kanade/tachiyomi/di/PreferenceModule.kt` | 122 | 23 | 8 |

**By kind:** 38 `viewModelFactory {` blocks and 73 `CreationExtras.Key` declarations; 16 worker
classes (14 using Injekt, 9 of them Reikai-only) plus 10 `setupTask` companions; 47 files holding
both `@Composable` and an Injekt call, with 54 `remember { Injekt.get() }` sites; 21 `object` files
plus 2 file-scope `by injectLazy()` delegates; 16 migrations and 31 `migrationContext.get<T>()`
sites; zero Injekt in `app/src/test` and one file in `androidTest`.

**Upstream's diff: 314 files, +2416 / -2010.** app 219, domain 55 (all two-line annotation adds),
data 13, core/common 8, core/metro 5 new, presentation-widget 5, source-local 4, source-api 2, three
build files. 68 files exceed 15 changed lines and all but about 6 are mechanical.

**Baseline profiles: 896 lines in `baseline-prof.txt` and 842 in `startup-prof.txt` name Injekt or
the three modules** (`app/src/main/baselineProfiles/`, not `app/src/main/`). They need regenerating
on the GMD after the port, or they are dead rules.

## Sequence

Each phase is a commit that compiles and boots. The verification column says what actually proves it.

| Phase | Work | Proves |
|---|---|---|
| 0. Spike (done) | Plugin on `:app` and the new `:core:metro`, `AppBindings` providing `Json` only, an `AppGraph` with `inject(app)` plus two accessors, interop for that one type | Done: compile, 1064 tests, minified `:app:assemblePreview`, cold start to a populated library |
| 1. Leaves (done) | Annotate `core/common`, `domain`, `data`, `source-local`, plus the three upstream signature changes and the call sites they force in `app` and `exh` | Done: each module compiled alone, then 1064 `:app` and 75 `:domain` tests, a minified build and a manga browse list on device |
| 2. Graph (done) | `AppBindings` for the four infrastructure providers, accessors and interop for the leaf types `AppModule` / `PreferenceModule` registered, `App` bootstrap. The three module files shrink; they cannot be deleted until phases 3 to 6 empty them | Done: 1064 + 75 tests, a minified build, and on device a cold start, the full library and a backup within 14 bytes of the pre-Metro one |
| 3a. App classes, upstream (done) | Annotate and move the `eu.kanade` / `mihon` classes the module files register, plus the `data` repository implementations | Done: 1064 + 75 tests, minified build, cold start, library, details, tracking sheet |
| 3b. App classes, Reikai (done) | The `reikai` / `exh` classes, the fetcher list and both merge managers via `ReikaiBindings`, and the two `exh` classes in `core/common` | Done: 1064 + 75 tests, minified build, cold start, library, and a novel source browsed end to end through the QuickJS plugin host |
| 3c. Entry points (done) | All 15 workers, the 5 `setupTask` companions (through `Context.appGraph`), `AppModule` deleted with its two `addSingleton` calls and the warm-up moved into `App`, both widget surfaces plus their two refresh managers, and the activities, delegates and `NotificationReceiver` | Done: both update jobs to success, a cold start with the warm-up in `App`, both widgets rendering, and on a minified build the library, reader, WebView, a real tracker login, a download-queue notification action and the legacy extension installer |
| 4. ViewModels and Compose (in progress) | Done: metrox wired, `AppGraph : ViewModelGraph` with `ReikaiViewModelFactory`, the local at both `setComposeContent` roots, every plain model, and the assisted models for search, extension details, source browse, manga and novel details, notes, and seven one-off screens. Left: 20 `viewModelFactory` blocks and 49 keys, being `EntryTrackInfoDialog` (8 and 29), the migrate flow (7 and 13), the two engines (2 and 4), the EXH pair (2 and 3) and the cover-factory initializer, plus roughly 70 composable Injekt reads | Each batch: interop check, compile, spotless, both test suites, a minified build, then the screens it touches driven on device |
| 5. Migrations | 16 migrations to `@ContributesIntoSet`, 31 context reads to constructor params | Device: upgrade from an older `versionCode` and watch the migration log |
| 6. Reikai-owned | `reikai/` 71 files and `exh/` 16, the follow-up commit; the interop module shrinks as they land | Full device sweep: novels, EXH, recommendations, merge, migrate |
| 7. Cleanup | Drop the `reikai.**` / `exh.**` proguard keeps if no Injekt generic remains, regenerate both baseline profiles, rewrite the rules files | Minified `:app:assemblePreview`, then the profiles' own generation task |

**Ordering rule that makes the two-commit split safe:** a type that moves to Metro must have its
Injekt registration replaced by an interop `addSingleton(metroInstance)` in the same commit, never
deleted and never left duplicated, or the app runs with two singletons of that type and the symptom
is lost state rather than a crash. During the upstream-mirroring commit the interop module is at its
widest, because Reikai-owned code is still resolving those types from Injekt; it shrinks in the
follow-up.

**Direction is leaves-first.** Annotating a library module adds annotations only, so `app` keeps
compiling against the same constructors. Graph-first forces the whole transitive closure of every
accessor to be annotated at once.

**The ViewModel phase is incremental, not a cliff.** Only a model carrying `@ViewModelKey` or an
assisted-factory key joins the multibinding, so every model still on its own `viewModelFactory`
companion keeps working untouched. Convert screen by screen; the graph pieces (the `ViewModelGraph`
supertype, the factory binding, the composition local) are what must land first and together.

**Atomic units that cannot be split:** the Gradle plugin plus the runtime dependency plus the first
annotation in a module; a module file's deletion plus every type it registered; the migration set
(the `Set<Migration>` injection and every `@ContributesIntoSet`); and the `ViewModelGraph` supertype
plus the `viewModelFactory` accessor plus the composition local, which arrive together because a
contributed model cannot resolve without all three.

## Traps

- **The merge-manager cycle.** `DomainModule.kt:267` and `:271` hand `MangaMergeManager` and
  `NovelMergeManager` a lambda that resolves `PropagateTrackerLinks` / `PropagateNovelTrackerLinks`,
  which depend on the managers. Metro rejects the direct cycle at compile time, so the lambda becomes
  `Lazy<T>` in the same commit the four types are annotated. Upstream has the same shape at
  `AndroidSourceManager` and solves it with `private val downloadManager: Lazy<DownloadManager>`.
- **`LnPluginHost` and `LnPluginLoader` take an `OkHttpClient`**, built at `AppModule.kt:148` from
  `get<NetworkHelper>().client`. `@Inject` alone cannot resolve a bare `OkHttpClient`. Change both
  constructors to take `NetworkHelper` rather than adding a global `OkHttpClient` binding that other
  code could bind by accident.
- **`AndroidSourceManager` builds sources inside an init flow collector and matches delegated sources
  by `sourceName`**, both invisible to Metro. It also breaks its own cycle with
  `private val downloadManager: DownloadManager by injectLazy()` (`AndroidSourceManager.kt:65`, and
  `exhPreferences` at `:68`), a property delegate rather than a constructor parameter, so the port
  needs `Provider`/`Lazy` injection there, not a parameter swap. Annotating it is safe only while the
  types those runtime lookups need are still interop-registered, and a miss compiles fine and throws
  on the first source-map build.
- **`LibraryEngine` and `RecentsEngine`** take their provider lists through `CreationExtras` keys and
  everything else through Injekt constructor defaults (`LibraryEngine.kt:56-63`,
  `RecentsEngine.kt:49-56`). Metro cannot use default-argument injection, so these become
  `@AssistedInject` with the providers assisted, and the factory plus every call site move together.
  They compile fine unannotated, so they can also be deliberately left for last.
- **The closure-capturing cover factory** at `reikai/presentation/details/EntryDetailsDialog.kt:102`
  builds a star-projected `EntryCoverViewModel<*>` from a captured behaviour object. There is no
  upstream analogue and no graph key for it; design it before touching it.
- **`App.onCreate` ordering.** `LegacyYokaiDbImporter.prepareIfLegacyDb` at `App.kt:120` must run
  after DI exists and before the database is first opened. A lazily built graph whose eager
  singletons touch SQLDelight breaks the legacy recovery silently.
- **The `:error_handler` process.** `CrashActivity` is the only component with `android:process`
  (`AndroidManifest.xml:124`), so `App.onCreate` and therefore graph construction runs there too.
- **Widget surfaces are system-instantiated**, and the two of them inject from different places.
  `presentation-widget` took upstream's shape: the Metro plugin, `implementation(libs.metro.runtime)`
  in place of the `api(libs.injekt)` it leaked onto consumers, a `@ContributesTo` `PresentationWidgetGraph`
  the module declares its own `inject()` on, `@HasMemberInjections` on the base widget, and `context`
  threaded through `prepareData` now that the constructor no longer holds one. `UnifiedUpdatesGlanceWidget`
  is NOT a subclass of that base, so the module graph does not reach it: it injects through
  `AppGraph.inject()` like the workers, and threads `context` through four private functions.
- **10 `setupTask` companions** are called from five migrations, from `PreferenceRestorer` and from
  composable settings bodies. Two of them are the historical R8 crash sites, so they are exactly the
  code that stops being fragile once it is graph-resolved.

## Riding-along changes to decide, not inherit

Upstream's commit is not purely a DI change. Each of these needs an explicit yes or no.

**Decided 2026-08-18 (owner).** Taken: 4, 5, 10 and 11, all of them already in the tree. Item 11 is
the one to say out loud, as it asked: the driver's `lock` plus `WeakReference` guard is gone, and
`@SingleIn(AppScope::class)` on `AppBindings.providesSqlDriver` carries the single-instance guarantee
instead. Rejected as a pattern: 10, where Reikai injects in `init` for every worker rather than
copying upstream's split. **Deferred until the DI port is finished:** 1, 2, 3, 6, 7 and 8. None is a
DI change; each is an API or behaviour change that happens to ride along, and taking them mid-port
would mix two kinds of risk in one commit. Revisit them as their own items after phase 7. Item 9
belongs to the ViewModel phase and is tracked there.

1. `source-api/.../util/RxExtension.kt` deleted. Public extension-lib surface.
2. `ConfigurableSource` switches `Injekt.get<Application>()` to `Injekt.get<Context>()`. Safe here:
   `AppModule.kt:71-72` already registers both.
3. WorkManager threaded explicitly (`BackupRestoreJob.isRunning/start/stop` and
   `LibraryUpdateJob.startNow` take a `WorkManager`), which ripples into five call sites and is
   inconsistent upstream, since `LibraryUpdateJob.stop` still takes a `Context`.
4. `NetworkPreferences` swaps `verboseLoggingDefault: Boolean = false` for `@IsDebugBuild`.
   Behaviour-neutral here: `PreferenceModule.kt:42-45` already passes `isDebugBuildType`.
5. `AndroidPreferenceStore` loses its `SharedPreferences` default parameter.
6. `ExtensionApi` and `DownloadNotifier` lose `internal`.
7. `DownloadStore`, `DownloadNotifier` and `DownloadPendingDeleter` become app-scoped singletons
   where they were per-`Downloader` instances, and `Download.fromChapterId` moves into
   `DownloadManager`. This one is a real behaviour change, not a refactor.
8. `BackupFileValidator` drops its `context` parameter; `BackupCreator` becomes `@AssistedInject`
   with `isAutoBackup` moved to first position.
9. `MangaCoverViewModel` is created from the screen's `mangaId` rather than
   `successState.manga.id`, which changes which value wins after a migration.
10. Worker injection point is inconsistent upstream: `DownloadJob` injects in `init`, the others on
    the first line of `doWork()`. Copying the wrong one gives an uninitialized-property crash in
    `getForegroundInfo`, which WorkManager may call before `doWork`. Upstream's own `LibraryUpdateJob`
    has exactly that shape after the migration, so it is a pattern to fix rather than copy.
11. `AppBindings.providesSqlDriver` drops the `lock` plus `WeakReference<SqlDriver>` guard that mihon
    `f8e82b932` added to fix a "database is locked" crash, and that we still carry at
    `AppModule.kt:64-88`. Metro's `@SingleIn(AppScope::class)` gives one instance per graph and there
    is one graph per process, so it should subsume the case the guard covered, but this is a silent
    behaviour change and needs saying out loud rather than inheriting.

## Key files

- Upstream: `mihon/app/di/{AppGraph,AppBindings,AppGraphUtils,MihonViewModelFactory}.kt`,
  `mihon/app/di/injekt/MetroInteropModule.kt`, `core/metro/src/main/kotlin/mihon/core/metro/*.kt`,
  and `eu/kanade/tachiyomi/App.kt`, all at `b2015d1ef`.
- Here: `app/src/main/java/eu/kanade/domain/DomainModule.kt`,
  `app/src/main/java/eu/kanade/tachiyomi/di/{AppModule,PreferenceModule}.kt`,
  `app/src/main/java/eu/kanade/tachiyomi/App.kt`, `app/proguard-rules.pro`,
  `app/src/main/baselineProfiles/`, and the two engines named under Traps.

## Decisions and tradeoffs

- **Port everything rather than mirroring upstream only** (owner, 2026-08-16). Mirroring would have
  been about a third of the work and would have left Reikai-owned trees on Injekt indefinitely; the
  owner chose to end the split, with the Reikai half as its own follow-up commit so the
  upstream-mirroring diff stays reviewable against upstream's.
- **The R8 question was settled by inspecting the artifact, not by building.** Metro ships
  `META-INF/proguard/metro-runtime.pro` containing only two `-assumenosideeffects` rules, so it needs
  no keeps and adds no reflection. Our existing keeps stay only while Injekt calls remain in those
  packages, which phase 7 is what clears.
- **Migration order is not at risk.** `MigrationJobFactory.kt:16` sorts by `version`, so moving from
  a hand-maintained list to a `Set<Migration>` multibinding cannot reorder anything.
- **The novel reader is not migrated.** See Status.
- **`i18n` needs no plugin.** It is the only multiplatform module and holds no Kotlin code.
- Documentation debt this creates: `.claude/rules/architecture.md` lines describing Injekt as the DI
  system, the `Injekt.get<Application>()` convention in `.claude/rules/screen-conventions.md`, and
  the R8 keep discipline in both become false and are rewritten in phase 7.
