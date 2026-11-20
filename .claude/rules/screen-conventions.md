---
alwaysApply: true
---

# Reikai screen conventions on Mihon

Mihon is already Compose + Navigation 3 throughout, so there is no legacy-to-Compose "port checklist" anymore. This file is the canonical version of the conventions every Reikai screen (ported from the `design/library-compose` reference, or net-new) must follow so it blends into the Mihon base. The short list in [CLAUDE.md](../../CLAUDE.md) is the index.

## When this applies

- Porting a Reikai screen onto Mihon (library, manga details, the light-novel surfaces).
- Adding a net-new screen to Reikai on the Mihon base.

## The conventions

### 1. A route and a screen function

A page is a `@Serializable` route, a `data class FooRoute(...) : NavKey` (a `data object` when it takes nothing), and a top-level `@Composable fun FooScreen(<the route's fields>)`, the shape upstream's routes take (`MangaRoute` / `MangaScreen`). A page the main display draws is registered once, in `reikai/navigation/ReikaiNavGraph.kt` `reikaiEntries()`; a bottom-bar tab is a `TopLevelRoute` on Home's navigator, and a track-sheet page is drawn by the sheet's own. Pushing a route registered nowhere crashes, and `RouteListGuardTest` (in `ReikaiRoutesTest.kt`) fails on a declared route missing from `ReikaiRoutesTest.routes`, so a new one is registered and listed together. Navigate with `LocalNavigator.current` (`mihon.core.navigation`).

**Routes carry identity, never editable content.** A field is what names the page (an id, a content type, a source key), so the back stack Android saves and restores holds nothing that can go stale; a page reads its content when it opens. Every field type is `@Serializable`, so no lambdas. A random default (a migration's `flowId`) survives a restore, because the encoder writes a field whose value differs from a freshly drawn default; `MigrationFlowIdTest` pins that. A check on what is shown (incognito, the migration flow, `shows`) is a function over the route's fields.

**Results go over the bus.** A page that answers the one below it sends a `ResultEventBus` result and pops; the page below collects it with `ResultEffect`, at most one composed collector per result key, and only in a route-level composable. Never reach into the stack for another page's state.

**An `ON_RESUME` effect also fires when an overlay is dismissed** (the track sheet is drawn over the details page, which pauses beneath it), so keep such an effect idempotent or gate it on an armed flag.

**A details page is addressed by row id; a listing open stores first.** Build every details page through `reikai/presentation/details/DetailsRoutes.kt` (`DetailsRoutesGuardTest` fails on any other construction), and open a tap on a listing through its `rememberDetailsOpener`, which stores a novel's row before it pushes.

### 2. Business logic in a `ViewModel`

State and side-effects live in an AndroidX `ViewModel` resolved via `viewModel<FooViewModel>()`. The composable becomes a renderer over `state.collectAsState()`. Static / pure-UI screens (license lists, simple pickers) may skip the ViewModel and must say so in a one-line comment: `// no ViewModel: pure UI, no async state.`

A model whose dependencies all come from the graph carries `@Inject`, `@ViewModelKey` and `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`, and the screen resolves it with `metroViewModel<FooViewModel>()`. One that also takes a value only the call site has (a `mangaId`, a `sourceId`) is `@AssistedInject` with those parameters `@Assisted`, plus an `@AssistedFactory` interface, and the screen uses `assistedMetroViewModel<FooViewModel, FooViewModel.Factory> { create(...) }`; `MangaViewModel` is the worked example. **A model resolved by a bare `viewModel<T>()` must never be `private`**: that factory instantiates reflectively, and a private top-level Kotlin class is package-private in bytecode, so the screen crashes on open in every build type including debug.

### 3. DI: constructor parameters, never inside `@Composable`

Dependencies arrive as constructor parameters on the ViewModel, which the Metro graph fills; a `Context` parameter is one of them, not `LocalContext`. No DI resolution belongs inside a `@Composable` body, because it couples the renderer to the container and breaks isolated preview and test. Where a composable genuinely needs a graph value (a settings screen building its preference list), hoist it: `val graph = remember { context.appGraph }` once at the top, then read fields off it. Do not introduce Koin. The full shape list, including assisted factories and a `() -> T` parameter for deferred construction, is in [architecture.md](architecture.md).

**Carve-out for ported upstream shapes (applies to conventions 3 and 5):** code ported from a Mihon or Komikku file may keep the upstream shape even where it breaks these two rules (Mihon ships its queue stores on raw `SharedPreferences`, and keeps `Injekt.get<Context>()` as a locator in the handful of non-composable readers that have no `Context` to hand). Staying character-close to the source keeps future syncs diffable. The rules bind net-new Reikai screens.

### 4. State via `StateFlow`

State exposed by the ViewModel is a `StateFlow`, declared on the model itself with a Kotlin explicit backing field (`val state: StateFlow<State>` then `field = MutableStateFlow<State>(...)`, as in `DeepLinkViewModel`). There is no state base class to extend: `StateViewModel<S>` and the `core:viewmodel` module were removed upstream (see [architecture.md](architecture.md)). Trivial UI-only state (a text field value, a tab index) may use `mutableStateOf` / `rememberSaveable` in the composable. No RxJava `Observable` / `Subject` / `Flowable` on the screen path; adapt at the boundary with `.asFlow()` if a dependency still returns Rx.

### 5. No preferences inside `@Composable`

Don't read `PreferenceStore` or a `*Preferences` holder inside a composable. Read in the ViewModel and expose preference values as fields of `state`.

### 6. Coroutines via `viewModelScope` or `rememberCoroutineScope`

ViewModel: `viewModelScope.launchIO { }` / `launchUI { }`. Composable: `rememberCoroutineScope().launch { }` or `LaunchedEffect`. Forbidden: `GlobalScope.launch(...)`, hand-rolled `CoroutineScope(Dispatchers.IO + Job())`. Work that must outlive the screen belongs in `WorkManager`.

### 7. Business logic out of `@Composable`

The composable describes what to render given a state. It should not run a state machine inline, branch on load state to decide what data to fetch, or make repository / service calls. Side-effects go in `LaunchedEffect` (one-shot) or the ViewModel. Cheap pure derivation (filtering a list before rendering) is fine in the composable; expensive or I/O work goes in the ViewModel.

### 8. Re-typed to Mihon, patches fenced

Ported code is re-typed against Mihon's immutable domain models and interactors. Net-new code lives in its own files/modules. Edits to Mihon's own files are fenced with `// RK -->` / `// RK <--` so they survive upstream merges and are greppable.

## Reference screen

A clean example of conventions 1-7 on the Mihon base is the library: [LibraryTab.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt) (a tab's screen function resolving `LibraryViewModel` via `metroViewModel<T>()`, rendering over `state.collectAsState()`) plus [LibraryViewModel.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt) (interactors as constructor parameters off the graph, state exposed as `StateFlow`, coroutines via `viewModelScope`). For the assisted shape, read [MangaScreen.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt) beside `MangaViewModel`. Read these before adding or porting a screen.

**Where a model's store comes from.** `viewModel()` inside a route resolves against that entry's own `ViewModelStore`, which the main display's `rememberViewModelStoreNavEntryDecorator` gives every entry and clears when the entry is popped. Content outside the main display (`ReaderActivity`, `WebViewActivity`) falls through to the Activity's store. **Each bottom-bar tab keeps a store of its own** (`reikai/navigation/TabViewModelStores.kt`), so two tabs resolving the same ViewModel type get two separate instances, which survive tab switches. Upstream's tabs share Home's store instead, and the Updates, History and Recents tabs each need their own feed models. The track sheet's pages each get their own store too, or every picker would reuse the first one's model.

## What this is not

- Not a license to refactor Mihon's screens. Touch Mihon code only to attach a Reikai feature, fenced with `// RK`.
- Not a justification for a standalone "fix the screens" sprint. Convention gaps get fixed when the screen is next touched for a feature or bug.
- Not a licence for native XML UI. Every new or ported screen is Compose, on a route; the reader's page views and the novel renderers are the View-based holdouts.
