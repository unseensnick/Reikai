# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Yōkai-Y2K** is a personal Android manga reader — a fork of [Yōkai](https://github.com/null2264/yokai) (itself a fork of Tachiyomi/Mihon). The fork adds multi-source manga grouping, manual merge/unmerge, and category sorting on top of upstream Yōkai.

- Upstream: `https://github.com/null2264/yokai`
- Package ID: `eu.kanade.tachiyomi`. Release suffix is `.y2k` (was `.yokai`); the change in 1.9.7.5.x makes Y2K installable alongside upstream Yōkai. Backup files remain interchangeable — Tachiyomi-lineage backups don't bind to applicationId, so a Y2K backup restores into upstream Yōkai (and vice versa).
- App name string: `Yōkai-Y2K` (in `i18n/src/commonMain/moko-resources/base/strings.xml`)

## Versioning Convention

Fork versions use a 5th segment: `upstream.fork-patch` (e.g. `1.9.7.5.1` = upstream `1.9.7.5`, fork patch `1`).

Two places to update per release:
- `app/build.gradle.kts` — `_versionName` (string) and `versionCode` (integer, always increment)
- `CHANGELOG.md` — add a `## [x.x.x.x.x]` entry above `[Unreleased]`

When syncing upstream: move the `[Unreleased]` content into a new version entry, then add fork-specific changes below it.

## Development Workflow

### Changelog

After completing any code change (feature, fix, or other), update `CHANGELOG.md`:

- Add a bullet under `## [Unreleased]`, using the same category labels as the file: `Additions`, `Changes`, `Fixes`, `Other`.
- If `## [Unreleased]` does not exist, create it immediately above the most recent version entry (e.g., above `## [1.9.7.5.5]`).
- **Do not add a new entry** for iterative changes or fixes to something already listed in `[Unreleased]` — that item was never released, so mid-development churn is noise. Update the existing bullet or leave it unchanged.

**When the user asks to cut a release / rename Unreleased:**
- Rename `## [Unreleased]` to the specified version (e.g., `## [1.9.7.5.6]`).
- Add a new empty `## [Unreleased]` section above it for the next cycle.

### Commits

After completing code changes, create a git commit. Do not push. Use conventional commit format matching the project's existing style:

- `feat:` / `feat(scope):` — new feature
- `fix:` — bug fix
- `docs:` — documentation only
- `chore:` — build / tooling

Do not include `Co-Authored-By` lines in commit messages.

## Build Commands

```bash
# Debug builds
./gradlew assembleStandardDebug      # Full-featured debug
./gradlew assembleDevDebug           # English-only, faster iteration

# Release build
./gradlew assembleStandardRelease    # Optimized with R8/ProGuard

# Tests
./gradlew testReleaseUnitTest
./gradlew testStandardReleaseUnitTest
./gradlew :domain:test               # Single module

# Lint
./gradlew lint
```

Requires Java 17. `minSdk 23`, `targetSdk 36`, `compileSdk 36`.

**Build flavors:** `standard` (full features) vs `dev` (English-only, faster).
**Build types:** `debug`, `release`, `beta`, `nightly`.

## Releasing via GitHub Actions

Releases are built and published automatically by `.github/workflows/build_push.yml`.

**To trigger a release:**
GitHub → Actions → "Build app" → Run workflow
- **Version:** e.g. `1.9.7.5.1` (no `v` prefix)
- **Beta:** unchecked for stable
- **Message:** optional header shown at the top of release notes

The workflow builds all APK variants, signs them (using repo secrets), generates SHA-256 checksums, and creates a **draft** release. Go to the Releases tab to review and publish.

**Required repo secrets** (Settings → Secrets and variables → Actions):

| Secret | Description |
|---|---|
| `SIGNING_KEY` | Keystore file as Base64 |
| `ALIAS` | Key alias |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

Keystore file is at `C:\Users\unseensnick\Desktop\projects\code\keystore\yokai-y2k.jks` — keep it backed up outside the repo.

## Module Architecture

Multi-module Gradle project with Kotlin Multiplatform where applicable:

| Module | Purpose |
|---|---|
| `app/` | Android application, UI controllers, presenters |
| `core/main` | Shared utilities — multiplatform (Android/iOS stubs/common) |
| `core/archive` | ZIP/RAR archive handling |
| `data/` | SQLDelight database, repository implementations — multiplatform |
| `domain/` | Business logic & use cases — multiplatform, has unit tests |
| `i18n/` | Strings via Moko Resources |
| `presentation/core` | Reusable Compose components, Material 3 theme |
| `source/api` | Plugin API for external manga sources |

Build convention plugins are in `buildSrc/`. Dependency versions are in `gradle/` version catalogs (`androidx`, `compose`, `kotlinx`, `libs`).

## Key Technologies

- **UI:** Jetpack Compose + Material 3; legacy screens use Conductor; new screens use Voyager
- **Database:** SQLDelight 2.x (multiplatform)
- **DI:** Koin 4 modules under `yokai/core/di/`; older code uses Injekt
- **Networking:** OkHttp 5 alpha
- **Image loading:** Coil 3 with custom decoders for SVG/GIF/page formats
- **Serialization:** kotlinx.serialization
- **Logging:** Kermit; crash reporting via Firebase Crashlytics
- **Testing:** JUnit 5, MockK, Kotest

## Architectural Patterns

**Presenter pattern (MVP-like):** each screen has a `*Controller` (Conductor, View-layer) and a `*Presenter` (business logic). New screens migrate toward Compose + Voyager.

**Multiplatform:** `domain/` and `data/` use `commonMain`/`androidMain` source sets. Keep platform-specific code in the correct source set.

**Preferences:** accessed through `PreferencesHelper` wrapping a custom datastore. Keys are defined in `PreferenceKeys`. Do not access raw SharedPreferences directly.

**Coroutines & Flow:** reactive state via `StateFlow`/`SharedFlow`. Use `launchIO`/`launchUI` extension helpers. Presenters collect flows and push state to controllers.

**Compose opt-ins:** propagate `@OptIn` annotations rather than suppressing globally.

## Fork-Specific Features

All implemented in `app/`:

- **Multi-source grouping** — `LibraryPresenter.applySourceGrouping()` groups same-title entries per category. State stored in `mangaManualMerges` / `mangaManualUnmerges` preferences (StringSet of comma-separated ID pairs).
- **Source-count badge** — `LibraryMangaItem.sourceCount` + `LibraryGridHolder` renders a pill badge on grouped cards.
- **Source-switcher chips** — `MangaHeaderHolder.setSourceChips()` renders a horizontal chip row in manga details. Tapping switches to that manga's detail screen while preserving `relatedMangaIds`.
- **Manage sources sheet** — `ManageSourcesSheet.kt` (new file), opened from manga details overflow menu when `relatedMangaIds` is non-empty.
- **Category sort order** — `preferences.categorySortOrder()` (int: 0=manual, 1=A→Z, 2=Z→A), applied in `LibraryPresenter` category comparator.

## Settings Screen Architecture

Settings navigation is driven by `app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/SettingsMainController.kt`, which maps each section to its screen.

**Two-screen pattern:** every settings section has two parallel implementations:
- **Legacy Conductor controller** in `app/.../ui/setting/controllers/legacy/` — this is what users see by default
- **Compose screen** in `app/.../yokai/presentation/settings/screen/` — experimental, not yet the default for most sections

**When adding or editing settings UI, changes must go to the legacy controller.** The Compose screen won't be visible to users until the migration for that section is complete.

**Advanced settings specifically:**
- Normal tap → `SettingsAdvancedLegacyController` (legacy, what users see)
- Long-press → `SettingsAdvancedController` → `SettingsAdvancedScreen` (Compose, experimental)

## Build Gotchas

Kotlin incremental compilation can serve stale class files for individual files even when the rest of the module recompiles successfully. Symptom: code changes in one file don't appear in the running app despite a successful build, while changes to other files in the same module do. Fix: **Build → Clean Project** in Android Studio, then rebuild.

## Source Plugin System

External manga sources are plugins loaded at runtime via `source/api`. `HttpSource` and `ParsedHttpSource` define the extension contract. Do not break the public API surface of `source/api` without a migration plan.

## Coding Principles

Follow these principles in all changes to this codebase.

**DRY (Don't Repeat Yourself):** Before writing a new utility, helper, or pattern, search the codebase for an existing equivalent. During planning, always run Explore agents to check whether similar code already exists — duplication found after implementation wastes review cycles.

**YAGNI (You Aren't Gonna Need It):** Only add what the current task requires. No speculative APIs, optional parameters, or abstractions for hypothetical future callers. If it isn't needed right now, don't write it.

**KISS (Keep It Simple):** Prefer the simplest solution that correctly solves the problem. Complexity must be justified by a concrete requirement, not elegance or anticipated scale.

**No standalone refactor/cleanup sprints:** Refactoring must be done in small increments alongside the feature or fix that motivated it — only touch what the task requires. Never propose a separate "cleanup pass" as a follow-up PR unless the user explicitly asks for one.

**Minimal blast radius:** A bug fix should change only what is broken. A feature should add only what is specified. Leave surrounding code that works untouched, even if it could be "improved."
