# Reikai — Development

## Project Overview

**Reikai** is a personal Android manga reader — a fork of [Yōkai](https://github.com/null2264/yokai) (itself a fork of Tachiyomi/Mihon). The fork adds multi-source manga grouping, manual merge/unmerge, and category sorting on top of upstream Yōkai.

- Upstream: `https://github.com/null2264/yokai`
- Package ID: `eu.kanade.tachiyomi`. Release suffix is `.y2k` (legacy — predates the Reikai rename; kept so existing installs upgrade in place). Backup files remain interchangeable — Tachiyomi-lineage backups don't bind to applicationId, so a Reikai backup restores into upstream Yōkai (and vice versa).
- App name string: `Reikai` (in `i18n/src/commonMain/moko-resources/base/strings.xml`)

## Versioning Convention

Fork versions use a 5th segment: `upstream.fork-patch` (e.g. `1.9.7.5.1` = upstream `1.9.7.5`, fork patch `1`).

Two places to update per release:
- `app/build.gradle.kts` — `_versionName` (string) and `versionCode` (integer, always increment)
- `CHANGELOG.md` — add a `## [x.x.x.x.x]` entry above `[Unreleased]`

## Development Workflow

### Changelog

After completing any code change (feature, fix, or other), update `CHANGELOG.md`:

- Add a bullet under `## [Unreleased]`, using the same category labels as the file: `Additions`, `Changes`, `Fixes`, `Other`.
- If `## [Unreleased]` does not exist, create it immediately above the most recent version entry (e.g., above `## [1.9.7.5.5]`).
- **Do not add a new entry** for iterative changes or fixes to something already listed in `[Unreleased]` — that item was never released, so mid-development churn is noise. Update the existing bullet or leave it unchanged.

**When the user asks to cut a release / rename Unreleased:**
- Rename `## [Unreleased]` to the specified version (e.g., `## [1.9.7.5.6]`).
- Add a new empty `## [Unreleased]` section above it for the next cycle.

### Documentation

After any change that alters user-visible behavior, file paths called out in docs, or fork-specific feature mechanics, check `README.md` and `docs/*.md` for stale references and update them in the same change. Do not leave bullets describing dev-cycle iteration once a feature is complete:

- Inside `[Unreleased]`: fold mid-development churn into the original bullet (already covered by the Changelog rule above) — do not accumulate `Fix X in feature Y` bullets when feature Y was added in the same `[Unreleased]` block.
- In `README.md` / `docs/*.md`: describe the current behavior, not the journey to it. No "we tried X then switched to Y" paragraphs, no notes about temporary workarounds that have since been removed.
- Refrain from rewriting already-released CHANGELOG entries — those are a historical record. Apply the rule going forward.

### Commits

After completing code changes, create a git commit. Do not push. Use conventional commit format matching the project's existing style:

- `feat:` / `feat(scope):` — new feature
- `fix:` — bug fix
- `docs:` — documentation only
- `chore:` — build / tooling

When running Claude Code, use the `/ship` skill (or `/debug-fix --fast` for hotfixes) — those walk the scan → stage → commit → push → PR flow with this project's conventions baked in and won't emit `Co-Authored-By` lines.

### Cutting a release

Bump the app version in `app/build.gradle.kts` only when the user asks to cut a release. Alpha cycles ship by branch/tag, not by per-push version bumps.

- `_versionName` — follow the versioning convention above (5-segment, `upstream.fork-patch`).
- `versionCode` — always increment.

The release workflow (`build_push.yml`) builds whatever is pushed to `main`, but per-push bumps cause noisy version churn during iteration. Save the bump for the release commit.

### Merging

All changes land on `main` via a PR. Use **squash and merge** when a branch has accumulated a lot of commits from iteration — it keeps `main` history readable. Use a regular merge when the branch commits are already clean and meaningful on their own.

### Syncing with upstream

Upstream changes are ported manually. Clone [null2264/yokai](https://github.com/null2264/yokai) locally, check what changed, and apply the relevant diffs to Reikai by hand. Re-target to the Compose screen where Reikai has already migrated ahead of upstream.

When porting, keep Reikai identity as-is (`applicationId`, app name, `.y2k` suffix, workflow refs, README/CHANGELOG fork sections, `google-services.json`) and take upstream for everything else.

### Reference clones

Sibling read-only clones provide related-project context. Clone whichever repos are relevant to your work and keep them as siblings next to this repo:

- `yokai` — upstream `null2264/yokai`; primary reference for porting upstream changes
- `komikku` — Komikku (source of ported features like related-mangas)
- `mihon` — Mihon (upstream of Yōkai)
- `tachiyomi-extension` — legacy Tachiyomi extension source repo (archived; historical reference only)
- `keiyoushi-extensions-source` — Keiyoushi extensions **source** repo (`keiyoushi/extensions-source`); active Mihon-lineage extension code that Reikai users install from
- `keiyoushi-extensions` — Keiyoushi extensions **distribution** repo (`keiyoushi/extensions`); compiled APKs + `index.json` served to the in-app extension list
- `blueth-yokai` — another Yokai fork; reference for alternative feature implementations
- `lnreader-main` / `lnreader-plugins` — LNReader (Android light-novel reader); reference for novel-reading UI and plugin architecture

## Build Commands

Java 17 required. `minSdk 23`, `targetSdk 36`, `compileSdk 36`.

**Build flavors:** `standard` (full features) vs `dev` (English-only, faster).
**Build types:** `debug`, `release`, `beta`, `nightly`.

Builds run through Android Studio (`Build → Make/Rebuild`), not the terminal.

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

**Two-screen pattern:** each settings section can have two parallel implementations during its migration:
- **Compose screen** in `app/.../yokai/presentation/settings/screen/` — the target stack (Compose + Voyager).
- **Legacy Conductor controller** in `app/.../ui/setting/controllers/legacy/` — kept as a long-press fallback during the soak period after a section flips.

**Per-section state:**
- **Security**, **Data and storage**, **Advanced** — Compose by default (tap). Long-press reaches the legacy version, which prints a `"You're entering legacy version of …"` toast.
- **General**, **Appearance**, **Library**, **Reader**, **Downloads**, **Browse**, **Tracking**, **About** — legacy only. No Compose port yet.

**When adding or editing settings UI for a section that has flipped:** changes go to the Compose screen. The legacy controller stays alive during the soak period so that the Settings search (which still indexes legacy controllers — see `SettingsSearchHelper.kt`) keeps surfacing results for migrated sections.

**When adding or editing settings UI for a section that has not flipped:** changes go to the legacy controller. The Compose screen, if any, is invisible to users until that section's migration is complete and the routing is flipped.

For the full step-by-step recipe to port a remaining section (Compose screen creation, dialog helpers, soak probes, routing flip, regression test, /ship), see [`docs/dev/settings-compose-migration.md`](settings-compose-migration.md). That doc is the canonical place to update the per-section status table as sections flip.

## Planned work

Standing tasks captured in their own cold-start handbooks. Each is self-contained and pickup-able by a fresh session:

- [Finishing the Settings Compose migration](settings-compose-migration.md) — 8 remaining sections to port from legacy Conductor / XML preferences to Compose + Voyager. Includes the recipe, probe protocol, and per-section status table.

## Build Gotchas

If file edits don't appear in the running app despite a successful build, Kotlin incremental compilation is likely serving stale class files — **Build → Clean Project** in Android Studio, then rebuild.

## Source Plugin System

External manga sources are plugins loaded at runtime via `source/api`. `HttpSource` and `ParsedHttpSource` define the extension contract. Do not break the public API surface of `source/api` without a migration plan.

## Coding Principles

Follow these principles in all changes to this codebase.

**DRY (Don't Repeat Yourself):** Before writing a new utility, helper, or pattern, search the codebase for an existing equivalent. During planning, always run Explore agents to check whether similar code already exists — duplication found after implementation wastes review cycles.

**YAGNI (You Aren't Gonna Need It):** Only add what the current task requires. No speculative APIs, optional parameters, or abstractions for hypothetical future callers. If it isn't needed right now, don't write it.

**KISS (Keep It Simple):** Prefer the simplest solution that correctly solves the problem. Complexity must be justified by a concrete requirement, not elegance or anticipated scale.

**No standalone refactor/cleanup sprints:** Refactoring must be done in small increments alongside the feature or fix that motivated it — only touch what the task requires. Never propose a separate "cleanup pass" as a follow-up PR unless the user explicitly asks for one.

**Minimal blast radius:** A bug fix should change only what is broken. A feature should add only what is specified. Leave surrounding code that works untouched, even if it could be "improved."
