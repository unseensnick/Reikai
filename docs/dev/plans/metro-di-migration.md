# Metro DI migration (Injekt to Metro)

> **Status: phases 0 to 4 have landed in full. Phases 5 to 7 have not started.** With the reader
> converted, no screen resolves through Injekt any more and what is left is migrations, the
> Reikai-owned tail and cleanup. Research completed 2026-08-16 against upstream `b2015d1ef`;
> re-verified 2026-08-17 before phase 0; re-measured against current code 2026-08-20.
>
> **Counts age fast here.** Everything under Inventory is a 2026-08-16 snapshot kept as the record of
> what the port started from, and most of it is now wrong. Current figures live in Status and in the
> sequence table. Re-derive before trusting either; the commands are given.

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
2026-08-16), so the hazard goes away with the last `Injekt.get<T>()` in those packages.

**The keeps mostly do not go, and this was overstated until 2026-08-20.** `source-api` and
`source-local` hold Injekt permanently, because they are the contract installed extensions compile
against, and they live under `eu.kanade.**`, `exh.**` and `tachiyomi.**`. Three of the five keeps in
`app/proguard-rules.pro` therefore stay whatever this port does. Only `mihon.**` and `reikai.**` are
reachable, and those leave with the tsundoku reader migration rather than with this work.

## Status

### What is left, in order (the authoritative list)

Read this before the sequence table. The table below is the landed record and the original phase
shape; where the two disagree, this list wins. Phases 0 to 4 are finished, so this list is the whole
of what remains.

| Unit | Work | Proves |
|---|---|---|
| 5 | The migrations | Upgrade from an older `versionCode` and watch the migration log |
| 6 | The Reikai-owned tail, including the cover models and library adders | Full device sweep: novels, EXH, recommendations, merge, migrate |
| 7 | Cleanup | Minified `:app:assemblePreview`, then the profiles' own generation task |

**Fresh install is now exercised** (2026-08-20): the preview install was wiped, walked through
onboarding and restored from its own backup. **Upgrade from a shipped build is still untested.**

### The files this port had skipped, done 2026-08-20

An audit of `b2015d1ef` file by file found about fifty upstream-converted files under `app/` that no
phase owned, so "phase 4 complete" was true only of the units the plan named. They landed as four
seams: backup (`fb6ba9ce0`), the downloader, notifiers and cover fetchers (`918377dfa`), the trackers
(`1fe397998`), and the extension, update and cover-util tail (`9e56c41cd`). Upstream's WorkManager
parameter refactor went in beside them (`110e3c6e7`).

**What let them slip is a sentence in this document.** "What stays on Injekt" below says upstream
keeps 41 files on Injekt including "the trackers ... and a tail it had not converted". That counts
files still *containing* an Injekt call after the commit, which is not the same as files upstream
left alone: `BaseTracker` is in that 41 and upstream rewrote it anyway. Read as "upstream left
these", the line licenses skipping exactly the set that was skipped. **The count to use is per-file
against upstream's post-commit state, never the total.**

**No gate could see them.** `di-interop-check.ps1` fails on a type that is Injekt-registered and
unannotated. These construct themselves through `= Injekt.get()` defaults and are registered nowhere,
so the check is silent and so is the compiler. That is the same shape as the six update-error
interactors 3b walked past, one level further out.

Verified on a minified emulator build: a backup created, then the install wiped, onboarded, and
restored from that backup, coming back to 220 entries (169 manga with all three categories, 51
novels); and a chapter downloaded to a finished `.cbz` with the progress notification clearing and
the row showing as downloaded. Trackers were verified on the Fold against live accounts: an AniList
bind on a manga and a Kitsu bind on a novel, both returning authenticated search results and writing
the link. The novel sheet offers seven trackers to the manga sheet's nine, which is `supportsNovels`
doing its job.

### Landed

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

**Two install paths are still untested across the whole port** (2026-08-18): fresh install and upgrade
from a shipped build. Every device pass so far ran on one emulator with existing state. The upgrade
path is what the `Provider`-not-instance interop rule protects, and that rule is reasoned rather than
exercised. The `:error_handler` process was exercised by a forced crash when its gate landed.

**The gates prove "nothing broke", not "nothing was missed."** Six update-error interactors were
walked past in 3b and every gate still passed, because they were still Injekt-registered. That is why
`scripts/di-interop-check.ps1` now fails on a registered class with no graph annotation, alongside its
original no-double-registration check. All three halves are mutation-tested.

**Lazy to eager is the port's own bug class** (audit, 2026-08-18). The port removed 110 `by
injectLazy()` delegates and 333 `Injekt.get()` constructor defaults; each one changes *when* the
dependency is built, and a dependency whose `init` starts work then starts it wherever its owner is
built. One case was caught on device by accident, and the fix for it (`d38d1d27f`) closed the direct
edge while a transitive one stayed open: the novel library reached `NovelDownloadManager` through
`SetNovelReadStatus` to `DeleteNovelChaptersAfterRead`, so opening the library still resumed
downloads. **Before promoting any dependency to a constructor parameter, read its `init`, and check
the promoted type's whole transitive closure rather than the one edge you are editing.** Member
injection is the same hazard by another route: `NotificationReceiver` injects every field at the top
of `onReceive`, so a field only one action needs is built for every notification tap.

**The audit's other two findings.** `App.onCreate` had come to build `Database` before the legacy
recovery ran, inert only because the SQLDelight driver opens its connection lazily; both widget
managers are lambdas now so the ordering holds structurally. And the guard checked ownership but never
scope, which is the actual mechanism of the silent-double-instance bug it was written for: Injekt's
`addSingletonFactory` caches per type forever, while an unscoped Metro binding builds a new instance
per injection, so the two halves of the app drift apart with nothing failing. It now checks scope,
discovers Injekt modules instead of reading a stale path list, matches multi-line registrations, and
runs from CI as well as the hook, which fires on staged content rather than four hard-coded paths.

**What the audit could not have caught, and what that costs.** Its six parallel passes verified
scoping across all 88 interop types, every lost registration, every ViewModel conversion and every
entry point, and found three defects. It also produced two false alarms worth remembering: three
independent passes claimed `spotlessCheck` would fail on the port's orphaned imports (it does not,
ktlint as configured here does not flag them), and one reported a count that a recount contradicted.
Treat a subagent's claim about a gate as a hypothesis until the gate is run.

Phases 5 to 7 remain, with two corrections found by the 2026-08-17 audit:

- **Phase 5 must inject the migration set as a `Provider`**, never eagerly. A `Set<Migration>` built
  at `graph.inject(this)` constructs every migration, and one of them pulls `Database`, which would
  open the database before `LegacyYokaiDbImporter` can move an incompatible one aside.
- **Phase 7 cannot drop the `reikai.**` / `exh.**` proguard keeps.** The novel reader stays on Injekt
  by design with reified generic lookups on `reikai.*` types, and `source-api` reads
  `DelegateSourcePreferences`, an `exh.pref` type, from three places. Those keeps leave with the
  tsundoku reader migration, not with this port. Phase 7 keeps the baseline profiles and the rules
  files only.
- Relatedly, **phase 6 does not shrink the interop module for the novel reader's subgraph**: keeping
  `NovelReaderScreenModel` on Injekt means its 17 dependencies must be handed back. Re-measured
  2026-08-19: **11 of the 17 are already interop-registered**, up from the one this line originally
  recorded, so phase 6's floor for the interop module is those 17 rather than the six not yet added.

**The Reikai-owned models still on constructor defaults belong to phase 6**: `EntryCoverViewModel`,
`MangaEntryCoverViewModel`, `NovelCoverViewModel`, both library adders and `MigrateNovelUseCase`.
Re-derive the set with
`grep -rln --include=*.kt --exclude-dir=build 'Injekt.get()' app/src/main/java | xargs grep -l ViewModel`.

### The composable reads, specified 2026-08-19

Upstream's `b2015d1ef` fully converted its settings screens. Only two Injekt calls survive under
`more/settings/` there: a `Context` locator in `AboutScreen`, and an `Injekt.addSingleton` inside a
`@PreviewLightDark` preview in `AppThemePreferenceWidget`. Partial conversion is not upstream's model.

Three target shapes, taken verbatim from that commit:

- Inside a `@Composable`: `val context = LocalContext.current`, then `remember { context.appGraph.x }`.
  Hoist `val graph = remember { context.appGraph }` when a body has four or more reads, as
  `SettingsAdvancedScreen` does upstream.
- Inside a lambda or callback: read inline off `context`, unremembered, as in
  `context.appGraph.downloadCache.invalidateCache()`.
- A class or object property initializer: keep Injekt purely as a `Context` locator,
  `Injekt.get<Context>().appGraph.x`. Upstream's own `DisplayRefreshHost` has exactly this shape.

36 distinct types are read across the scope. 13 already have accessors and **25 are net-new**, every
one a one-liner, because all 25 are already graph-constructible: 22 carry `@Inject` (with
`@SingleIn(AppScope::class)` where they are singletons), both merge managers come from
`ReikaiBindings`, and `TasteLibraryRepository` is bound by its implementation through
`@ContributesBinding`. They belong in the accessor block under the "Read through Context.appGraph"
comment. `Application` is not on the graph at all, only `Context`, but nothing in this scope needs it.

Four batches, each gated and committed on its own:

1. **Landed** (`c3d1c4cd9`). The twelve settings screens and their lambda reads, plus `ConfigureExhDialog`,
   `AboutScreen.getFormattedBuildTime`, `DebugInfoScreen` and `WorkerInfoScreen`. 17 files, 23 new
   accessors, 46 reads converted. Manga and novel pairs moved together: `SettingsLibraryScreen` reads
   `GetCategories` beside `GetNovelCategories` and resets both category flag sets in adjacent
   lambdas, so converting one half would have forked the rule. The settings tree now matches
   upstream's end state, where the only surviving Injekt calls are `Injekt.get<Context>()` locators
   in the three non-composable readers plus the preview widget batch 4 took.
2. **Landed** (`9d85a35f6`). The remaining composables, including four Reikai-owned ones that phase 6 would
   otherwise have taken (owner, 2026-08-19): `DateText`, `ChapterSettingsDialog`,
   `ChapterListDialog`, `ReadingModePage`, `TachiyomiTheme`, `SourcePreferencesScreen`,
   `HomeScreen`, `OnboardingScreen`, `ReaderProgressIndicator`, `ReaderTransitionView`,
   `EntryCoverDialog`, `EntryDescription`, `SettingsRecommendationsScreen` and
   `MigrationDeepPicker`. 15 files, 2 new accessors. Three shapes beyond the usual: the two
   `AbstractComposeView` subclasses read the view's own `context` rather than `LocalContext`;
   `SourcePreferencesFragment` uses `requireContext()`; and `HomeScreen` hoists the context out of
   its `produceState` blocks, whose lambdas are suspend, not composable. `MigrationDeepPicker`'s
   file-level `by injectLazy()` property is gone, so the handoff now resolves from the app-scoped
   binding by construction rather than by coincidence.
3. **Landed** (`9ad07b8f4`). The non-composable holders: the three onboarding steps and
   `DisplayRefreshHost`, plus `eu/kanade/core/util/SourceUtil.kt`. That last one is a genuine
   composable read (`remember { Injekt.get<SourceManager>().isInitialized }` inside
   `ifSourcesLoaded()`) that both earlier batches missed, because the scope was derived from files
   holding `@Composable` next to a settings or details surface and this one is a bare util. Found by
   re-measuring 2026-08-19. The three onboarding steps took upstream's shape rather than the locator
   form the batch was scoped around: upstream deletes the property and reads inside `Content()`, so
   only `DisplayRefreshHost`, which has no composition, keeps `Injekt.get<Context>()`.
4. **Landed** (`cbea79b08`). The `@PreviewLightDark` preview in `AppThemePreferenceWidget`, which
   turned out to be a repair rather than a conversion. Batch 2 moved `TachiyomiTheme` onto the graph,
   and `appGraph` casts the application context to `GraphProvider`, which a preview has no
   `Application` to satisfy. So the preview had been dead since batch 2, and the `Injekt.addSingleton`
   it carried had been feeding nothing for just as long. Both the preview and the per-swatch call
   inside `AppThemesList` now use `TachiyomiPreviewTheme`, the graph-free entry point the other 21
   previews already use; production behaviour is identical because both sites pass the theme and the
   amoled flag, so the graph's values were discarded anyway. **Upstream is broken the same way at
   their HEAD** and has touched neither file since their migration; the owner ruled against reporting
   it. Nothing in the repo or in CI renders previews, so no gate can catch this class of break.

**`MigrationDeepPicker` is an identity hazard, not a timing one.** `MigrationPickHandoff` is
`@SingleIn(AppScope::class)` and reaches that file today through the interop module's provider.
Resolving it any other way splits the offer/take slot, and the symptom is a manual deep pick silently
going nowhere rather than a crash.

**Timing is clear everywhere else in this scope.** The four types promoted out of property
initializers are thin `PreferenceStore` wrappers with no `init` block, no eager `.get()` and no
coroutine, and `AndroidPreferenceStore.keyFlow` is a cold `callbackFlow` that registers its listener
only on collection. `StoragePreferences` evaluates `folderProvider.path()` as a default value, which
builds a path string and reads one string resource without touching the filesystem. One pre-existing
eager read stays as it is: `DisplayRefreshHost` calls `flashIntervalPref.get()` in a property
initializer, a synchronous preferences read at `ReaderActivity` construction that predates the port.

The 13 files carrying a lone orphan `uy.kohesive.injekt` import were swept in `d721de727`. The
per-batch rule they were under could not discharge them, because batches 3 and 4 touch none of the
13, so they went in one commit of their own to keep the batch diff readable against upstream's.

### The reader models and engine, done 2026-08-20

`ReaderViewModel` is the port's first model on metrox's **assisted-with-`CreationExtras`** shape, the
third of `MetroViewModelFactory`'s three maps and the only one that can supply a `SavedStateHandle`.
It is `@AssistedInject` with a nested `@AssistedFactory @ViewModelAssistedFactoryKey` interface whose
`create(extras)` calls `extras.createSavedStateHandle()`, and `ReaderActivity` passes
`graph.viewModelFactory` to `viewModels`. The reader is an Activity, not a Compose screen, so the
plain and manual shapes the rest of the tree uses do not reach it. Five dependencies came in with the
conversion (`LocalCoverManager`, `UpdateManga`, `CoverCache`, `ChapterCache`, `DownloadCache`) purely
to absorb the model's five interior Injekt reads, and `filterDownloaded` gained a `downloadCache`
parameter for the same reason.

The viewers take upstream's escape hatch instead: `PagerViewer`, `WebtoonViewer`, `WebGpuViewer` and
`ReadingMode` are plain objects the graph cannot build, so they read `activity.appGraph` lazily and
hand what they read to the config they construct.

Two scope rulings, both 2026-08-20:

- **`DownloadQueueViewModel` is not part of this port.** It is dead (`// RK: inert`, zero call sites),
  the download-queue plan keeps it verbatim so upstream syncs stay clean, and the off-path manifest
  has it waiting on Road B to retire it. The `DownloadQueueViewModel` conversion the roadmap names is
  mihonapp/mihon#3727, the collect-while-subscribed change, which is different work on the same file.
- **`DownloadPageLoader` keeps its Injekt read**, as upstream does. The argument for converting it was
  dropping the R8 keeps, and that payoff is not reachable anyway: `source-api` and `source-local` hold
  Injekt permanently under `eu.kanade.**`, `exh.**` and `tachiyomi.**`, because they are the contract
  installed extensions compile against. Only the `mihon.**` and `reikai.**` keeps can ever go.

Owner rulings, 2026-08-16:

- **Port everything, Reikai-owned trees included**, rather than mirroring upstream and leaving them on
  Injekt. The commit count the ruling named is not binding; the intent is, that the upstream-mirroring
  work lands before the Reikai tail so each diff stays reviewable against upstream's.
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

**Those numbers describe upstream's commit, not this tree.** Here the port is staged, so as of
2026-08-19 `AppModule` and `PreferenceModule` are deleted while `DomainModule` and
`mihon/core/migration/migrations/Migrations.kt` are still live, and our `AppGraph` has grown past
upstream's shape to 61 accessors and 23 `inject()` members.

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
large. The port started from roughly 506 of them (see Inventory); 82 remain, alongside 130
`by injectLazy()`.

### ViewModels

Hand-written factories go away. Screens call `metroViewModel<T>()` or
`assistedMetroViewModel<T, F> { create(...) }`, and every Compose entry point must provide
`LocalMetroViewModelFactory` from the graph (upstream does it inside `setComposeContent`).
`ReaderViewModel` is the one model that keeps a `CreationExtras` path, through Metro's
`ViewModelAssistedFactory` rather than a hand-written `viewModelFactory`. This tree matches that end
state: `ReaderViewModel` converted onto it in `aeecf08f3`, and it is the only model here using that
map. Every `CreationExtras.Key` in this tree is gone.

Two consequences worth stating: the rule that a bare-resolved model must never be `private` stops
applying on the Metro path, because the factory looks the class up in a generated map rather than
instantiating it reflectively; and upstream un-`private`d `TrackStatusSelectorScreen` for the
opposite reason, its factory needing to be visible.

### What stays on Injekt

`source-api` is deliberately left out of the Metro plugin list because it is the contract installed
extensions compile against.

**41 upstream files still contain an Injekt call after the commit. That is not a list of files
upstream left alone, and reading it as one is what caused the skipped-files unit above.** A file can
be rewritten and still keep one call: `BaseTracker` is in the 41 and upstream moved all four of its
fields onto a lazy `appGraph`, keeping only an `Injekt.get<Context>()` locator. Seven upstream files
carry Metro annotations and an Injekt call at once, so a partially migrated file is legal, which is
exactly why the total tells you nothing. **Decide per file, by diffing that file against upstream's
post-commit state.**

Genuinely untouched by upstream: the contract itself (`source-api`), the trackers as graph nodes
(they are hand-constructed inside `TrackerManager`, so they read the graph rather than being built
by it), and the interop bridge.

**Reikai's contract surface is wider than upstream's**, and this is the one failure with no compile
error: `DelegateSourcePreferences` is read from `HttpSource`,
`exh/source/EnhancedHttpSource` and `exh/metadata/metadata/EHentaiSearchMetadata`, and
`source-api`'s `MetadataSource` exposes three interactors as interface `get()`
accessors. Every one of those must be interop-registered or delegated sources throw on first use.

## Inventory, measured 2026-08-16 (historical: what the port started from)

**Every number in this section is the pre-port baseline and most are now wrong.** It is kept because
the sizing decisions above were made against it, not as a description of the tree. For current
figures, re-derive.

Re-derive with `grep -rl --include=*.kt --exclude-dir=build "uy.kohesive.injekt" app domain data core core-metadata source-api source-local presentation-core presentation-widget telemetry`.

Measured 2026-08-20, after the skipped-files unit: **88 files repo-wide, 82 of them in `app`** (45
under `eu`/`mihon`, 29 `reikai/`, 7 `exh/`), plus 5 in `source-api` and 1 in `source-local` that stay
there permanently. Down from 265. `data` and `presentation-widget` are clean.
**No `remember { Injekt.get() }` site remains**, down from 54, and 4 files hold both `@Composable`
and an Injekt call, down from 47. Those four are the end state, all of them `Injekt.get<Context>()`
locators: `AboutScreen`, `WorkerInfoScreen`, `SettingsEhScreen` and `DisplayRefreshHost`. **The
Compose path is finished**, and what is left is engine code. `AppGraph` carries 61 accessors and 23 `inject()` members, and `MetroInteropModule`
hands back 88 types. Phase 5 is untouched: 0 `@ContributesIntoSet` migrations against 31
`migrationContext.get<T>()` sites.

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

**This table is the landed record.** Phases 0 to 3c are history and accurate. Phase 4 grew sub-units
as it ran and phases 5 to 7 have not started, so for what is left read "What is left, in order" at the
top of Status instead; it is the list that gets maintained.

| Phase | Work | Proves |
|---|---|---|
| 0. Spike (done) | Plugin on `:app` and the new `:core:metro`, `AppBindings` providing `Json` only, an `AppGraph` with `inject(app)` plus two accessors, interop for that one type | Done: compile, 1064 tests, minified `:app:assemblePreview`, cold start to a populated library |
| 1. Leaves (done) | Annotate `core/common`, `domain`, `data`, `source-local`, plus the three upstream signature changes and the call sites they force in `app` and `exh` | Done: each module compiled alone, then 1064 `:app` and 75 `:domain` tests, a minified build and a manga browse list on device |
| 2. Graph (done) | `AppBindings` for the four infrastructure providers, accessors and interop for the leaf types `AppModule` / `PreferenceModule` registered, `App` bootstrap. The three module files shrink; they cannot be deleted until phases 3 to 6 empty them | Done: 1064 + 75 tests, a minified build, and on device a cold start, the full library and a backup within 14 bytes of the pre-Metro one |
| 3a. App classes, upstream (done) | Annotate and move the `eu.kanade` / `mihon` classes the module files register, plus the `data` repository implementations | Done: 1064 + 75 tests, minified build, cold start, library, details, tracking sheet |
| 3b. App classes, Reikai (done) | The `reikai` / `exh` classes, the fetcher list and both merge managers via `ReikaiBindings`, and the two `exh` classes in `core/common` | Done: 1064 + 75 tests, minified build, cold start, library, and a novel source browsed end to end through the QuickJS plugin host |
| 3c. Entry points (done) | All 15 workers, the 8 `setupTask` companions (through `Context.appGraph`), `AppModule` deleted with its two `addSingleton` calls and the warm-up moved into `App`, both widget surfaces plus their two refresh managers, and the activities, delegates and `NotificationReceiver` | Done: both update jobs to success, a cold start with the warm-up in `App`, both widgets rendering, and on a minified build the library, reader, WebView, a real tracker login, a download-queue notification action and the legacy extension installer |
| 4a. ViewModels (done) | metrox wired, `AppGraph : ViewModelGraph` with `ReikaiViewModelFactory`, the local at both `setComposeContent` roots, every plain and assisted model including the tracker sheet's eight, the migrate flow's seven, both engines and the EXH pair. Zero `CreationExtras.Key` remain and the one surviving `viewModelFactory` is the cover-factory initializer, which stays by ruling | Done: the screens each batch touched, driven on device |
| 4b. Composable reads, batches 1 and 2 (done) | The settings screens (`c3d1c4cd9`), then the remaining composables including four Reikai-owned ones (`9d85a35f6`). Graph at 55 accessors at that point | Done: both batches on a minified build, batch 2 on the emulator and the Fold |
| 4c. Composable reads, batch 3 (done) | The onboarding steps, `DisplayRefreshHost` and `SourceUtil` (`9ad07b8f4`), plus the orphan-import sweep (`d721de727`) | Done: onboarding walked, browse and the reader driven on a minified telemetry build |
| 4d. Composable reads, batch 4 (done) | The app-theme preview repaired onto `TachiyomiPreviewTheme` (`cbea79b08`), plus upstream's remembered read restored in `TachiyomiTheme` (`0af6be7da`) | Done: the owner confirmed the preview renders in both light and dark, each swatch in its own theme, and the production swatch row was driven on the emulator including the amoled toggle |
| 4e. The reader models and engine (done) | `ReaderViewModel` and the loaders behind it (`aeecf08f3`), then the viewers onto `activity.appGraph` (`c95ab27e6`) | Done on a minified emulator build: reader opens, a downloaded chapter renders, the saved page is restored, set-as-cover rewrites the cover and re-themes the page, and Save writes the file. **WebGPU is unexercised**, the emulator has no GPU, so it needs the Fold |
| 4f. The files no phase owned (done) | The backup subsystem (`fb6ba9ce0`), upstream's WorkManager parameter refactor (`110e3c6e7`), the downloader, notifiers and cover fetchers (`918377dfa`), the trackers (`1fe397998`), and the extension, update and cover-util tail (`9e56c41cd`) | Done: backup and restore across a full wipe, a chapter downloaded to a finished `.cbz`, and AniList plus Kitsu binds on the Fold |
| 5. Migrations | 16 migrations to `@ContributesIntoSet`, 31 context reads to constructor params. Untouched as of 2026-08-20: 0 and 31 respectively | Device: upgrade from an older `versionCode` and watch the migration log |
| 6. Reikai-owned | `reikai/` and `exh/`; the interop module shrinks as they land, but its floor is the novel reader's 17 types. Measured 2026-08-20: **29** files under `reikai/` and **7** under `exh/`, down from the 71 and 16 of the 2026-08-16 inventory. Includes the Reikai-owned cover models and library adders named in Status | Full device sweep: novels, EXH, recommendations, merge, migrate |
| 7. Cleanup | Regenerate both baseline profiles and rewrite the rules files. The `reikai.**` / `exh.**` proguard keeps stay: they leave with the tsundoku reader migration, not with this port | Minified `:app:assemblePreview`, then the profiles' own generation task |

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

- **The merge-manager cycle.** `MangaMergeManager` and `NovelMergeManager` take a lambda resolving
  `PropagateTrackerLinks` / `PropagateNovelTrackerLinks`, which depend on the managers. Metro rejects
  the direct cycle at compile time, so `ReikaiBindings` supplies that lambda from a `Provider`.
  Upstream has the same shape at `AndroidSourceManager` and solves it with `Lazy<DownloadManager>`.
- **`LnPluginHost` and `LnPluginLoader` took an `OkHttpClient`** built from `NetworkHelper.client`.
  `@Inject` alone cannot resolve a bare `OkHttpClient`, so both constructors take `NetworkHelper`
  rather than a global `OkHttpClient` binding that other code could bind by accident.
- **`AndroidSourceManager` builds sources inside an init flow collector and matches delegated sources
  by `sourceName`**, both invisible to Metro. It also breaks its own cycle with `downloadManager` and
  `exhPreferences` as `by injectLazy()` property delegates rather than constructor parameters, so the
  port needs `Provider`/`Lazy` injection there, not a parameter swap. Annotating it is safe only while
  the types those runtime lookups need are still interop-registered, and a miss compiles fine and
  throws on the first source-map build.
- **A model a test constructs directly keeps every parameter it had.** The migrate flow and the two
  engines were held to the end because their constructors carry test seams: the adapter a fake stands
  in for, the dispatcher a test drives, the provider lists. A direct constructor call bypasses the
  graph, so the seam survives conversion as long as the parameter does; what Metro cannot fill simply
  becomes `@Assisted`. That is why the six affected test classes pass unmodified, and it is the check
  to run on any future conversion: if a test builds it, the parameter list may not shrink.
- **The closure-capturing cover factory** in `EntryDetailsDialog` builds a star-projected
  `EntryCoverViewModel<*>` from a captured behaviour object. There is no upstream analogue and no
  graph key for it; design it before touching it.
- **`App.onCreate` ordering.** `LegacyYokaiDbImporter.prepareIfLegacyDb` must run after DI exists and
  before the database is first opened, so nothing `graph.inject(this)` builds may reach `Database`.
  Both widget managers did, through the updates and novel repositories, which is why `App` holds them
  as lambdas. A lazily built graph whose eager singletons touch SQLDelight breaks the recovery
  silently, and only the driver's own lazy connection pool kept it from firing.
- **The `:error_handler` process.** `CrashActivity` is the only component with `android:process`, so
  `App.onCreate` and therefore graph construction runs there too.
- **Widget surfaces are system-instantiated**, and the two of them inject from different places.
  `presentation-widget` took upstream's shape: the Metro plugin, `implementation(libs.metro.runtime)`
  in place of the `api(libs.injekt)` it leaked onto consumers, a `@ContributesTo` `PresentationWidgetGraph`
  the module declares its own `inject()` on, `@HasMemberInjections` on the base widget, and `context`
  threaded through `prepareData` now that the constructor no longer holds one. `UnifiedUpdatesGlanceWidget`
  is NOT a subclass of that base, so the module graph does not reach it: it injects through
  `AppGraph.inject()` like the workers, and threads `context` through four private functions.
- **8 `setupTask` companions** are called from five migrations, from `PreferenceRestorer` and from
  composable settings bodies. Two of them are the historical R8 crash sites, so they are exactly the
  code that stops being fragile once it is graph-resolved.

## Riding-along changes to decide, not inherit

Upstream's commit is not purely a DI change. Each of these needs an explicit yes or no.

**The file citations below are pre-port.** Items 2, 4 and 11 point at `AppModule.kt` and
`PreferenceModule.kt`, both deleted in 3c, so those line references are historical evidence for the
decision rather than places to look. The registrations they describe now live in `AppBindings` or
were dropped with the modules.

**Decided 2026-08-18 (owner).** Taken: 4, 5, 10 and 11, all of them already in the tree. Item 11 is
the one to say out loud, as it asked: the driver's `lock` plus `WeakReference` guard is gone, and
`@SingleIn(AppScope::class)` on `AppBindings.providesSqlDriver` carries the single-instance guarantee
instead. Rejected as a pattern: 10, where Reikai injects in `init` for every worker rather than
copying upstream's split. **Deferred until the DI port is finished:** 1, 2, 3, 6, 7 and 8. None is a
DI change; each is an API or behaviour change that happens to ride along, and taking them mid-port
would mix two kinds of risk in one commit. Revisit them as their own items after phase 7. Item 9
belongs to the ViewModel phase and is tracked there.

**Amended 2026-08-20.** Items 3, 6, 7 and 8 came in with the skipped-files unit, because the files
they touch could not convert without them. Only 1 and 2 are still deferred. Two halves were not
taken: `DownloadNotifier` is `@Inject` but not app-scoped, and `BackupFileValidator` keeps its
`context`, which the streaming reader needs where upstream decodes the whole file.

1. `source-api/.../util/RxExtension.kt` deleted. Public extension-lib surface.
2. `ConfigurableSource` switches `Injekt.get<Application>()` to `Injekt.get<Context>()`. Safe here:
   `AppModule` already registered both.
3. WorkManager threaded explicitly (`BackupRestoreJob.isRunning/start/stop` and
   `LibraryUpdateJob.startNow` take a `WorkManager`), which ripples into five call sites and is
   inconsistent upstream, since `LibraryUpdateJob.stop` still takes a `Context`.
4. `NetworkPreferences` swaps `verboseLoggingDefault: Boolean = false` for `@IsDebugBuild`.
   Behaviour-neutral here: `PreferenceModule` already passed `isDebugBuildType`.
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
    `AppModule`. Metro's `@SingleIn(AppScope::class)` gives one instance per graph and there
    is one graph per process, so it should subsume the case the guard covered, but this is a silent
    behaviour change and needs saying out loud rather than inheriting.

## Key files

- Upstream: `mihon/app/di/{AppGraph,AppBindings,AppGraphUtils,MihonViewModelFactory}.kt`,
  `mihon/app/di/injekt/MetroInteropModule.kt`, `core/metro/src/main/kotlin/mihon/core/metro/*.kt`,
  and `eu/kanade/tachiyomi/App.kt`, all at `b2015d1ef`.
- Here: `app/src/main/java/mihon/app/di/` (the graph, both binding containers, the ViewModel factory
  and the interop module), `app/src/main/java/eu/kanade/domain/DomainModule.kt` (the last Injekt
  module), `app/src/main/java/eu/kanade/tachiyomi/App.kt`, `scripts/di-interop-check.ps1` with the
  `pre-commit` hook and the `build_check` workflow step that run it, `app/proguard-rules.pro`,
  `app/src/main/baselineProfiles/`, and the two engines named under Traps. `AppModule.kt` and
  `PreferenceModule.kt` are deleted.

## Decisions and tradeoffs

- **Port everything rather than mirroring upstream only** (owner, 2026-08-16). Mirroring would have
  been about a third of the work and would have left Reikai-owned trees on Injekt indefinitely; the
  owner chose to end the split, with the Reikai half as its own follow-up commit so the
  upstream-mirroring diff stays reviewable against upstream's.
- **The R8 question was settled by inspecting the artifact, not by building.** Metro ships
  `META-INF/proguard/metro-runtime.pro` containing only two `-assumenosideeffects` rules, so it needs
  no keeps and adds no reflection. Our existing keeps stay only while Injekt calls remain in those
  packages, which phase 7 is what clears.
- **Migration order is not at risk.** `MigrationJobFactory` sorts by `version`, so moving from
  a hand-maintained list to a `Set<Migration>` multibinding cannot reorder anything.
- **The novel reader is not migrated.** See Status.
- **`i18n` needs no plugin.** It is the only multiplatform module and holds no Kotlin code.
- Documentation debt this creates: `.claude/rules/architecture.md` lines describing Injekt as the DI
  system, the `Injekt.get<Application>()` convention in `.claude/rules/screen-conventions.md`, and
  the R8 keep discipline in both become false and are rewritten in phase 7.
