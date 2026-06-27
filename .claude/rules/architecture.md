---
alwaysApply: true
---

# Project architecture

Reikai is built on Mihon. Mihon is **Compose + Voyager throughout**, so the Yōkai-era split (Conductor `*Controller` + RxJava `*Presenter` for legacy screens, Compose for new ones) does not exist here. There is one UI stack.

## Compose + Voyager (the only stack)

Screens are Voyager `Screen` / `Tab` classes whose `Content()` resolves a `ScreenModel` via `rememberScreenModel { ... }` and renders over `state.collectAsState()`. Navigation is a Voyager `Navigator` (see `MainActivity` and `HomeScreen`). There is no Conductor `Router` and no `*Presenter`.

The one View-based holdout, shared with upstream, is the **reader** (`ReaderActivity` + `PagerViewer` / `WebtoonViewer`). Reader tweaks are View edits, not Compose work.

## Reikai screens on Mihon

Reikai's ported screens (library, manga details, the light-novel surfaces) follow Mihon's conventions (see [screen-conventions.md](screen-conventions.md)) and are re-typed to Mihon's models. Two placement rules:

- **Net-new code lives in its own files/modules** (own ScreenModels, own `.sq` tables, own Voyager screens).
- **Edits to Mihon's own files** (the nav tab list, backup proto fields, DI registration) are fenced with `// RK -->` / `// RK <--` comment islands so they survive upstream merges and are greppable. Mirrors Komikku's `// SY` / `// KMK` convention.

## Dependency injection

DI is **Injekt** (`uy.kohesive.injekt`). Modules live at `app/src/main/java/eu/kanade/tachiyomi/di/` (`AppModule`, `PreferenceModule`) and `app/src/main/java/eu/kanade/domain/DomainModule.kt`. Register new repositories and interactors there with `addSingletonFactory { ... }`; resolve with `Injekt.get<T>()` (constructor defaults) or `by injectLazy()` at class level. Do not introduce Koin: the Yōkai base used Koin, Mihon does not, and adding it would widen the patch surface against upstream.

No `Injekt.get<>()` / `injectLazy()` inside a `@Composable` body.

## Minification (R8) and net-new packages

Release-type builds (`release` / `preview` / `foss`) are minified (`isMinifyEnabled = Config.enableCodeShrink`); the `debugY2k` dev build is NOT, so R8-only bugs are invisible in the normal dev loop. The recurring one: R8 strips the generic `Signature` that Injekt's `FullTypeReference` reflects on, so an `Injekt.get<T>()` / `injectLazy<T>()` in a package that is not in the proguard keep list crashes the minified build with `IllegalArgumentException: Internal error: TypeReference constructed without actual type information`.

`app/proguard-rules.pro` keeps the upstream packages (`eu.kanade.**` / `tachiyomi.**` / `mihon.**`) plus every net-new top-level package: `reikai.**` and `exh.**`. **When you add a new top-level package that uses Injekt generics, add its own `-keep,allowoptimization class <pkg>.**` line.** Past crashes: `NovelUpdateJob.setupTask` -> `Injekt.get<NovelPreferences>()` (startup); `EHentaiUpdateWorker.setupTask` -> `Injekt.get<ExhPreferences>()` (toggling the E-Hentai gallery-update schedule).

Verify such code on a minified build before trusting it: `:app:assemblePreview` / `:app:installPreview` (the `preview` variant is `initWith(release)`, so minified and debug-signed; its package is `eu.kanade.tachiyomi.debug`), then exercise the path. A preview/release build is not debuggable, so drive it via UI, not `run-as`.

## Preferences

Preferences go through `PreferenceStore` (`core/common/.../preference/PreferenceStore.kt`, backed by `AndroidPreferenceStore`) and the typed `*Preferences` classes (e.g. library / reader / source preference holders) injected via Injekt. There is no `PreferencesHelper` on Mihon. Never use raw `SharedPreferences`. Read preferences in the ScreenModel and expose them as state, not inside a `@Composable`.

## Coroutines

Launch with the `launchIO` / `launchUI` extensions (`core/common/.../util/lang/CoroutinesExtensions.kt`), not raw `launch(Dispatchers.IO)`. In a ScreenModel use `screenModelScope.launchIO { }` / `launchUI { }`; in a composable use `rememberCoroutineScope()` or `LaunchedEffect`. Never `GlobalScope`; for work that must outlive the screen, use `WorkManager` (as upstream does for library updates, backups, etc.). Reactive state via `StateFlow` / `SharedFlow`; no RxJava on the screen path.

## Domain models

Domain models are immutable (`tachiyomi.domain.*.model`): `val` properties, `Long` flag fields, non-null ids, `@Immutable`. This differs from the Yōkai-era mutable models (`var`, `Int` flags, nullable id). When porting Reikai code from the `design/library-compose` reference, re-type it against Mihon's models and interactors. This re-typing is the single biggest mechanical cost of the rebase.

## Modules

Most modules are Android-library modules with a `src/main` source set: `app`, `core/archive`, `core/common`, `core-metadata`, `data`, `domain`, `i18n`, `presentation-core`, `presentation-widget`, `source-local`, `telemetry`. `source-api` is the multiplatform module (`commonMain`) holding the extension contract loaded by third-party extensions. Keep Android types out of `commonMain`. SQLDelight lives in `data` (`data/src/main/sqldelight/tachiyomi/data/`).
