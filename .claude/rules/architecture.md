---
alwaysApply: true
---

# Project architecture

Reikai is built on Mihon. Mihon is **Compose + Voyager throughout**, so the Yōkai-era split (Conductor `*Controller` + RxJava `*Presenter` for legacy screens, Compose for new ones) does not exist here. There is one UI stack.

## Compose + Voyager (the only stack)

Screens are Voyager `Screen` / `Tab` classes whose `Content()` resolves an AndroidX `ViewModel` via `viewModel<FooViewModel>()` and renders over `state.collectAsState()`. Navigation is a Voyager `Navigator` (see `MainActivity` and `HomeScreen`). There is no Conductor `Router` and no `*Presenter`.

Voyager routes; AndroidX holds the state. Reikai took Mihon's move off `ScreenModel` (mihonapp/mihon#3594, mihon `c3b99aea0`), so a model extends androidx `ViewModel()` directly and the scope is `viewModelScope`. **There is no state base class.** Upstream's `StateViewModel<S>` and the `core:viewmodel` module were removed in a later sync (mihonapp/mihon#3763, mihon `b1efee47f`); a model now declares its own `val state: StateFlow<S>` with a Kotlin explicit backing field, as `MangaViewModel` does (`) : ViewModel() {`, then `val state: StateFlow<State>` / `field = MutableStateFlow<State>(State.Loading)`). The syntax needs no compiler flag, and a backing field is private to its declaring class, so a base that writes subclass state is not expressible. The novel reader is the last screen still on `ScreenModel`, held there deliberately because the tsundoku reader migration deletes it; `voyager-screenModel` stays in the build until then.

The one View-based holdout, shared with upstream, is the **reader** (`ReaderActivity` + `PagerViewer` / `WebtoonViewer`). Reader tweaks are View edits, not Compose work.

## Reikai screens on Mihon

Reikai's ported screens (library, manga details, the light-novel surfaces) follow Mihon's conventions (see [screen-conventions.md](screen-conventions.md)) and are re-typed to Mihon's models. Two placement rules:

- **Net-new code lives in its own files/modules** (own ViewModels, own `.sq` tables, own Voyager screens).
- **Edits to Mihon's own files** (the nav tab list, backup proto fields, DI registration) are fenced with `// RK -->` / `// RK <--` comment islands so they survive upstream merges and are greppable. Mirrors Komikku's `// SY` / `// KMK` convention.

## Dependency injection

DI is **Metro** (`dev.zacsweers.metro`), resolved by the compiler rather than at runtime. The graph is `app/src/main/java/mihon/app/di/AppGraph.kt`, with its providers in `AppBindings` (upstream's) and `ReikaiBindings` (ours) beside it. A class joins the graph by carrying `@Inject` on itself and taking its dependencies as constructor parameters; add `@SingleIn(AppScope::class)` when it must be an application singleton, and `@ContributesBinding(AppScope::class)` on an implementation bound to an interface. Do not introduce Koin.

Three shapes for the cases a plain parameter cannot cover:

- **A value that only exists at the call site** (a `mangaId`, the live model a screen already resolved) is `@Assisted`, with an `@AssistedFactory` the graph hands over. `MangaEntryCoverViewModel` and the four library and recents adapters are the worked examples.
- **A dependency whose construction must stay deferred** is a `Provider<T>` parameter, never a lazy delegate: same timing, and the reason sits in the type. `NovelDownloadManager` is the one that matters, because building it restores the persisted download queue and can start the worker.
- **Code that cannot take a constructor parameter at all** (an object, a top-level function, a companion) reads `context.appGraph.x`. Where there is no `Context` either, `Injekt.get<Context>()` survives purely as a locator, which is the shape upstream kept for the same case.

**Injekt is not gone, and the two survivors are permanent or ruled.** `source-api` and `source-local` keep it because they are the contract installed extensions compile against, and `eu/kanade/domain/DomainModule.kt` keeps twelve registrations for those contracts plus the novel reader, which stays on Injekt until the tsundoku migration. `MetroInteropModule` hands graph-owned singletons back to Injekt so extensions can still resolve them. Do not add to any of that: net-new code goes in the graph. `DomainModuleTest` resolves the twelve for real, so removing one that is still needed fails there rather than at runtime.

No DI resolution of any kind inside a `@Composable` body: read it in the ViewModel, or `remember { context.appGraph.x }` at the top of the composable.

## Minification (R8) and net-new packages

Release-type builds (`release` / `nightly` / `foss`) are minified (`isMinifyEnabled = true` on `release`, which `nightly` and `foss` take by `initWith`); the `debugY2k` dev build is NOT, so R8-only bugs are invisible in the normal dev loop. **Metro resolves the graph at compile time and reflects on nothing, so graph-owned code carries no minification hazard of its own.** The hazard that remains belongs to the surviving Injekt calls: R8 strips the generic `Signature` that Injekt's `FullTypeReference` reflects on, so an `Injekt.get<T>()` / `injectLazy<T>()` in a package outside the keep list crashes the minified build with `IllegalArgumentException: Internal error: TypeReference constructed without actual type information`.

`app/proguard-rules.pro` keeps `eu.kanade.**` / `tachiyomi.**` / `mihon.**`, which are upstream's own and stay whatever Reikai does, plus `reikai.**` and `exh.**`, which are ours. **All five are permanent, and do NOT leave with the tsundoku reader migration** (ruled 2026-08-21, after being overstated twice): `reikai.**` because `Novel.hasCustomCover` keeps a reified `Injekt.get()` default that is itself ruled to stay for twin parity with the manga side, and `exh.**` because `source-api` reads `DelegateSourcePreferences` and `exh/debug/DebugToggles.kt` holds a reified read of its own. Evidence in [metro-di-migration.md](../../docs/dev/plans/metro-di-migration.md). A net-new top-level package needs its own keep line only if something in it resolves through Injekt, which for new code should be nothing. Past crashes, both from before the Metro port: `NovelUpdateJob.setupTask` -> `Injekt.get<NovelPreferences>()` (startup); `EHentaiUpdateWorker.setupTask` -> `Injekt.get<ExhPreferences>()` (toggling the E-Hentai gallery-update schedule).

Verify such code on a minified build before trusting it: `:app:assembleNightly` / `:app:installNightly` (the `nightly` variant is `initWith(release)`, so minified and debug-signed; its package is still `eu.kanade.tachiyomi.debug`, since the suffix is independent of the build type's name), then exercise the path. A nightly/release build is not debuggable, so drive it via UI, not `run-as`.

## Preferences

Preferences go through `PreferenceStore` (`core/common/.../preference/PreferenceStore.kt`, backed by `AndroidPreferenceStore`) and the typed `*Preferences` classes (e.g. library / reader / source preference holders), taken as constructor parameters off the graph. There is no `PreferencesHelper` on Mihon. Never use raw `SharedPreferences`. Read preferences in the ViewModel and expose them as state, not inside a `@Composable`.

## Coroutines

Launch with the `launchIO` / `launchUI` extensions (`core/common/.../util/lang/CoroutinesExtensions.kt`), not raw `launch(Dispatchers.IO)`. In a ViewModel use `viewModelScope.launchIO { }` / `launchUI { }`; in a composable use `rememberCoroutineScope()` or `LaunchedEffect`. Never `GlobalScope`; for work that must outlive the screen, use `WorkManager` (as upstream does for library updates, backups, etc.). Reactive state via `StateFlow` / `SharedFlow`; no RxJava on the screen path.

## Domain models

Domain models are immutable (`tachiyomi.domain.*.model`): `val` properties, `Long` flag fields, non-null ids, `@Immutable`. This differs from the Yōkai-era mutable models (`var`, `Int` flags, nullable id). When porting Reikai code from the `design/library-compose` reference, re-type it against Mihon's models and interactors. This re-typing is the single biggest mechanical cost of the rebase.

## Modules

Every code module is an Android-library module with a `src/main` source set (the list is `settings.gradle.kts`). `source-api` holds the extension contract loaded by third-party extensions; it and `source-local` were multiplatform until upstream converted them (mihonapp/mihon#3636), since Android was their only target, so there is no longer a `commonMain` to keep Android types out of. `i18n` is the one module still multiplatform, and only because moko-resources lives there (`i18n/src/commonMain/moko-resources/`); it holds no Kotlin code. SQLDelight lives in `data` (`data/src/main/sqldelight/tachiyomi/data/`).
