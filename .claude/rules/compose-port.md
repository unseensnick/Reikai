---
alwaysApply: true
---

# Compose + Voyager port checklist

Every new Compose screen, and every screen migrated from a legacy Conductor `*Controller`, must pass the nine items below. This file is the canonical source; the short bullet list in [CLAUDE.md](../../CLAUDE.md) is the index. The current debt snapshot lives in [docs/dev/compose-port-status.md](../../docs/dev/compose-port-status.md) and decays over time; re-audit before any cross-cutting port.

## When this applies

- A new screen being added to the Compose+Voyager surface.
- A legacy `*Controller` + `*Presenter` pair being ported.
- A "flipped" settings screen (Compose-default) that gains a new feature.

When fixing legacy code that has not yet been migrated, follow the legacy pattern (see [architecture.md](architecture.md)). Don't half-migrate a screen mid-fix.

## The nine items

### 1. Extends Voyager `Screen` (or `Tab`)

The screen is a class that extends either Voyager's `Screen` interface directly (`cafe.adriel.voyager.core.screen.Screen`) or the project's `yokai.util.Screen` wrapper. Not a bare `@Composable fun FooScreen()`. Voyager's navigator only routes `Screen` instances; a bare composable cannot be pushed onto the stack, can't survive process death cleanly, and forces callers to thread arguments through composable parameters instead of constructor properties.

Either entry shape is fine. `LibraryScreen` uses the Voyager interface directly (`class LibraryScreen : Screen`); `ExtensionRepoScreen`, `AboutScreen`, and the `ComposableSettings` subclasses use the project wrapper (`class Foo : Screen()`). The wrapper is mildly preferred for new screens since it carries project helpers, but mixing is acceptable.

Acceptable exception: a `@Composable` building block consumed by a `Screen.Content()` body. The top-level entry point must still be a `Screen`. A Compose body currently hosted by a legacy Conductor `*Controller` (e.g., `OnboardingController` hosting `OnboardingScreen`) is the inverse case: the body is fine in isolation but the next migration step is to wrap it in a Voyager `Screen` so the host can switch from Conductor to Voyager.

### 2. Business logic in a `ScreenModel`

State and side-effects live in a `ScreenModel` resolved via `rememberScreenModel { ... }`. The composable becomes a renderer over `state.collectAsState()`.

Static or pure-UI screens (license lists, storybook demos, onboarding step flows with no async work) may skip the ScreenModel. When they do, the screen must say so in a one-line comment so reviewers don't read the absence as an oversight: `// no ScreenModel: pure UI, no async state.`

### 3. DI: no Injekt inside `@Composable`

The hard rule: `Injekt.get<>()` and `injectLazy()` never appear inside a `@Composable` function body.

The soft rule: new ScreenModels prefer Koin (`KoinComponent` + `by inject()`, or constructor injection registered in `yokai/core/di/`). Legacy `injectLazy()` at ScreenModel *class level* is acceptable during migration since the existing ScreenModels mix both patterns; rewriting working class-level injects for no behavioral change is not a project goal.

**Why the asymmetry**: composable reach-through couples the UI layer to the DI container and makes it harder to preview or test the composable in isolation. Class-level inject in a ScreenModel is one indirection further down and doesn't poison the renderer.

### 4. State via `StateFlow`

State exposed by the ScreenModel is a `StateFlow` (typically via `StateScreenModel<S>` from Voyager). For trivial UI-only state (a text field value, a current tab index), `mutableStateOf` / `rememberSaveable` inside the composable is fine.

No RxJava `Observable` or `Subject` in the screen path. If a legacy repository still returns Rx, adapt it at the ScreenModel boundary with `.asFlow()` and expose `StateFlow` from there.

### 5. No `PreferencesHelper` inside `@Composable`

Don't pull `preferences` into a composable to call `.libraryLayout().collectAsState()` directly. Read preferences in the ScreenModel and expose them as part of `state`. The composable should see preference values as state fields, not as a `PreferencesHelper` reference.

The exception during migration is screens that *only* render preferences (a flat settings screen with no domain logic). For those, the screen still violates the rule strictly but can be left until it gains a ScreenModel for an unrelated reason. The status doc tracks these.

### 6. Coroutines via `screenModelScope` or `rememberCoroutineScope`

In a ScreenModel: launch via `screenModelScope.launchIO { }` / `screenModelScope.launchUI { }`.
In a composable: launch via `rememberCoroutineScope().launch { }`, inside `LaunchedEffect`, or via a `coroutineScope` captured at the top of `Content()`.

Forbidden anywhere on the screen surface: `GlobalScope.launch(...)`, hand-rolled `CoroutineScope(Dispatchers.IO + Job())`.

When work genuinely must outlive screen scope (a download cleanup the user may back out of mid-run, a long migration, etc.), the right tool is `WorkManager` (the codebase already uses it via `LibraryUpdateJob`, `BackupCreatorJob`, etc.), not `GlobalScope`. Treat any existing `GlobalScope` call on a Compose screen as a WorkManager-ification candidate, not as evidence that `GlobalScope` is acceptable.

### 7. Rx-free public surface

The ScreenModel's public state and event API uses Kotlin coroutines types only. No `Observable`, no `Subject`, no `Flowable` typed members reachable from the composable. Internal use is fine if it's adapted to `Flow` before crossing the boundary.

### 8. No legacy `*Controller` launches without a bridge comment

A new Compose screen should not push a Conductor `*Controller` to do its work. The exception is an explicitly-marked transitional bridge during a multi-phase port (e.g., `LibraryScreen` still launching `MangaDetailsController` until the details port lands).

When a transitional bridge is genuinely needed, the call site must carry a comment in this form:

```kotlin
// transitional: legacy MangaDetailsController until MangaDetailsScreen ports
router.pushController(MangaDetailsController(manga).withFadeTransaction())
```

The presence of the comment signals "yes, this violates rule 8, and yes, it's intentional." Reviewers can grep for `transitional: legacy` to find every active bridge.

Do not retroactively annotate existing bridges. The comment convention applies to new bridge sites; existing ones get annotated when next touched.

### 9. Business logic out of `@Composable`

The composable should describe what to render given a state. It should not:

- Run a state machine inline (`when (loadState) { is Loading -> ... is Loaded -> fetchMoreIfNeeded() ... }`).
- Decide what data to load based on multiple branches.
- Make direct repository / service calls.

Side-effects belong in `LaunchedEffect` (one-shot) or in `screenModelScope` inside the ScreenModel. Pure derivation of one state into another (filtering a list before rendering it) is fine in the composable if it's cheap; if it's expensive or involves I/O, push it into the ScreenModel.

## Reference pattern

The cleanest current example of all nine items passing is [ExtensionRepoScreen.kt:54](../../app/src/main/java/yokai/presentation/extension/repo/ExtensionRepoScreen.kt:54):

- Extends `Screen()`.
- Resolves two ScreenModels via `rememberScreenModel { ExtensionRepoScreenModel() }` / `rememberScreenModel { LnRepoScreenModel() }`.
- No DI calls inside the composable; DI happens in the ScreenModel classes via Koin.
- State via `state.collectAsState()`.
- Coroutines via `rememberCoroutineScope()` in the composable, `screenModelScope.launchIO` in the ScreenModel.
- No legacy controller bridge.
- Composable body is pure render dispatch.

When in doubt, read this screen and its two ScreenModels first.

## What this checklist is not

- It is not a permission slip to refactor legacy screens. Legacy `*Controller` + `*Presenter` code stays as-is until that section is on the migration roadmap.
- It is not a justification for a standalone "fix the failing screens" sprint. Failing screens get fixed opportunistically when next touched for a feature or bug, not as a cleanup pass.
- It is not a hard rule for the four "novel probe" surfaces in `yokai/presentation/novel/*` that are explicitly debug/probe scaffolding. Those will either harden into proper Screens or get deleted when the LN feature lands; don't gate them on the checklist meanwhile.
