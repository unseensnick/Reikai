# Reikai — Development

## Project overview

**Reikai** is a personal Android manga + light-novel reader, **built on [Mihon](https://github.com/mihonapp/mihon)** (Tachiyomi lineage). It was previously a fork of [Yōkai](https://github.com/null2264/yokai) and was **rebased onto Mihon** (shipped 2026-06 as v0.1.0). Reikai's differentiators (multi-source grouping, manual merge/unmerge, category sort order, and a light-novel subsystem) are layered on top of Mihon.

- GitHub: `https://github.com/unseensnick/Reikai`
- Mihon upstream: `https://github.com/mihonapp/mihon`
- Package ID: `eu.kanade.tachiyomi`, release suffix `.y2k` (debug `.debugY2k`). Legacy, kept so existing installs upgrade in place. Mihon's own applicationId is `app.mihon`; the `eu.kanade.tachiyomi` namespace is shared by both, so source classes resolve either way.
- App name string: `Reikai` (`i18n/src/commonMain/moko-resources/base/strings.xml`).
- The **database** is not interchangeable with the old Yōkai-based builds: the schema differs, so a Mihon-based install cannot open a Yōkai-Reikai `tachiyomi.db`, which is why `LegacyYokaiDbImporter` exists. **Backup files are** interchangeable: the proto is shared and each side's extra field numbers are disjoint, so a Yōkai `.tachibk` restores here and vice versa.

## Rebase status

The rebase has shipped: `main` is the Mihon-based main. The forward backlog lives in [ROADMAP.md](../../ROADMAP.md) (forward-only), the per-feature implementation records in [plans/](plans/) (the rebase's own record is `plans/rebase-overview.md`), and the done-log in [shipped.md](shipped.md). The old Yōkai-based code stays on the `design/library-compose` branch as the porting reference.

## Canonical rules

The working rules under `.claude/rules/` are the single source of truth; this doc points to them rather than duplicating:

- [architecture.md](../../.claude/rules/architecture.md) — Compose + Voyager, Metro DI, `PreferenceStore`, coroutines, domain models, modules, `// RK` patch markers.
- [screen-conventions.md](../../.claude/rules/screen-conventions.md) — Reikai screen conventions on Mihon.
- [workflow.md](../../.claude/rules/workflow.md) — CHANGELOG, commits/PRs, release-cut, upstream + feature porting.
- [code-quality.md](../../.claude/rules/code-quality.md), [testing.md](../../.claude/rules/testing.md), [database.md](../../.claude/rules/database.md), [security.md](../../.claude/rules/security.md).

## Build

- Android Studio (`Build → Make/Rebuild`). JDK 21 (Temurin 21.0.11; matches `.github/.java-version`). Formatting via Spotless (`./gradlew spotlessApply`).
- `minSdk 26`, `targetSdk 36`, `compileSdk 37`.
- No product flavors. Build types: `debug` (`.debugY2k`), `release` (`.y2k`), `foss`, `nightly`, `benchmark`. Release builds use AGP-native signing with the real key when CI secrets or a local `keystore.properties` are present, else they fall back to debug-signed (see the `// RK` signing block in `app/build.gradle.kts`). The `nightly` build type is the pre-release channel; it installs as `eu.kanade.tachiyomi.debug`.
- Domain tests: `./gradlew :domain:test`.
- CLI Gradle is intermittent on the dev machine (loopback flake); build/test on-device in Android Studio when it fails.

## Module architecture

Multi-module Gradle project; convention plugins live in `gradle/build-logic` (`includeBuild`), dependency versions in the `libs` and `mihonx` catalogs (`gradle/*.versions.toml`).

| Module | Purpose |
|---|---|
| `app/` | Android application (Compose + Voyager UI, AndroidX ViewModels) |
| `core/common` | Shared utilities (coroutine + preference helpers) |
| `core/archive` | Archive handling |
| `core/metro` | Metro DI helpers: graph lookup, build-type qualifier, ViewModel wiring |
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

Compose + Voyager (no Conductor), Metro DI, SQLDelight, OkHttp, Coil 3, kotlinx.serialization, squareup logcat logging (+ optional Firebase Crashlytics), JUnit / MockK / Kotest.

## Reference clones

Sibling read-only clones in `refs/` provide context:

- `mihon` — the base; port upstream Mihon changes from here.
- `yokai` — the Yōkai-era base; historical reference only.
- `komikku` — Komikku (SY/EH lineage); reference for merge and feature approaches.
- `tsundoku` — Tsundoku, a Mihon-fork novel reader; reference for novel-reader features and the planned native-reader migration.
- `lnreader-main` / `lnreader-plugins` — LNReader; reference for the light-novel subsystem.
- `keiyoushi-extensions` / `keiyoushi-extensions-source` — Keiyoushi extensions (distribution + source).
- `tachiyomi-extension` — Suwayomi's extension repo.
- `blueth-yokai` — another Yōkai fork.

Reikai's own pre-rebase features are read from the `design/library-compose` branch, not from a clone.

## Porting

Two inbound flows, each with its own doc (this section is a pointer, not the record):

- **Mihon upstream** (the base): ported by hand from `refs/mihon`, edits to Mihon's files fenced with `// RK`. Process, ledger and frontier: [upstream-sync.md](upstream-sync.md).
- **Borrowed features** (Komikku / Tsundoku / LNReader): per-feature, no frontier. Record: [feature-ports.md](feature-ports.md).
- **Reikai's own pre-rebase features**: ported from the `design/library-compose` branch, re-typed onto Mihon's immutable domain models.

## Build gotcha

If file edits don't appear in the running app despite a successful build, Kotlin incremental compilation may be serving stale class files: **Build → Clean Project** in Android Studio, then rebuild.
