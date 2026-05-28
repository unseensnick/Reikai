# Compose + Voyager port status

**Snapshot date:** 2026-05-28
**Branch at audit:** `design/library-compose`
**Checklist source:** [.claude/rules/compose-port.md](../../.claude/rules/compose-port.md)
**Audit method:** delegated initial pass to an `Explore` subagent, then verified every cited line by reading the source directly. Citations below reflect the verified positions; if a citation here disagrees with anyone else's recollection, this doc is right (as of the snapshot date).

This document decays. Re-run the audit (an `Explore` subagent against the checklist, then verify the high-stakes findings inline) before any cross-cutting port or before assuming a screen is still in the state recorded here.

## Tally

| Bucket | Count | Screens |
|---|---|---|
| **PASS** (all applicable items) | 5 | `ExtensionRepoScreen`, `WebViewScreen` + `WebViewScreenModel`, `AboutLibraryLicenseScreen`, `AboutLicenseScreen`, `StoryBookScreen` |
| **PARTIAL** (6-8) | 9 | `LibraryScreen`, `OnboardingScreen`, `SettingsDataScreen`, `SettingsSecurityScreen`, `SettingsDownloadScreen`, `SettingsTrackingScreen`, `SettingsAppearanceScreen`, `AboutScreen`, `NovelDetailsScreen` |
| **FAIL** (≤5) | 4 | `SettingsAdvancedScreen`, `NovelTrackProbeScreen`, `NovelBrowseScreen`, `LnPluginHostProbeScreen` |

Standalone ScreenModels (audited in isolation, all PASS): `MangaLibraryScreenModel`, `NovelLibraryScreenModel`, `ExtensionRepoScreenModel`, `LnRepoScreenModel`, `LnPluginBrowseScreenModel`, `WebViewScreenModel`.

## Top three failure modes

1. **`Injekt.get<>()` / `injectLazy()` inside `@Composable`** in 11 surfaces. Worst at [LibraryScreen.kt:78,126-131,136,148](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:78) (9 calls total across `Content()` and `MangaLibraryTabContent`) and across the six Compose settings screens whose entire `getPreferences()` body lives in `@Composable` scope. Violates rule 3.
2. **`PreferencesHelper` reached directly from the composable** in 7 surfaces. The library case is the heaviest ([LibraryScreen.kt:135-157](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:135), 21 inline `preferences.xxx().collectAsState()` reads); the settings screens all read prefs in `@Composable getPreferences()` private helpers. Violates rule 5.
3. **`GlobalScope.launch(Dispatchers.IO)` in production code** at [SettingsAdvancedScreen.kt:318](../../app/src/main/java/yokai/presentation/settings/screen/SettingsAdvancedScreen.kt:318). The call sits inside a `private fun cleanupDownloads()` annotated `@OptIn(DelicateCoroutinesApi::class)`, not directly in a composable, and the work legitimately must outlive screen scope. Violates rule 6, but the fix is WorkManager-ification (mirror the existing `LibraryUpdateJob` / `BackupCreatorJob` pattern), not `screenModelScope`.

## Per-screen deltas

Compact list. Each entry: PASS counts as the applicable count; PARTIAL/FAIL lists only the failing items by rule number with a citation.

### Library

- **`LibraryScreen`** - PARTIAL (5/9). Fails rules 3, 5, 8, 9.
  - Rule 3: 9 `Injekt.get<>()` calls inline. One in `Content()` at [LibraryScreen.kt:78](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:78), eight in `MangaLibraryTabContent` at [lines 126-131, 136, 148](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:126).
  - Rule 5: 21 inline `preferences.xxx().collectAsState()` reads in `MangaLibraryTabContent` at [lines 135-157, 164, 170-176, 227-228, 437](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:135).
  - Rule 8: launches `MangaDetailsController` at [lines 475, 587, 600](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:475) and `PreMigrationController.navigateToMigration(...)` at [line 708](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:708). These are real transitional bridges; annotate with the `// transitional: legacy ...` comment when next touched.
  - Rule 9: heavy inline logic in `MangaLibraryTabContent`. Most concretely, [LibraryScreen.kt:350-368](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:350) kicks off `MangaLibraryFilter.filter(...)` from inside a `produceState`, which calls `downloadManager.getDownloadCount(manga)` and `getTrack.awaitAllByMangaId(mangaId)` (real I/O) from a composable. Plus `selectionHasRemoteSources` ([211-221](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:211)), `canUnmerge` ([230-246](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:230)), `seriesTypes` map build ([308-319](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:308)). The `produceState` work in particular belongs in the ScreenModel.

The two library ScreenModels themselves pass all applicable items.

### Settings (Compose-default)

- **`SettingsSecurityScreen`** - PARTIAL (7/9). Fails rules 3, 5.
  - Rule 3: `injectLazy()` at [SettingsSecurityScreen.kt:35-36](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:35) inside `@Composable getPreferences()`.
  - Rule 5: preference reads in private `@Composable` helpers, e.g. `preferences.lockAfter()` at [line 101](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:101), `preferences.hideNotificationContent()` at [line 110](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:110), `preferences.secureScreen()` at [line 123](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:123).
- **`SettingsDataScreen`** - PARTIAL (7/9). Fails rules 3, 5.
  - Rule 3: `injectLazy()` at [SettingsDataScreen.kt:90-91](../../app/src/main/java/yokai/presentation/settings/screen/SettingsDataScreen.kt:90) inside `@Composable getPreferences()`.
  - Rule 5: preference reads in private `@Composable` helpers throughout the file.
- **`SettingsAdvancedScreen`** - FAIL (5/9). Fails rules 3, 5, 6.
  - Rule 6 (most acute on the surface): `GlobalScope.launch(Dispatchers.IO, CoroutineStart.DEFAULT)` at [SettingsAdvancedScreen.kt:318](../../app/src/main/java/yokai/presentation/settings/screen/SettingsAdvancedScreen.kt:318) inside `private fun cleanupDownloads()`. The call is `@OptIn(DelicateCoroutinesApi::class)`-marked and the work must outlive screen scope. Fix path: WorkManager (mirror `LibraryUpdateJob`/`BackupCreatorJob`), not `screenModelScope`.
  - Rule 3: `injectLazy()` at [line 82-85](../../app/src/main/java/yokai/presentation/settings/screen/SettingsAdvancedScreen.kt:82), plus `Injekt.get()` inside the cleanupDownloads body at [line 320](../../app/src/main/java/yokai/presentation/settings/screen/SettingsAdvancedScreen.kt:320).
  - Rule 5: inline preference reads in private `@Composable` helpers.

### Settings (not flipped, but Compose impl exists)

- **`SettingsDownloadScreen`** - PARTIAL (7/9). Fails rules 3, 5. Inline `injectLazy()` at [SettingsDownloadScreen.kt:33-35](../../app/src/main/java/yokai/presentation/settings/screen/SettingsDownloadScreen.kt:33). Preference reads at [lines 47-48, 53, 59](../../app/src/main/java/yokai/presentation/settings/screen/SettingsDownloadScreen.kt:47).
- **`SettingsTrackingScreen`** - PARTIAL (7/9). Fails rules 3, 5. Inline `injectLazy()` at [SettingsTrackingScreen.kt:48-51](../../app/src/main/java/yokai/presentation/settings/screen/SettingsTrackingScreen.kt:48). Direct `trackPreferences.trackUsername(...)` at [line 57](../../app/src/main/java/yokai/presentation/settings/screen/SettingsTrackingScreen.kt:57).
- **`SettingsAppearanceScreen`** - PARTIAL (7/9). Fails rules 3, 5. Inline `injectLazy()` at [SettingsAppearanceScreen.kt:59-60](../../app/src/main/java/yokai/presentation/settings/screen/SettingsAppearanceScreen.kt:59). Preference reads in private helpers (e.g., `nightMode().collectAsState()` at [line 78](../../app/src/main/java/yokai/presentation/settings/screen/SettingsAppearanceScreen.kt:78)).

### About

- **`AboutScreen`** - PARTIAL (7/9). Fails rules 3, 5.
  - Rule 3: `Injekt.get<PreferencesHelper>()` inline in `Content()` at [AboutScreen.kt:80](../../app/src/main/java/yokai/presentation/settings/screen/about/AboutScreen.kt:80).
  - Rule 5: same line, plus downstream preference reads in the composable body.
- **`AboutLibraryLicenseScreen`** - PASS. Static license display; no business logic; rule 2 N/A.
- **`AboutLicenseScreen`** - PASS. Navigates to `AboutLibraryLicenseScreen` (Voyager); rest is static.

### Onboarding

- **`OnboardingScreen`** - PARTIAL (8/9, rule 1 in transitional shape).
  - Rule 1: at [OnboardingScreen.kt:33](../../app/src/main/java/yokai/presentation/onboarding/OnboardingScreen.kt:33) this is `@Composable fun OnboardingScreen()`, not a Voyager `Screen` class. The Compose body is currently hosted by the legacy `OnboardingController` (Conductor). The body itself is clean; the migration step is to wrap it in a Voyager `Screen` so the host can switch from Conductor to Voyager. See the rule 1 note in [compose-port.md](../../.claude/rules/compose-port.md).

### Extensions

- **`ExtensionRepoScreen`** - PASS. **Reference pattern; cite this when teaching the checklist.** [ExtensionRepoScreen.kt:54](../../app/src/main/java/yokai/presentation/extension/repo/ExtensionRepoScreen.kt:54).

### WebView

- **`WebViewScreen`** - PASS (composable side).
- **`WebViewScreenModel`** - PASS. The `Injekt.get()` calls at [WebViewScreenModel.kt:22-23](../../app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewScreenModel.kt:22) are **default arguments in the primary constructor**, not composable reach-through. Under rule 3's soft-rule reading (class-level Injekt is acceptable during migration), this passes. Note: the earlier audit graded this FAIL on a misread of import lines as call sites.

### Storybook

- **`StoryBookScreen`** - PASS. Demo surface.

### Novel (probe/debug)

These four are bare `@Composable` functions, not `Screen` classes, with heavy state machines and `Injekt.get<>()` inline. The rule file explicitly exempts them from the checklist (they'll either harden into proper Screens or be deleted when the LN feature lands). Listed here for completeness, not as project debt:

- **`NovelTrackProbeScreen`** - FAIL (4/9). Bare composable at [line 70](../../app/src/main/java/yokai/presentation/novel/track/NovelTrackProbeScreen.kt:70). 3 `Injekt.get<>()` at [lines 71-73](../../app/src/main/java/yokai/presentation/novel/track/NovelTrackProbeScreen.kt:71). Inline state machine.
- **`NovelBrowseScreen`** - FAIL (4/9). Bare composable at [line 121](../../app/src/main/java/yokai/presentation/novel/browse/NovelBrowseScreen.kt:121). 6 `Injekt.get<>()` at [lines 123-127 and 574](../../app/src/main/java/yokai/presentation/novel/browse/NovelBrowseScreen.kt:123). Full 4-state browse state machine inline.
- **`NovelDetailsScreen`** - PARTIAL (5/9). Bare composable at [line 75](../../app/src/main/java/yokai/presentation/novel/details/NovelDetailsScreen.kt:75). 5 `Injekt.get<>()` at [lines 77-81](../../app/src/main/java/yokai/presentation/novel/details/NovelDetailsScreen.kt:77). Inline state machine.
- **`LnPluginHostProbeScreen`** - FAIL (4/9). Bare composable at [line 63](../../app/src/main/java/yokai/presentation/novel/probe/LnPluginHostProbeScreen.kt:63). 6 `Injekt.get<>()` at [lines 65-71](../../app/src/main/java/yokai/presentation/novel/probe/LnPluginHostProbeScreen.kt:65). Inline plugin-host management.

## What to do with this list

- **When you're about to port a screen**, scan its row above so you know the deltas before you start.
- **When you touch a PARTIAL screen for any reason**, fix the listed deltas in the same change *if the fix is small and local*. Don't bundle a full rewrite into an unrelated bug fix.
- **When you finish a port**, re-audit that row (just that row) and update this doc in the same commit.
- **Before any cross-cutting work** (theming, navigation, DI module change), re-run the full audit. The snapshot above is dated; assume it's wrong after the date is more than a month old.

## Audit corrections from the first pass

For context if you compare this snapshot against the conversation that produced it: the first subagent pass over-graded `WebViewScreenModel` (FAIL) and `OnboardingScreen` (PASS), and several Injekt citations pointed at *import* lines rather than the actual *call sites*. The verified citations above all come from direct file reads. If you re-audit and find new disagreements, prefer your verified reads over any prior snapshot.
