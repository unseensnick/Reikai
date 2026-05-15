# Yōkai-Y2K — Development

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

### Pushing

Before pushing commits to GitHub, bump the app version in `app/build.gradle.kts`:

- `_versionName` — follow the versioning convention above (5-segment, `upstream.fork-patch`).
- `versionCode` — always increment.

The release workflow (`build_push.yml`) treats every push as buildable, so each pushed state needs a unique version identifier. If a commit is purely docs / CI / tooling and not going into a release APK, the version bump can be skipped — but flag that explicitly so the user can decide.

### History hygiene before merge

A feature branch accumulates journey-noise — probe-instrument/strip pairs, revert/re-port cycles, temp doc add/drop pairs, fix-of-my-own-bug commits, mid-development churn. Land it on `main` as a clean themed history instead.

**Target shape:** one commit per phase or coherent unit of work. Bisectable. Reads top-down as a feature story.

**Mechanics (proven on the 71→18 commit cleanup):**

1. **Backup first.** `git branch <branch>-pre-rebase <branch>` before touching anything. The 23-commit and 71-commit originals stayed locally recoverable through the entire rebase.
2. **Reset to the merge-base with `main`**, then cherry-pick original commits in chronological order into themed groups. `git cherry-pick --no-commit <a> <b> <c>` accumulates a group; `git commit` materializes it with a written message.
3. **Conflicts on doc files (`CHANGELOG.md`, `docs/suggestions-plan.md`):** auto-resolve with `git cherry-pick -X theirs`. Intermediate doc states don't matter — the final docs commit sets the canonical state.
4. **Conflicts on code:** rare when commits are kept in chronological order within a group. If one happens, it usually means a reordered docs commit (e.g., README rename) is a prerequisite — split that doc commit out and apply it earlier.
5. **`--no-commit` + conflict + `--continue` quirk:** if cherry-pick stalls with "your local changes would be overwritten," run `git commit --no-edit` to materialize the current accumulated state, then `--continue`.
6. **Verify before pushing.** Compare the rebased branch's final tree against the pre-rebase backup:

   ```bash
   git diff <branch>-pre-rebase <branch>   # should be empty
   ```

   Stronger check: tree-object hash equality.

   ```bash
   [ "$(git rev-parse <branch>^{tree})" = "$(git rev-parse <branch>-pre-rebase^{tree})" ]
   ```

   If those match, the rebase preserved every byte. Any divergence means `-X theirs` mis-resolved something — fix by `git checkout <branch>-pre-rebase -- <file>` and `git commit --amend`.
7. **`--force-with-lease`, not `--force`.** Refuses to push if origin moved unexpectedly. Single-developer fork still benefits from the safety check.
8. **FF merge to `main`.** Both branches are already rebased onto `main`'s tip, so `git merge --ff-only` is trivial. Single-developer fork → no team workflow reason to prefer merge commits.

**When to do it:** before merging a feature branch back to `main`. Squash earlier than that and you lose the ability to re-section work mid-stream.

**When *not* to do it:** never rebase commits that are already on `main` or carry release tags. The `feat/tracker-sync-grouped-pre-rebase` and `feat/related-mangas-pre-rebase` backups are local-only; they can be deleted with `git branch -D` once the merge has been confirmed healthy for a week or so.

### Syncing with upstream

Branch layering, top-down:

- `upstream/master` (null2264/yokai)
- `main` — rebrand + small fork features
- branches off `main` — feature/fix work

**Never merge `upstream/master` directly into a non-`main` branch** — that replays rebrand conflicts on every branch instead of resolving them once on `main`.

```bash
git fetch upstream
git checkout main && git merge upstream/master      # rebrand conflicts resolved here, once
git checkout <branch> && git merge main             # branch picks up upstream via main
```

**GitHub's "Sync fork" button:**

- **On `main`**: tries to pull from upstream, sees divergence, refuses or offers to discard fork commits — don't use.
- **On other branches**: safe — syncs the branch with this repo's `main`.

Conflict resolution: keep Y2K for identity/packaging (`applicationId`, app name, `.y2k` suffix, workflow refs, README/CHANGELOG fork sections, `google-services.json`); keep upstream for everything else.

### Reference clones

Sibling read-only clones provide related-project context:

- `yokai` — upstream `null2264/yokai`
- `komikku` — Komikku (source of ported features like related-mangas)
- `mihon` — Mihon (upstream of Yōkai)
- `tachiyomi-extension` — legacy Tachiyomi extension source repo (archived; kept for historical reference)
- `keiyoushi-extensions-source` — Keiyoushi extensions **source** repo (`keiyoushi/extensions-source`); the de-facto active Mihon-lineage extension code that Y2K users install from
- `keiyoushi-extensions` — Keiyoushi extensions **distribution** repo (`keiyoushi/extensions`); compiled APKs + `index.json` served to the in-app extension list

Resolve paths from `permissions.additionalDirectories` in `.claude/settings.json`. Verify the path exists before use; if missing, tell the user and don't retry unless asked.

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
- [Renaming the fork to Reikai](reikai-rebrand.md) — pre-flight plan for renaming `Yōkai-Y2K` → `Reikai`. Locked-in decisions, step-by-step file changes, Android Studio Image Asset Studio steps for the new launcher icons.

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
