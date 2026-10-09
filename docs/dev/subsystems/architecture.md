# App architecture

## Purpose

The wiring every screen and background job sits on: how objects are built and shared (the Metro graph and the Injekt surface kept for extensions), how a screen holds its state (AndroidX ViewModels with no base class), how stored settings are upgraded between versions (preference migrations gated on `versionCode`), and how the Settings screens are laid out and found by search. The rules a change must follow are [architecture.md](../../../.claude/rules/architecture.md) and [screen-conventions.md](../../../.claude/rules/screen-conventions.md); this page describes how the pieces work and the traps behind those rules.

## How it works

### The Metro graph

Metro resolves dependencies in the compiler, so a missing binding is a build error and nothing is looked up by reflection at runtime. `App` implements `GraphProvider<AppGraph>` (`:core:metro`), builds the graph once per process and calls `graph.inject(this)`; anything with a `Context` reaches it as `context.appGraph`.

- `AppGraph` is Mihon's graph in Mihon's order. It extends `ViewModelGraph` and `ReikaiGraph`, which holds Reikai's own `inject()` members and accessors, so a Reikai addition never edits Mihon's member list.
- Providers that cannot be an annotated class live in binding containers: `AppBindings` (Mihon's `Json`, `XML`, `ProtoBuf`), `ReikaiBindings` (the two merge managers and the tracker-library fetcher list), and `DatabaseBindings` in `:data` (`SqlDriver`, `Database`).
- Every other class joins by carrying `@Inject`, with `@SingleIn(AppScope::class)` for a singleton and `@ContributesBinding(AppScope::class)` for an implementation of an interface. Migrations join a set with `@ContributesIntoSet`, ViewModels a map with `@ContributesIntoMap`.

A dependency whose construction must wait is a `() -> T` parameter. Metro hands the provider through without calling it, so the object is built when the parameter is invoked. `NovelDownloadManager` is the one that matters: building it restores the saved download queue and can start the download worker, so every reader takes it deferred, and `DeleteNovelChaptersAfterRead` is the choke point the library, details, updates and the notification receiver all reach it through.

### The Injekt surface

Installed extensions compile against `source-api`, which resolves its dependencies through Injekt. `MetroInjektRegistrar` is the whole Injekt surface: a read-only registrar assigned over the global `Injekt` in `App.onCreate`, with fifteen bindings. `Application` and `Context` return the application; the other thirteen each read one `AppGraph` accessor (`Json`, `ProtoBuf`, `XML`, `NetworkHelper`, `JavaScriptEngine`, plus Reikai's `TrackerManager`, `TrackPreferences`, `DelegateSourcePreferences`, `ExhPreferences`, `EHentaiUpdateHelper` and the three `MetadataSource` interactors). Every write method throws, so nothing registers at runtime, and a type it omits throws `InjektionException` at the read. The built-in adult and MangaDex sources resolve through it the same way an extension does, which is why Reikai's list is longer than Mihon's.

### ViewModels

A screen model extends `androidx.lifecycle.ViewModel` directly and declares its own `val state: StateFlow<S>` with an explicit backing field; there is no state base class and no Voyager `ScreenModel`. Resolution goes through `ReikaiViewModelFactory`, the graph's `MetroViewModelFactory`, which `setComposeContent` provides as `LocalMetroViewModelFactory`:

- A model whose dependencies all come from the graph carries `@ViewModelKey` and is resolved with `metroViewModel<T>()`.
- A model that needs a call-site value (an entry id, a source id) is `@AssistedInject` with an `@AssistedFactory` keyed by `@ManualViewModelAssistedFactoryKey`, resolved with `assistedMetroViewModel<T, F> { create(...) }`.
- The reader's models live outside any Voyager `Navigator`, so `ReaderActivity` scopes them to itself with `by viewModels { graph.viewModelFactory }`.
- Two models are still built by hand through `viewModelFactory`: the cover model in `EntryDetailsDialog` (star-projected, built from the behavior object and keyed by `coverKey`, the source chip's entry) and `NovelSourceSettingsModel`.

Under a `Navigator`, `viewModel()` resolves against that Screen's own store, cleared on pop; tabs each get their own store that survives tab switches. The full scoping rule is in screen-conventions.md.

### Preference migrations

A preference migration implements `Migration` with a `version` and joins the set. `App.initializeMigrator` reads `graph.migrations` after the `:error_handler` early return and hands it to `Migrator` with the stored `last_version_code` and the build's `versionCode`. `MigrationStrategyFactory` picks the strategy: a fresh install runs only the `Migration.ALWAYS` ones and stamps the rest done, an upgrade runs those whose version falls in `old < version <= new`, in version order (`MigrationJobFactory` sorts). So a new migration only fires once the shipped `versionCode` reaches its gate, which is why adding one bumps `versionCode` to a fresh number; each gates on its own number, never a reused one. A SQLDelight `.sqm` is separate and needs no bump ([data-and-backup.md](data-and-backup.md)).

`MainActivity` holds the splash on `Migrator.awaitAndRelease()`. Code that can run without it (WorkManager workers, the download stores) calls `Migrator.await()` first.

### Settings screens and search

Each top-level Settings screen is a `SearchableSettings` object (a Voyager `Screen`) returning a list of `Preference` groups; `SettingsMainScreen` lists the root entries. Settings search builds its index from the `settingScreens` list in `SettingsSearchScreen.kt`, one entry per screen, and indexes every row with a non-blank title under a `HighlightKey` of (group, title), which is what scrolls to and highlights the row a result opens. A `CustomPreference` with a blank title (the About logo, its link icons) never becomes a result.

`SearchableSettings.isEnabled()` suspends, because the MangaDex gate waits for the extension scan. Search resolves every gate once with `produceState` and shows nothing until they answer; Browse and sources reads the MangaDex gate the same way.

The layout, by subject:

- **Manga reader** and **Novel reader** are two top-level screens, each holding every setting for its reader. Settings with the same name on both (keep screen on, the volume-key trio, skip duplicates, mark read on skip) are separate keys per type; bottom buttons offer a different option set per type (`ReaderBottomButton.Scope`), while default rotation offers one list, `readerOrientationChoices`.
- **Browse and sources** holds source configuration: extension stores, the adult-sources gate and the delegated-sources switch, then the Source settings group the gate reveals (MangaDex and E-Hentai as drill-downs), and page preview rows.
- **Library** holds the merged-series group (the merge switch, auto-merge, preferred sources) and per-type global update groups, including the update-error tracking switches. Rows that differ by type carry a `· Manga` / `· Novels` suffix from `contentTypedCategory` in `Commons.kt` where they would otherwise read as one row printed twice.
- **Recommendations** is its own top-level screen.
- **About** is built on the preference DSL, so its version, update check and legal links are searchable.
- **Advanced** groups its loose rows under Debugging, Help and Background activity, and keeps the destructive library maintenance (clearing merges, repairing novel details) in its Library group.

## Key files

- `app/src/main/java/mihon/app/di/AppGraph.kt`: the graph, `migrations`, `viewModelFactory`.
- `app/src/main/java/reikai/di/ReikaiGraph.kt`: Reikai's `inject()` members and accessors.
- `app/src/main/java/mihon/app/di/AppBindings.kt` and `ReikaiBindings.kt`: the binding containers; `data/src/main/java/tachiyomi/data/DatabaseBindings.kt` for the database.
- `app/src/main/java/mihon/app/di/injekt/MetroInjektRegistrar.kt`: the fifteen Injekt bindings.
- `app/src/main/java/mihon/app/di/ReikaiViewModelFactory.kt`: the ViewModel factory over both maps.
- `app/src/main/java/eu/kanade/tachiyomi/util/view/ViewExtensions.kt`: `setComposeContent`, which provides `LocalMetroViewModelFactory`.
- `core/metro/src/main/kotlin/mihon/core/metro/GraphProvider.kt`: `GraphProvider`, `metroGraph`.
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`: graph construction, `initializeMigrator`, the `:error_handler` gate.
- `app/src/main/java/mihon/core/migration/Migrator.kt` and `MigrationStrategy.kt`: `initialize`, `await`, the version-range strategies.
- `app/src/main/java/mihon/core/migration/migrations/`: every preference migration.
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt`: `settingScreens`, the gate resolution.
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SearchableSettings.kt`: `SearchableSettings`, `isEnabled`, `HighlightKey`.
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`: the root list.
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMangaReaderScreen.kt` and `SettingsNovelReaderScreen.kt`.
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt`: About on the DSL.
- `scripts/di-interop-check.ps1`: the checks the compiler cannot make.

## Invariants and traps

- **Changing a lazy read to a constructor parameter changes when the object is built.** A dependency whose `init` starts work then starts it wherever its owner is built, transitively: the novel library once resumed downloads through `SetNovelReadStatus` to `DeleteNovelChaptersAfterRead`. Read the type's `init` and its whole closure before promoting it, and keep a deferral as `() -> T`.
- **A new screen joins `settingScreens`, or search never sees it.** Nothing fails; its rows are just absent.
- **Moving a row to another group changes its `HighlightKey`.** A stale key fails silently: the result opens the screen but does not scroll.
- **Never push a screen from `SourcePreferencesScreen`.** It hosts a Fragment behind a one-time commit guarded by `rememberSaveable`, and coming back from a pushed screen leaves the body empty.
- **A gate read in a composable is observed state, never a bare `isEnabled()` call.** A one-shot read does not update when the preference flips, so the row appears only after the screen is recreated.
- **Workers inject in `init`, not at the top of `doWork`.** WorkManager may call `getForegroundInfo` first, and `NovelDownloadWorker` reads an injected field there.
- **`App.onCreate` also runs in `:error_handler`.** The migration set and the image loader's dependencies are read off the graph after that process's early return, since building them there would load WebView and could take down the crash screen.
- **A model a test constructs keeps every parameter.** What the graph cannot fill becomes `@Assisted`, so the test seam survives; a conversion that shrinks a tested constructor breaks the test's isolation.
- **`viewModelScope` is cancelled before `onCleared()` runs.** Cleanup written as a launch inside `onCleared()` never happens; it must be synchronous.
- **No `flowWithLifecycle` inside a ViewModel.** The model outlives the Activity, so a captured `Lifecycle` is a dead one after rotation; use `stateIn(WhileSubscribed)` with a lifecycle-aware collect instead.
- **The novel merge manager and its tracker propagator form a cycle.** `ReikaiBindings` supplies the propagator as `() -> PropagateNovelTrackerLinks`, cutting it at one edge.
- **The checker sees what the compiler does not.** `di-interop-check.ps1` fails on an Injekt read the registrar does not bind, an unscoped or unread binding, an Injekt registration, and a ViewModel or migration left out of its multibinding; it runs from the `pre-commit` hook and CI.

## Decisions

- **Reikai code is on Metro too, not only Mihon's files.** Leaving Reikai trees on Injekt would have kept two DI systems indefinitely and the R8 signature hazard with them.
- **The Injekt surface is a closed allow-list.** A binding is added only for a reader that cannot take a constructor parameter (an extension, `source-api`); net-new code goes in the graph.
- **The migration set is an accessor read, not an injected `App` field.** A field would build every migration at `graph.inject`, in `:error_handler` too.
- **The two readers' same-named settings stay separate per type.** They are ergonomics, not rules a user sees diverge, and paged images and scrolling text want different answers; void if a setting gains behaviour both readers must share.
- **Two reader screens, not a hub with drill-downs.** A hub would cost every manga reader an extra tap, and a shared row shown on both screens reads as two settings.
- **Source settings live on Browse and sources, not at the root.** MangaDex and E-Hentai were at the root only because their preferences are app-owned, which a user cannot know.
- **Upstream's placement of rows in Advanced, Library and Reader stays** unless the row is Reikai's own; Advanced's untitled top block is the one regrouped.

## Upstream divergences

`// RK` islands in `AppGraph.kt` (Reikai's binding container and `ReikaiGraph`), `MetroInjektRegistrar.kt` (Reikai's eight extra bindings), `App.kt` (the migration accessor, the image loader reads), `SettingsSearchScreen.kt` (the screen list, `HighlightKey`), `SettingsMainScreen.kt`, `SettingsBrowseScreen.kt`, `SettingsAdvancedScreen.kt`, `SettingsLibraryScreen.kt` and `SourcePreferencesScreen.kt`. `AboutScreen.kt` is a whole-file rewrite. Recorded divergences: [upstream-sync.md](../upstream-sync.md) "Deliberate divergences" (`AboutScreen`).

## Extending

- **A new injected class**: `@Inject`, plus `@SingleIn(AppScope::class)` if it is shared; take dependencies as constructor parameters. Code with no constructor reads `context.appGraph.x`, adding the accessor to `ReikaiGraph`.
- **A new screen model**: `@ViewModelKey` and `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`, or the assisted shape for a call-site value; resolve it with `metroViewModel` / `assistedMetroViewModel`.
- **A new preference migration**: `@Inject` and `@ContributesIntoSet(AppScope::class)`, `version` set to the next unused `versionCode`, and bump `versionCode` in `app/build.gradle.kts` to match; check whether a worker that reads the migrated state already awaits `Migrator`.
- **A new settings screen**: a `SearchableSettings` object, an entry in `SettingsMainScreen` and in `settingScreens`.

## Tests

`MetroInjektRegistrarTest` (all fifteen bindings resolve through `fullType`), `ReikaiViewModelFactoryTest` (a model resolves only once contributed), `MigratorTest` (strategy selection and ordering), plus one test per migration under `app/src/test/java/mihon/core/migration/migrations/`. `pwsh scripts/di-interop-check.ps1` runs the graph checks. Run a class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`.

## Related

- Rules: [architecture.md](../../../.claude/rules/architecture.md), [screen-conventions.md](../../../.claude/rules/screen-conventions.md).
- Module overview: [development.md](../development.md).
