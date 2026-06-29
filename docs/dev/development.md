# Reikai — Development

## Project overview

**Reikai** is a personal Android manga + light-novel reader, **built on [Mihon](https://github.com/mihonapp/mihon)** (Tachiyomi lineage). It was previously a fork of [Yōkai](https://github.com/null2264/yokai) and was **rebased onto Mihon** (shipped 2026-06 as v0.1.0). Reikai's differentiators (multi-source grouping, manual merge/unmerge, category sort order, and a light-novel subsystem) are layered on top of Mihon.

- GitHub: `https://github.com/unseensnick/Reikai`
- Mihon upstream: `https://github.com/mihonapp/mihon`
- Package ID: `eu.kanade.tachiyomi`, release suffix `.y2k` (debug `.debugY2k`). Legacy, kept so existing installs upgrade in place. Mihon's own applicationId is `app.mihon`; the `eu.kanade.tachiyomi` namespace is shared by both, so source classes resolve either way.
- App name string: `Reikai` (`i18n/src/commonMain/moko-resources/base/strings.xml`).
- Backups are **not** interchangeable with the old Yōkai-based builds: Mihon's database schema differs, so a Mihon-based install cannot open a Yōkai-Reikai database.

## Rebase status

The rebase has shipped: `main` is the Mihon-based main. The master plan and per-phase record live in [ROADMAP.md](../../ROADMAP.md), the per-feature implementation records in [plans/](plans/), and the `mihon-rebase` memory. The old Yōkai-based code stays on the `design/library-compose` branch as the porting reference.

## Canonical rules

The working rules under `.claude/rules/` are the single source of truth; this doc points to them rather than duplicating:

- [architecture.md](../../.claude/rules/architecture.md) — Compose + Voyager, Injekt DI, `PreferenceStore`, coroutines, domain models, modules, `// RK` patch markers.
- [screen-conventions.md](../../.claude/rules/screen-conventions.md) — Reikai screen conventions on Mihon.
- [workflow.md](../../.claude/rules/workflow.md) — CHANGELOG, commits/PRs, release-cut, upstream + feature porting.
- [code-quality.md](../../.claude/rules/code-quality.md), [testing.md](../../.claude/rules/testing.md), [database.md](../../.claude/rules/database.md), [security.md](../../.claude/rules/security.md).

## Build

- Android Studio (`Build → Make/Rebuild`). JDK 21 (Temurin 21.0.11; matches `.github/.java-version`). Formatting via Spotless (`./gradlew spotlessApply`).
- `minSdk 26`, `targetSdk 36`, `compileSdk 37`.
- No product flavors. Build types: `debug` (`.debugY2k`), `release` (`.y2k`), `foss`, `preview`, `benchmark`. Release builds use AGP-native signing with the real key when CI secrets or a local `keystore.properties` are present, else they fall back to debug-signed (see the `// RK` signing block in `app/build.gradle.kts`). The `preview` build type doubles as the nightly channel.
- Domain tests: `./gradlew :domain:test`.
- CLI Gradle is intermittent on the dev machine (loopback flake); build/test on-device in Android Studio when it fails.

## Module architecture

Multi-module Gradle project; convention plugins live in `gradle/build-logic` (`includeBuild`), dependency versions in the `libs` and `mihonx` catalogs (`gradle/*.versions.toml`).

| Module | Purpose |
|---|---|
| `app/` | Android application (Compose + Voyager UI, ScreenModels) |
| `core/common` | Shared utilities (coroutine + preference helpers) |
| `core/archive` | Archive handling |
| `core-metadata` | Metadata parsing |
| `data/` | SQLDelight database + repository implementations |
| `domain/` | Business logic + interactors (immutable `tachiyomi.domain.*` models; has unit tests) |
| `i18n/` | Strings via Moko Resources |
| `presentation-core` / `presentation-widget` | Reusable Compose components, home-screen widgets |
| `source-api` | Extension contract loaded by 3rd-party extensions |
| `source-local` | Local source |
| `telemetry` | Optional telemetry (gated by `Config.includeTelemetry`) |
| `baseline-profile` | Baseline profile generation (startup / scroll) |

## Key technologies

Compose + Voyager (no Conductor), Injekt DI, SQLDelight, OkHttp, Coil 3, kotlinx.serialization, Kermit logging (+ optional Firebase Crashlytics), JUnit / MockK / Kotest.

## Reference clones

Sibling read-only clones in `refs/` provide context:

- `mihon` — the base; port upstream Mihon changes from here.
- `yokai` — the Yōkai-era base; historical reference only.
- `komikku` — Komikku (SY/EH lineage); reference for merge and feature approaches.
- `lnreader-main` / `lnreader-plugins` — LNReader; reference for the light-novel subsystem.
- `keiyoushi-extensions` / `keiyoushi-extensions-source` — Keiyoushi extensions (distribution + source).
- `tachiyomi-extension` — Suwayomi's extension repo.
- `blueth-yokai` — another Yōkai fork.

Reikai's own pre-rebase features are read from the `design/library-compose` branch, not from a clone.

## Porting

- **Mihon upstream:** port manually from `refs/mihon`. Fence edits to Mihon's own files with `// RK -->` / `// RK <--`.
- **Reikai features:** port from the `design/library-compose` branch, re-typed onto Mihon's immutable domain models. The rebase plan defines the phase order.

## Build gotcha

If file edits don't appear in the running app despite a successful build, Kotlin incremental compilation may be serving stale class files: **Build → Clean Project** in Android Studio, then rebuild.
