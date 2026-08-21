---
alwaysApply: true
---

# Reikai screen conventions on Mihon

Mihon is already Compose + Voyager throughout, so there is no legacy-to-Compose "port checklist" anymore. This file is the canonical version of the conventions every Reikai screen (ported from the `design/library-compose` reference, or net-new) must follow so it blends into the Mihon base. The short list in [CLAUDE.md](../../CLAUDE.md) is the index.

## When this applies

- Porting a Reikai screen onto Mihon (library, manga details, the light-novel surfaces).
- Adding a net-new screen to Reikai on the Mihon base.

## The conventions

### 1. A Voyager `Screen` / `Tab` class

The entry point is a class extending Voyager's `Screen` / `Tab` (`cafe.adriel.voyager.core.screen.Screen`, `eu.kanade.presentation.util.Tab`), not a bare `@Composable fun FooScreen()`. The navigator only routes `Screen` instances; a bare composable can't be pushed, can't survive process death cleanly, and forces callers to thread arguments through composable parameters instead of constructor properties. A `@Composable` building block consumed by a `Screen.Content()` body is fine; the top-level entry must be a `Screen` / `Tab`.

Voyager `Screen` constructor args must be serializable (no lambdas: they crash on state-save). Route navigation via the local navigator inside `Content()`.

### 2. Business logic in a `ViewModel`

State and side-effects live in an AndroidX `ViewModel` resolved via `viewModel<FooViewModel>()`. The composable becomes a renderer over `state.collectAsState()`. Static / pure-UI screens (license lists, simple pickers) may skip the ViewModel and must say so in a one-line comment: `// no ViewModel: pure UI, no async state.`

A model whose dependencies all come from the graph carries `@Inject`, `@ViewModelKey` and `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`, and the screen resolves it with `metroViewModel<FooViewModel>()`. One that also takes a value only the call site has (a `mangaId`, a `sourceId`) is `@AssistedInject` with those parameters `@Assisted`, plus an `@AssistedFactory` interface, and the screen uses `assistedMetroViewModel<FooViewModel, FooViewModel.Factory> { create(...) }`; `MangaViewModel` is the worked example. **A model resolved by a bare `viewModel<T>()` must never be `private`**: that factory instantiates reflectively, and a private top-level Kotlin class is package-private in bytecode, so the screen crashes on open in every build type including debug.

### 3. DI: constructor parameters, never inside `@Composable`

Dependencies arrive as constructor parameters on the ViewModel, which the Metro graph fills; a `Context` parameter is one of them, not `LocalContext`. No DI resolution belongs inside a `@Composable` body, because it couples the renderer to the container and breaks isolated preview and test. Where a composable genuinely needs a graph value (a settings screen building its preference list), hoist it: `val graph = remember { context.appGraph }` once at the top, then read fields off it. Do not introduce Koin. The full shape list, including assisted factories and `Provider` for deferred construction, is in [architecture.md](architecture.md).

**Carve-out for ported upstream shapes (applies to conventions 3 and 5):** code ported from a Mihon or Komikku file may keep the upstream shape even where it breaks these two rules (Mihon ships its queue stores on raw `SharedPreferences`, and keeps `Injekt.get<Context>()` as a locator in the handful of non-composable readers that have no `Context` to hand). Staying character-close to the source keeps future syncs diffable. The rules bind net-new Reikai screens.

### 4. State via `StateFlow`

State exposed by the ViewModel is a `StateFlow`, declared on the model itself with a Kotlin explicit backing field (`val state: StateFlow<State>` then `field = MutableStateFlow<State>(...)`, as in `MangaViewModel`). There is no state base class to extend: `StateViewModel<S>` and the `core:viewmodel` module were removed upstream (see [architecture.md](architecture.md)). Trivial UI-only state (a text field value, a tab index) may use `mutableStateOf` / `rememberSaveable` in the composable. No RxJava `Observable` / `Subject` / `Flowable` on the screen path; adapt at the boundary with `.asFlow()` if a dependency still returns Rx.

### 5. No preferences inside `@Composable`

Don't read `PreferenceStore` or a `*Preferences` holder inside a composable. Read in the ViewModel and expose preference values as fields of `state`.

### 6. Coroutines via `viewModelScope` or `rememberCoroutineScope`

ViewModel: `viewModelScope.launchIO { }` / `launchUI { }`. Composable: `rememberCoroutineScope().launch { }` or `LaunchedEffect`. Forbidden: `GlobalScope.launch(...)`, hand-rolled `CoroutineScope(Dispatchers.IO + Job())`. Work that must outlive the screen belongs in `WorkManager`.

### 7. Business logic out of `@Composable`

The composable describes what to render given a state. It should not run a state machine inline, branch on load state to decide what data to fetch, or make repository / service calls. Side-effects go in `LaunchedEffect` (one-shot) or the ViewModel. Cheap pure derivation (filtering a list before rendering) is fine in the composable; expensive or I/O work goes in the ViewModel.

### 8. Re-typed to Mihon, patches fenced

Ported code is re-typed against Mihon's immutable domain models and interactors. Net-new code lives in its own files/modules. Edits to Mihon's own files are fenced with `// RK -->` / `// RK <--` so they survive upstream merges and are greppable.

## Reference screen

A clean example of conventions 1-7 on the Mihon base is the library: [LibraryTab.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt) (a Voyager `Tab` resolving `LibraryViewModel` via `metroViewModel<T>()`, rendering over `state.collectAsState()`) plus [LibraryViewModel.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt) (interactors as constructor parameters off the graph, state exposed as `StateFlow`, coroutines via `viewModelScope`). For the assisted shape, read [MangaScreen.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt) beside `MangaViewModel`. Read these before adding or porting a screen.

**Voyager still routes; only the models moved.** Screens and tabs remain Voyager `Screen` / `Tab` classes, and `viewModel()` inside one resolves against that Screen's own `ViewModelStore`, because `voyager-navigator` gives every Screen an `AndroidScreenLifecycleOwner` that clears the store on pop. That holds only under a `Navigator`: content hosted outside one (`ReaderActivity`, `WebViewActivity`) falls through to the Activity store silently. A `Tab` is a `Screen`, so it gets its own store on the same terms: `TabNavigator` builds a real `Navigator`, and the per-screen `AndroidScreenLifecycleOwner` is keyed by `Screen.key`, which defaults to the class name. **Two tabs resolving the same ViewModel type therefore get two separate instances**, and those survive tab switches, because `TabNavigator` passes `disposeSteps = false` so the replace that switches tabs does not dispose the outgoing one. (Verified against voyager 2.2.21 sources 2026-08-08, correcting an earlier note here that claimed tabs share one store.)

## Sanctioned exception: the edit-info form is native XML

One surface is deliberately not Compose: the shared manga/novel **edit-info form** ([EntryEditInfoDialog.kt](../../app/src/main/java/reikai/presentation/details/EntryEditInfoDialog.kt), inflating [edit_entry_info.xml](../../app/src/main/res/layout/edit_entry_info.xml) via `AndroidView`, ported from Komikku's `EditMangaDialog`). A pure-Compose form could not keep the soft keyboard stable on the user's edge-to-edge Android 15+ device: the keyboard closed and reopened on every field switch, and the focused field either hid behind the keyboard or left a keyboard-height gap (reproduced on Samsung and SwiftKey keyboards, so app-side, not keyboard-specific). Native `EditText` handles IME focus cleanly where Compose's `bringIntoView` on A15+ does not. The known-good host is Komikku's exact shape: a Compose Material3 `AlertDialog` (wrap-content, floats over the details page) whose scrolling `Column` wraps the native `LinearLayout` form. A full-screen Voyager `Screen` host was tried and rejected: its edge-to-edge window does not resize for the IME, so the short native form sat above a keyboard-height void. Only the field UI is native; the dialog chrome (Save/Cancel) stays Compose, and it is dispatched from the details `ViewModel` as a `Dialog` case exactly like the novel `EditInfo` dialog. Do not re-attempt a pure-Compose editor form or a full-screen-Screen host; both paths are exhausted. This is the only view-based holdout besides the reader.

## What this is not

- Not a license to refactor Mihon's screens. Touch Mihon code only to attach a Reikai feature, fenced with `// RK`.
- Not a justification for a standalone "fix the screens" sprint. Convention gaps get fixed when the screen is next touched for a feature or bug.
- Not a general licence for native XML UI. The edit-info form above is the single sanctioned exception (keyboard/IME maturity); every other new or ported screen is Compose + Voyager.
