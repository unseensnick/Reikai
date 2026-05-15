# Settings Compose migration — cold-start handbook

This doc is the single-source recipe for porting a settings section from the legacy Conductor + XML-preference stack to Compose + Voyager. A fresh session reading this from scratch should be able to pick any unmigrated section and finish it without consulting prior conversation history.

Start by reading the **Status** table to see where things stand, then jump to **The recipe**.

## Status

| Section | Tap (default) | Long-press fallback |
|---|---|---|
| Security | Compose | Legacy |
| Data and storage | Compose | Legacy |
| Advanced | Compose | Legacy |
| General | Legacy | — |
| Appearance | Legacy | — |
| Library | Legacy | — |
| Reader | Legacy | — |
| Downloads | Legacy | — |
| Browse | Legacy | — |
| Tracking | Legacy | — |
| About | Compose | — (no legacy) |

About is Compose-only and lives outside `ui/setting/controllers/` ([`AboutController`](../../app/src/main/java/eu/kanade/tachiyomi/ui/more/AboutController.kt) is at `ui/more/`, hosting [`AboutScreen`](../../app/src/main/java/yokai/presentation/settings/screen/about/AboutScreen.kt)). It doesn't follow the rest of the recipe — it was migrated upstream before this fork started touching settings.

Sub-section controllers — [`SettingsLibraryRecommendationsController`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/SettingsLibraryRecommendationsController.kt) and [`SettingsSourcesController`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/SettingsSourcesController.kt) — are legacy-only screens reached from *inside* Library and Browse, not from the main menu. The same recipe applies to them when their parent section migrates.

Update this table when a section flips. The handbook itself is the table's home — don't move the table to a CHANGELOG or PR description.

## Why this exists

The fork is migrating settings off Conductor controllers + `androidx.preference` to Compose + Voyager. Motivation is **cohesion + maintainability**, not perf — every screen written once in the same stack is easier to read, change, and review than the current Conductor/Compose hybrid.

During each section's soak period, both implementations live side by side: tap opens the new Compose screen, long-press opens the legacy controller with a toast (`"You're entering legacy version of '<X>'"`). The legacy controller stays alive after the flip because **Settings → search still indexes legacy controllers** — Compose-side search is upstream-blocked (see [`SettingsSearchHelper.kt:69-72`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/search/SettingsSearchHelper.kt)). Removing a legacy controller would silently break search for that section.

The migration is happening ahead of upstream (`null2264/yokai`). Upstream sync for this fork is done manually by porting from the `refs/yokai/` clone, so being ahead doesn't accumulate merge-conflict debt — when upstream lands a change in a section we already migrated, we re-target the change to the Compose screen instead of replaying it on legacy.

## The recipe

> **Always start a new feature branch before doing any work — never edit a settings section directly on `main`.** Steps 1 and 14 (the `/ship` flow) bracket every port with a branch + PR cycle.

`<X>` is the section name (e.g. `General`, `Reader`). `<x>` is the lowercase variant for branch names.

1. **Branch.** `git checkout -b feat/compose-settings-<x>` off `main`. Do not skip this. Both the local hook and the GitHub ruleset reject direct pushes to `main` without admin bypass, and the PR route is the conventional path so review tooling (`code-reviewer` / `doc-reviewer` agents, `/pr-review` skill) has a branch to inspect.

2. **Move the legacy controller.** `git mv app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/Settings<X>Controller.kt app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/legacy/Settings<X>LegacyController.kt`. Rename the class to `Settings<X>LegacyController` and change the package declaration to `eu.kanade.tachiyomi.ui.setting.controllers.legacy`. Mirror what `SettingsAdvancedLegacyController` and `SettingsDataLegacyController` look like.

3. **Update search indexing.** In [`SettingsSearchHelper.kt`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/search/SettingsSearchHelper.kt), replace the import of `Settings<X>Controller` with the legacy variant from `controllers.legacy`, and update the entry in `settingControllersList` to point at `Settings<X>LegacyController::class`. Search still routes through legacy until the upstream TODO is resolved.

4. **Create the Compose screen.** New file: `app/src/main/java/yokai/presentation/settings/screen/Settings<X>Screen.kt`. Extend [`ComposableSettings`](../../app/src/main/java/yokai/presentation/settings/ComposableSettings.kt). Reuse the [`Preference.PreferenceItem.*`](../../app/src/main/java/yokai/presentation/component/preference/Preference.kt) DSL. Map legacy widgets 1:1:

   | Legacy DSL (`PreferenceDSL.kt`) | Compose DSL (`Preference.PreferenceItem.*`) |
   |---|---|
   | `switchPreference { bindTo(...) }` | `SwitchPreference(pref = ..., title = ...)` |
   | `intListPreference(activity) { bindTo(...) }` | `ListPreference<Int>(pref = ..., entries = ImmutableMap<Int, String>)` |
   | `listPreference(activity)` for an enum | `ListPreference<EnumType>(pref = ..., entries = ImmutableMap<EnumType, String>)` |
   | `editTextPreference(activity) { bindTo(...) }` | `EditTextPreference(pref = ..., title = ...)` |
   | `infoPreference(MR.strings.X)` | `InfoPreference(title = stringResource(MR.strings.X))` |
   | plain `preference { onClick { … } }` | `TextPreference(title = ..., onClick = { … })` |
   | `preferenceCategory { … }` (a group) | `Preference.PreferenceGroup(title = ..., preferenceItems = ...)` |

5. **Create the bridge controller.** New file at the original path: `app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/Settings<X>Controller.kt`. 9 lines, identical shape to [`SettingsAdvancedController`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/SettingsAdvancedController.kt):

   ```kotlin
   class Settings<X>Controller : SettingsComposeController() {
       override fun getComposableSettings(): ComposableSettings = Settings<X>Screen
   }
   ```

6. **Flip the row in [`SettingsMainController.kt`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/SettingsMainController.kt).** Change the section's `preference { … }` block to `preferenceLongClickable { … }`, matching the existing Security / Data / Advanced rows:

   ```kotlin
   preferenceLongClickable {
       iconRes = R.drawable.<icon>
       iconTint = tintColor
       titleRes = MR.strings.<x>
       onClick { navigateTo(Settings<X>Controller()) }
       onLongClick {
           navigateTo(Settings<X>LegacyController())
           context.toast("You're entering legacy version of '<X>'")
       }
   }
   ```

   Add the legacy controller import alongside the existing `controllers.legacy.*` imports.

7. **Dialogs.** If the section has multi-choice dialogs (checkboxes), follow the per-use-case `suspend fun DialogHostState.awaitX(...)` convention. Put the new file under `screen/<x>/AlertDialogs.kt`. Reuse [`LabeledCheckbox`](../../app/src/main/java/yokai/presentation/component/LabeledCheckbox.kt).

   Two `CancellableContinuation` idioms exist in the codebase — pick by whether `awaitX` returns a typed value:

   - **Void return** — the dialog's button does the work inside its own `onClick`, then unblocks the caller. Use `cont.cancel()` for confirm, dismiss, and `onDismissRequest`. `cancel()` is idempotent, so no guard is needed. Reference: [`screen/data/AlertDialogs.kt`](../../app/src/main/java/yokai/presentation/settings/screen/data/AlertDialogs.kt) (`awaitCreateBackup`, `awaitRestoreBackup`).
   - **Typed return** (`R?`) — the dialog hands a result back to the caller, who runs the side effect. Use `if (cont.isActive) cont.resume(value)` for confirm and `if (cont.isActive) cont.resume(null)` for dismiss. The `isActive` guard prevents `IllegalStateException` from a confirm-then-dismiss double-resume in the same frame. Reference: [`screen/advanced/AlertDialogs.kt`](../../app/src/main/java/yokai/presentation/settings/screen/advanced/AlertDialogs.kt) (`awaitCleanupDownloadedChapters`).

   Single-button confirm dialogs can use the existing `alertDialog.simple { … }` builder in [`DialogHostState.kt`](../../app/src/main/java/yokai/domain/DialogHostState.kt) — no new helper needed.

8. **Activity / Compose locals.** For widgets that need `MainActivity` (color profile picker, biometric prompt, etc.) use `LocalContext.current as? MainActivity`. For plain `Activity` casts (e.g. `SecureActivityDelegate.setSecure`) use `LocalContext.current as? Activity`. For coroutine launches use `rememberCoroutineScope()`. For dialogs use `LocalDialogHostState.currentOrThrow`. For navigation to other Conductor controllers use `LocalRouter.currentOrThrow` (deprecated long-term, but still required for legacy-only destinations).

9. **Add probes.** See the **Probes** section below.

10. **Compile-check.** `./gradlew :app:compileDevDebugKotlin` must succeed cleanly.

11. **Soak-test on device.** Build the debug APK in Android Studio. Open Settings → `<X>` (tap → Compose), exercise every preference, watch the Logcat filter from the Probes section to confirm each interaction. Long-press to verify the legacy fallback still works. Search Settings for two preferences in the section and confirm both surface (verifies the search-indexing flip from step 3).

12. **Strip probes.** Once soak-tested, remove the `LOG_TAG` constant, the `LaunchedEffect(Unit)` entry log, and every `Logger.i(LOG_TAG) { … }` call. Make this a separate `chore(settings): remove soak probes` commit so the diff is reviewable. Keep `Logger.e(e) { … }` calls inside real error paths (catch blocks, etc.) — those aren't probes.

13. **Regression test.** Run `./gradlew :app:testDevDebugUnitTest --tests "yokai.domain.preference.PreferencesKeyUniquenessTest"`. It must stay green. If it fails, two preference accessors now share a SharedPreferences key — find the duplicate from the failure message and give one its own slot. If you added a new `*Preferences` class for this section, register an instance of it in the test's `accessorInstances` list.

14. **Changelog.** Add one bullet to `CHANGELOG.md` under `## [Unreleased] → ### Changes`, matching the voice of the existing Security and Advanced bullets:

    > Settings → `<X>` rebuilt on the new Compose pattern. Long-press the row to reach the legacy version. [one sentence summarizing what's user-visible different, if anything.]

    **Do NOT** bump `_versionName` or `versionCode` in `app/build.gradle.kts`. **Do NOT** rename `[Unreleased]` to a numbered version header — see the **Versioning policy** section below.

15. **Ship.** Run `/ship`. The skill bakes in `--repo unseensnick/yokai-y2k --base main`, no `## Test plan` section, no `🤖 Generated with [Claude Code]` footer, no `Co-Authored-By` line. If the recent `code-reviewer` + `doc-reviewer` parallel run before push catches anything, fix and re-push to the same branch before merge.

After merge: update the **Status** table at the top of this file in a follow-up `docs(dev)` commit (can go direct-to-main as a docs chore).

## Probes

Add a probe protocol while building the screen — strip before merge.

Pattern:

- Top of the `object Settings<X>Screen` body, after `readResolve`:

  ```kotlin
  private const val LOG_TAG = "Settings<X>Screen"
  ```

- Top of `getPreferences()`, before the `buildList { … }`:

  ```kotlin
  LaunchedEffect(Unit) {
      Logger.i(LOG_TAG) { "entered compose <x> settings" }
  }
  ```

- Inside each `onValueChanged` / `onClick` lambda, one line:

  ```kotlin
  Logger.i(LOG_TAG) { "<keyOrAction> → $it" }   // for toggles / list selections
  Logger.i(LOG_TAG) { "<action> clicked" }      // for click-only rows
  ```

Imports needed during the soak: `androidx.compose.runtime.LaunchedEffect`, `co.touchlab.kermit.Logger`. All three (constant, effect, calls) come out together in the cleanup commit — that's why a separate `chore(settings): remove soak probes` commit is the recipe.

**Android Studio Logcat filter** (preferred — clean UI, no shell):

```
package:eu.kanade.tachiyomi.debugY2k tag:Settings<X>Screen level:info
```

The `package:` clause keeps Compose-recomposition noise from other flavors out. The `level:info` clause drops verbose / debug system noise.

**Adb command-line equivalent** (when working without Studio open):

```
adb logcat -s Settings<X>Screen:I
```

`:I` is the minimum log level (info). The `-s` flag silences everything except the listed tag.

Expected output during a healthy soak: one `entered compose <x> settings` line per screen open, plus one line per preference interaction with the new value. If a row toggles without producing a log line, its `onValueChanged`/`onClick` is wired wrong.

## Versioning policy

This catches future-me every release cycle. Three rules:

1. **No version bumps during migration PRs.** Leave `_versionName` and `versionCode` in `app/build.gradle.kts` alone. Alpha builds distribute by branch / tag, not by per-commit version numbers, so bumping per commit just burns versions that never ship.

2. **No `[Unreleased]` rename in migration PRs.** The `[Unreleased]` section in `CHANGELOG.md` becomes the GitHub release draft when the next release is cut. Adding bullets to it is correct; renaming it to `## [1.9.7.5.X]` is not — that happens only at release-cut time.

3. **Bumps and version-cuts happen only when the user explicitly says "cut a release".** At that point: rename `[Unreleased]` → `[N.N.N.N.N]`, add a new empty `[Unreleased]` block above it for the next cycle, and bump both `_versionName` and `versionCode`.

If something in this project's `.claude/rules/workflow.md` ever appears to contradict the above, this handbook wins — the rules file has been updated and re-updated to match, but stale copies show up in upstream merges.

## Common pitfalls

Observed during the Security and Advanced ports. Listing them here so the next session doesn't rediscover them.

- **SharedPreferences key collisions silently cause `ClassCastException`.** Two preference accessors that share a key cause a runtime crash on first untyped read. Caught the appIcon case via Settings → search. [`PreferencesKeyUniquenessTest`](../../app/src/test/java/yokai/domain/preference/PreferencesKeyUniquenessTest.kt) guards against this — keep it green. If you add a new `*Preferences` class, register an instance of it in the test.

- **Dialog continuation double-resume.** Only an issue for `awaitX` helpers that return a typed value via `cont.resume(...)`. Guard with `if (cont.isActive)` to survive confirm-then-dismiss races. Dialogs that use `cont.cancel()` for completion are fine — `cancel()` is idempotent. See recipe step 7 for the split.

- **Compose-search indexing is upstream-blocked.** See [`SettingsSearchHelper.kt:69-72`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/search/SettingsSearchHelper.kt). Until upstream solves this, keep every flipped section's legacy controller alive and indexed.

- **No Compose UI test infrastructure.** The project has no `androidInstrumentedTest` setup, no Compose test rules wired, no screenshot tests. Don't attempt TDD on the Compose screens — soak-test on device via probes instead. Unit tests for pure logic in `domain/`, `data/`, `core/main` are still encouraged.

- **`gh pr create` defaults to upstream.** The fork's parent is `null2264/yokai`, and bare `gh pr create` targets it. `/ship` already passes `--repo unseensnick/yokai-y2k --base main`; if you call gh manually for any reason, include those flags or you'll file against upstream and have to close + recreate.

- **Misleading string-resource names.** Some moko-resources keys don't match their visible label (e.g. `lock_with_biometrics` actually displays "Require unlock"). Don't assume the resource key spells the user-visible text — open the strings.xml when in doubt.

## File map

The surface area of a typical port. Read these:

- [`Preference.kt`](../../app/src/main/java/yokai/presentation/component/preference/Preference.kt) — the DSL primitives.
- [`ComposableSettings.kt`](../../app/src/main/java/yokai/presentation/settings/ComposableSettings.kt) — the base class for every settings screen.
- [`SettingsComposeController.kt`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsComposeController.kt) — the bridge base.
- [`DialogHostState.kt`](../../app/src/main/java/yokai/domain/DialogHostState.kt) — `simple { … }` builder + the `dialog<R> { cont -> … }` machinery.

Edit-touched (per port):

- The section's existing legacy controller (rename + move to `controllers/legacy/`).
- The new Compose screen.
- The new bridge controller (re-occupies the old path).
- [`SettingsMainController.kt`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/SettingsMainController.kt) — flip the row.
- [`SettingsSearchHelper.kt`](../../app/src/main/java/eu/kanade/tachiyomi/ui/setting/controllers/search/SettingsSearchHelper.kt) — point search at the legacy class.
- `CHANGELOG.md` — bullet under `[Unreleased] → Changes`.

Create-new (per port):

- `app/src/main/java/yokai/presentation/settings/screen/Settings<X>Screen.kt`.
- Optionally `app/src/main/java/yokai/presentation/settings/screen/<x>/AlertDialogs.kt` if the section has multi-choice dialogs.

Do not touch:

- `app/build.gradle.kts` (no version bump — see Versioning policy).
- Reference clones under `refs/` (read-only).

## Verification

Copy-paste runnable block at the end of a port, before opening the PR:

```bash
./gradlew :app:compileDevDebugKotlin
./gradlew :app:testDevDebugUnitTest --tests "yokai.domain.preference.PreferencesKeyUniquenessTest"
```

Plus on-device:

- Open Settings → `<X>` (tap). Confirm the new Compose UI renders all rows.
- Exercise every preference. Confirm Logcat shows the entry log + one log per interaction.
- Long-press Settings → `<X>`. Confirm the toast appears and the legacy screen renders. Toggle one preference and confirm it persists.
- Search Settings for two preferences from the section. Confirm both surface and route to the legacy screen (until Compose-side search lands upstream).
- For every other settings section (the ones not migrated yet), open and skim to confirm no routing regressions.
- After stripping the probes in the cleanup commit, repeat the on-device steps above with Logcat empty to confirm the screen still works without the probes.

Once all of the above pass, run `/ship`.
