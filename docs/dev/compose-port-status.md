# Compose + Voyager port status

**Snapshot date:** 2026-05-29
**Branch at audit:** `design/library-compose`
**Checklist source:** [.claude/rules/compose-port.md](../../.claude/rules/compose-port.md)
**Audit method:** delegated initial pass to an `Explore` subagent, then verified every cited line by reading the source directly. Citations below reflect the verified positions; if a citation here disagrees with anyone else's recollection, this doc is right (as of the snapshot date).

This document decays. Re-run the audit (an `Explore` subagent against the checklist, then verify the high-stakes findings inline) before any cross-cutting port or before assuming a screen is still in the state recorded here.

## Tally

| Bucket | Count | Screens |
|---|---|---|
| **PASS** (all applicable items) | 5 | `ExtensionRepoScreen`, `WebViewScreen` + `WebViewScreenModel`, `AboutLibraryLicenseScreen`, `AboutLicenseScreen`, `StoryBookScreen` |
| **PARTIAL** (6-8) | 10 | `LibraryScreen`, `OnboardingScreen`, `SettingsDataScreen`, `SettingsSecurityScreen`, `SettingsDownloadScreen`, `SettingsTrackingScreen`, `SettingsAppearanceScreen`, `SettingsAdvancedScreen`, `AboutScreen`, `NovelDetailsScreen` |
| **FAIL** (≤5) | 3 | `NovelTrackProbeScreen`, `NovelBrowseScreen`, `LnPluginHostProbeScreen` |

Standalone ScreenModels (audited in isolation, all PASS): `MangaLibraryScreenModel`, `NovelLibraryScreenModel`, `ExtensionRepoScreenModel`, `LnRepoScreenModel`, `LnPluginBrowseScreenModel`, `WebViewScreenModel`.

## Top three failure modes

1. **`Injekt.get<>()` / `injectLazy()` inside `@Composable`** in 10 surfaces (both library tabs now resolved; only the `Content()` shell's single `Injekt.get<UiPreferences>()` at [LibraryScreen.kt:67](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:67) remains) and across the six Compose settings screens whose entire `getPreferences()` body lives in `@Composable` scope. Violates rule 3.
2. **`PreferencesHelper` reached directly from the composable** in 6 surfaces. The library tabs are now resolved (display/badge/layout/category prefs come from `LibraryTabState`); the settings screens all still read prefs in `@Composable getPreferences()` private helpers. Violates rule 5.
3. **`GlobalScope.launch(Dispatchers.IO)` in production code** - **RESOLVED 2026-05-29.** Was at `SettingsAdvancedScreen.cleanupDownloads()`; the download cleanup now runs in [DownloadCleanupJob](../../app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCleanupJob.kt) (a `CoroutineWorker` enqueued via `enqueueUniqueWork(..., KEEP)`), so no `GlobalScope` remains on the screen surface. This was the only hard rule-6 violation in the codebase.

## Per-screen deltas

Compact list. Each entry: PASS counts as the applicable count; PARTIAL/FAIL lists only the failing items by rule number with a citation.

### Library

- **`LibraryScreen`** - **both tabs are now clean**; only one shell-level rule-3 spot remains. (Rule 8 resolved 2026-05-29 Tier 1; rules 3/5/9 on the manga tab resolved 2026-05-29 Tier 2 phases 2A/2B; same on the novel tab resolved 2026-05-29 Tier 2 phase 2C.)
  - Rule 3: **both tabs RESOLVED.** `MangaLibraryTabContent` and `NovelLibraryTabContent` no longer inject anything (`PreferencesHelper` / `UiPreferences` / `BasePreferences` / `NovelSourceManager` / etc. all moved into the respective ScreenModels). One residual: `Content()` (the tab shell) still reads `libraryActiveTab` via `Injekt.get<UiPreferences>()` at [LibraryScreen.kt:67](../../app/src/main/java/yokai/presentation/library/LibraryScreen.kt:67). Left deliberately: the shell hosts both ScreenModels and has no ScreenModel of its own; revisit if it gains one.
  - Rule 5: **both tabs RESOLVED.** The display/badge/layout/category pref reads now come from `LibraryTabState`; each composable derives only `columns` locally (needs screen width). Writes route through screen-model methods. On the novel side the shared-vs-independent display-pref duality (manga pref vs novel pref, picked by `useSharedLibraryDisplayPrefs`) is resolved reactively in `NovelLibraryScreenModel` via a `sharedOrNovel` `flatMapLatest` helper.
  - Rule 8: **RESOLVED 2026-05-29.** The `MangaDetailsController` launches and `PreMigrationController.navigateToMigration(...)` carry `// transitional: legacy ...` bridge comments. Same on `NovelLibraryTabContent`'s `NovelDetailsController` launch.
  - Rule 9: **both tabs RESOLVED.** Search + the suspend filter (track / download I/O) run in the ScreenModels, gated (`distinctUntilChanged` + a `debounce` on the raw-library flow to collapse download-cache renewal storms) so they don't re-run on cosmetic changes. Each composable keeps only the cheap, pure category-visibility / collapse / single-category pass plus `canMerge` / `canUnmerge` / `selectionHasRemoteSources` derivations (rule-9-permitted).

The two library ScreenModels themselves pass all applicable items.

### Settings (Compose-default)

- **`SettingsSecurityScreen`** - PARTIAL (7/9). Fails rules 3, 5.
  - Rule 3: `injectLazy()` at [SettingsSecurityScreen.kt:35-36](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:35) inside `@Composable getPreferences()`.
  - Rule 5: preference reads in private `@Composable` helpers, e.g. `preferences.lockAfter()` at [line 101](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:101), `preferences.hideNotificationContent()` at [line 110](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:110), `preferences.secureScreen()` at [line 123](../../app/src/main/java/yokai/presentation/settings/screen/SettingsSecurityScreen.kt:123).
- **`SettingsDataScreen`** - PARTIAL (7/9). Fails rules 3, 5.
  - Rule 3: `injectLazy()` at [SettingsDataScreen.kt:90-91](../../app/src/main/java/yokai/presentation/settings/screen/SettingsDataScreen.kt:90) inside `@Composable getPreferences()`.
  - Rule 5: preference reads in private `@Composable` helpers throughout the file.
- **`SettingsAdvancedScreen`** - PARTIAL (7/9). Fails rules 3, 5. (Rule 6 resolved 2026-05-29.)
  - Rule 6: **RESOLVED 2026-05-29.** The `GlobalScope.launch` cleanup was extracted to [DownloadCleanupJob](../../app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCleanupJob.kt); the screen's `onClick` now calls `DownloadCleanupJob.startNow(...)`.
  - Rule 3: `injectLazy()` inside `@Composable getPreferences()` (structural to the `ComposableSettings` base; sanctioned during migration per [settings-compose-migration.md](settings-compose-migration.md)).
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

## Known non-checklist issues (deferred)

These surface during Compose-library testing but are **not** nine-item violations and are intentionally left alone. Logged here so a future reader who hits them in logcat knows they were triaged, not missed.

- **`Failed to inflate ColorStateList` warning on the legacy MangaDetails header (2026-05-29).** Opening a manga from the library logs a recoverable `UnsupportedOperationException` resolving attr `colorOnContainerChecked`: the `MaterialButton` styled `@style/Theme.Widget.Button.TextButton` (parents `Widget.Material3.Button.TextButton`, [styles.xml:343](../../app/src/main/res/values/styles.xml:343)) in [manga_header_item.xml](../../app/src/main/res/layout/manga_header_item.xml) references an M3-only color attr the AppCompat-lineage `Theme.Tachiyomi` base doesn't define. Warning only (caught, framework falls back); TextButtons have no checked state so nothing renders wrong. **Deferred:** this is legacy view-system theming on a screen slated for the MangaDetails Compose port, not Compose-port debt. If ever silenced, prefer defining `colorOnContainerChecked` once in `Theme.Base` (theme-wide, no restyle) over reparenting the button style.

## Audit corrections from the first pass

For context if you compare this snapshot against the conversation that produced it: the first subagent pass over-graded `WebViewScreenModel` (FAIL) and `OnboardingScreen` (PASS), and several Injekt citations pointed at *import* lines rather than the actual *call sites*. The verified citations above all come from direct file reads. If you re-audit and find new disagreements, prefer your verified reads over any prior snapshot.
