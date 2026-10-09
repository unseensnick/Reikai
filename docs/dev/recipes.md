# Recipes

Short how-tos for the changes that recur, each with the files involved and the check that catches a
mistake. Paths are under `app/src/main/java/` unless they say otherwise. How to run the tests named
here is in [testing.md](testing.md).

## Add a preference migration

A preference migration runs once on upgrade, when the app moves from an older `versionCode` to one at
or above the migration's gate.

1. Add a class under `mihon/core/migration/migrations/` implementing `Migration`
   (`mihon/core/migration/Migration.kt`), annotated `@Inject` and `@ContributesIntoSet(AppScope::class)`,
   and take what it needs as constructor parameters. `ChapterNameSuffixMigration` is the model.
2. Give it its own gate: `override val version: Float = 200f` (or the next free number). The current top
   is `ChapterNameSuffixMigration` at `199f`; never reuse a gate another migration holds.
3. Raise `versionCode` in `app/build.gradle.kts` to that gate and add the migration to the comment above
   it that lists each gate. This is the one mid-cycle `versionCode` bump allowed; `versionName` waits for
   the release cut.
4. Write a test under `app/src/test/java/mihon/core/migration/migrations/` that runs it through
   `Migrator.initialize(old, BuildConfig.VERSION_CODE, listOf(migration))` from an older version, as
   `ChapterNameSuffixMigrationTest` does, with `Migrator.release()` in `@AfterEach`.

How gating works: `App.initializeMigrator()` passes the stored `last_version_code` and the build's
`versionCode` to `Migrator`. A fresh install (old version 0) runs only `Migration.ALWAYS` migrations
(`InitialMigrationStrategy`), so a gated migration never runs there; an upgrade runs every migration
whose `version` falls in `(old + 1)..new` (`VersionRangeMigrationStrategy`). A gate above the build's
`versionCode` does nothing at all, which is why step 3 matters.

Checks: `scripts/di-interop-check.ps1` (pre-commit and CI) fails a class implementing `Migration`
without `@ContributesIntoSet`. Nothing checks gate uniqueness; read
`grep -rn "override val version" app/src/main/java/mihon/core/migration/migrations/` before picking one.

## Add a SQLDelight migration

A schema change runs off the database's own `user_version`, not off `versionCode`, so it needs **no**
`versionCode` bump.

1. Change the table in `data/src/main/sqldelight/tachiyomi/data/*.sq` (views are in `.../view/*.sq`).
2. Add the next-numbered file in `data/src/main/sqldelight/tachiyomi/migrations/` (the highest is
   `61.sqm`, so the next is `62.sqm`) with the same change. Never copy Mihon's `.sqm` number, never edit
   the SQL of an existing `.sqm`, and put an index change in a migration of its own.
3. If the migration rebuilds or renames a table, `DROP VIEW` every view over it and recreate each view
   with its current `view/*.sq` text; a table rename rewrites dependent views to read the dropped table.
   `50.sqm` is the worked example.
4. If it moves or merges rows, add a data test in the shape of
   `data/src/test/java/reikai/data/migration/SchemaChainMigrationTest.kt`, which starts from the `43.db`
   snapshot and runs the real chain.

Checks: `./gradlew verifySqlDelightMigration` (CI on every pull request and nightly) applies every
`.sqm` to `data/src/main/sqldelight/43.db` and compares the result with the `.sq` files. Never
regenerate or delete `43.db`. Full rules: [.claude/rules/database.md](../../.claude/rules/database.md).

## Add a tracker

1. Implement it under `eu/kanade/tachiyomi/data/track/<name>/`, extending `BaseTracker(id, name)`. A
   tracker that can bind novels overrides `supportsNovels = true` (and `searchNovel` when novel search
   differs); `RanobeDb` and `NovelList` are novel trackers, `Anilist` serves both.
2. Register it in `TrackerManager`: a new id constant in the companion (Reikai's ids are 60 and
   100 to 102; an id is stored with every track, so it never changes), a property, and an entry in the
   `trackers` list inside its `// RK` island.
3. Add its login to `SettingsTrackingScreen`'s services group as a `TrackerPreference`, using the
   browser OAuth, `LoginDialog`, `TokenLoginDialog` or `TrackerWebViewLoginActivity` pattern already
   there. An OAuth callback host also needs its `<data android:host=...>` in `AndroidManifest.xml` and a
   branch in `TrackLoginActivity`.
4. Novels reach a tracker through `reikai/domain/track/EntryTrackPort.kt`, whose ports ask
   `Tracker.supportsContent(isNovel)`, so a tracker that declares `supportsNovels` needs no wiring there.
5. Add it to the hand-kept `cases()` in `app/src/test/java/reikai/data/track/TrackerMetadataAccessTest.kt`.

Checks: nothing iterates `TrackerManager.trackers` in a test, so the case list and a duplicate id are
caught only by review.

## Add a backup field

Backups are protobuf. Reikai's own numbers sit in ranges Mihon does not use (700 and up at the root), so
a Mihon backup still reads in Reikai and the reverse.

- **A top-level field:** add the constant to `BackupFields` (`eu/kanade/tachiyomi/data/backup/models/BackupFields.kt`)
  and the property with that `@ProtoNumber` on `Backup`. `BackupCreator`, `BackupRestorer` and
  `BackupFileValidator` stream the file one field at a time by these numbers, so write and read it there.
  `BackupFieldsTest` fails until the constant and the annotation agree.
- **A per-entry field:** it lands on both `BackupManga` and `BackupNovel` in the same commit, written by
  `MangaBackupCreator` and `NovelBackupCreator` and read by `MangaRestorer` and `NovelRestorer`. Shared
  per-entry steps run through `reikai/data/backup/BackupEntryDriver.kt`; fields both types carry belong
  on a shared interface, as `BackupCustomInfoFields` does for custom info.

Checks: a round trip through the real proto (`NovelBackupRoundTripTest`, `BackupCustomInfoWireTest`)
and a conformance test over both restorers (`BackupCustomInfoConformanceTest` is the shape). Nothing
compares the two entry models' field numbers for you.

## Add a settings screen or row

Rows are `Preference.PreferenceItem.*` inside a `Preference.PreferenceGroup`, returned from a screen's
`getPreferences()`. Read preferences off the graph with `remember { context.appGraph.x }` at the top of
the composable, never inside a row.

1. A new screen is an `object` implementing `SearchableSettings`
   (`eu/kanade/presentation/more/settings/screen/SearchableSettings.kt`).
2. Add it to `SettingsMainScreen`'s item list so it can be opened, and to `settingScreens` in
   `SettingsSearchScreen.kt` so search can find its rows. A screen missing from that list is invisible to
   search, and so is a row on a sub-screen that is not itself listed there.
3. A row that is expensive to build can read `LocalSettingsIndexing`, which is true while search builds
   its index, and skip the work.

Search matches a visible row's title or subtitle, skips `InfoPreference` rows and hidden groups, and a
screen whose `isEnabled()` returns false is left out. Checks: none automated; search for the new row's
title in the app.

## Add a ViewModel

Every screen is a Voyager `Screen` backed by an AndroidX `ViewModel` from the Metro graph
([.claude/rules/screen-conventions.md](../../.claude/rules/screen-conventions.md)).

- **Graph dependencies only:** annotate the class `@Inject`, `@ViewModelKey` and
  `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`, take dependencies as
  constructor parameters, and resolve it with `metroViewModel<FooViewModel>()`.
  `ClearDatabaseViewModel` in `ClearDatabaseScreen.kt` is the small example.
- **A value only the call site has** (an id): mark the class `@AssistedInject` and those parameters
  `@Assisted`, and nest an `interface Factory : ManualViewModelAssistedFactory` annotated
  `@AssistedFactory`, `@ManualViewModelAssistedFactoryKey` and `@ContributesIntoMap(AppScope::class)`.
  Resolve it with `assistedMetroViewModel<FooViewModel, FooViewModel.Factory> { create(...) }`.
  `MangaViewModel` and its call in `MangaScreen` are the worked example.

Expose state as `val state: StateFlow<State>` with `field = MutableStateFlow(...)`, launch with
`viewModelScope.launchIO`, and never make the class `private`.

Checks: `scripts/di-interop-check.ps1` fails a class resolved by `metroViewModel` whose file has no
`@ViewModelKey`, and an assisted factory resolved without `@ManualViewModelAssistedFactoryKey`. It does
not check `@ContributesIntoMap`, so a missing one shows up only when the screen opens.

## Pin a rule both content types must follow

Write the rule once, in this order of preference ([.claude/rules/content-layer.md](../../.claude/rules/content-layer.md)):

1. **A shared kernel** both sides call: a plain function under `reikai/domain/<area>/` (in `:app` or
   `:domain`), tested once. `resolveDefaultCategoryIds` (`reikai/domain/category/DefaultCategoryResolution.kt`)
   is the model; the manga and novel library adders both call it.
2. **A typed capability** the compiler makes each type answer, when the types genuinely differ.
3. **One conformance test** over both adapters, when the engines stay separate: a
   `@ParameterizedTest` whose `@MethodSource` lists a manga half and a novel half
   (`PausedNoticeConformanceTest`, `SkipDuplicateForwardConformanceTest`). The shape is in
   [testing.md](testing.md#the-conformance-test-pattern).

A comment calling one function the twin of another names its pin in the fixed spelling `twin of X,
pinned by Y` (or `type only` for a data shape); the pre-commit hook rejects an added `twin of` or
`mirrors` comment without one. Before adding a kernel, search for an existing one and record the result
as a `Reuse:` footer in the commit. Check the test by mutation: delete the clause it pins, see it fail
for both types, restore it.

## Patch a Mihon file

Prefer a Reikai-owned file. When a Mihon file has to change (it exists in `refs/mihon`):

- Fence the change: `// RK -->` and `// RK <--` around a block, a one-line `// RK:` note above a single
  change, or a trailing `// RK`. Imports are exempt. `TrackerManager.trackers` and
  `LibrarySettingsViewModel` carry real islands.
- A member moved out leaves a one-line `// RK:` note naming its replacement; plain deletions stay
  unmarked. A whole-file rewrite carries `// RK: whole file, <why>, see <doc>` in its first lines and a
  row under Deliberate divergences in [upstream-sync.md](upstream-sync.md).
- Never put a marker in a Reikai-owned file.

Checks: the pre-commit hook rejects an added `RK` marker in a file `refs/mihon` does not have.
`pwsh scripts/rk-fence-report.ps1 -MihonBase <top ledger base>` lists every changed hunk in a shared
file that no marker covers; it is a report, not a gate, and the tree carries a backlog, so fence what
you touched. Rules: [.claude/rules/architecture.md](../../.claude/rules/architecture.md).

## Port an upstream Mihon change

Follow [upstream-sync.md](upstream-sync.md): find the commits from the top ledger row, run
`scripts/off-path-check.ps1` for deleted files, copy marker-free files verbatim and hand-merge fenced
ones, drift-check, then commit as `chore: sync Mihon ...` citing `mihonapp/mihon#N` and the upstream
short SHA, and add a ledger row. The `commit-msg` hook rejects a sync commit with no off-path stamp.
