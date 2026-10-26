# Data and backup

## Purpose

How Reikai stores both content types and carries them between installs. Mihon's SQLDelight schema is the base; Reikai adds its own tables beside it (novels, merge groups, custom info, the adult-source metadata, chapter-number corrections) and its own fields in Mihon's backup format, so one `.tachibk` holds the manga and the novel library and a restore rebuilds both.

## How it works

### Schema and migrations

The schema lives in `data/src/main/sqldelight/tachiyomi/` as `.sq` files (fresh installs build from them through `Schema.create`) and numbered `.sqm` migrations (existing installs reach the same schema through them). Migrations carry Reikai's own numbers, never Mihon's: a Mihon schema change is ported as the next Reikai-numbered `.sqm`, and its header names the upstream file. The driver runs them off the database's own `user_version` against `Database.Schema.version` (`DatabaseBindings.providesSqlDriver`), so adding a `.sqm` needs no `versionCode` bump. The rules for writing one are [database.md](../../../.claude/rules/database.md).

Novels keep their own tables, never manga's: `novels`, `novel_chapters`, `novel_history`, `novel_tracks`, `novels_categories`, `custom_novel_info`, `novel_chapter_number_override`, with the views `novelLibraryView`, `novelHistoryView` and `novelUpdatesView` mirroring manga's. Categories are one shared `category` table with a `content_type` column. Reikai-owned columns keep Reikai's names (`page_count`, `content_type`, the `_id` columns of Reikai tables) rather than taking upstream's prefixes.

Reikai's domain types and repository interfaces live in `:domain` (`reikai.domain.*`), the implementations in `:data` (`reikai.data.*`), as upstream does for its own. No database type reaches `:app`; an app-side read goes through a repository.

### Dates

Every date column of both content types is epoch milliseconds on disk and a `kotlin.time.Instant` in the domain, through `InstantColumnAdapter`. A date nobody knows is NULL, never 0, so nothing can show it as the start of 1970: `62.sqm` turned every stored 0 into NULL in the manga, chapter and track tables, as upstream's migration does, and in the novel tables beside them. History the user removed is a NULL read time on both types, where it was 0. The one 0 kept on purpose is the library date (`user_favorite_at`, `novels.favorite_at`), because NULL there already means "not in the library": an entry added before the date was recorded keeps 0, `Manga.UNKNOWN_FAVORITE_AT`. The formats outside the database (the backup, the source API, the tracker APIs) still write 0 for an unknown date and are converted at that boundary by `toInstantOrNull` and `toEpochMillisOrZero`, so a backup reads the same in an older build and in Mihon.

### Raw chapter numbers

`novel_chapters.raw_chapter_number` holds what chapter-number recognition starts from: the plugin's own number, -1 for none ([details.md](details.md), Chapter numbers). It is nullable. `63.sqm` gave every stored row its stored `chapter_number`, and a row written without one, as a restore writes it, takes its `chapter_number` the same way (`NovelChapterRepositoryImpl.insertRow`), so a corrected chapter starts from its correction until its page syncs again. Backups do not carry it, since the next sync of the row's page writes the plugin's number. Pinned by `RawChapterNumberMigrationTest` and `NovelChapterRowWriteTest`.

### The duplicate merge on upgrade

`50.sqm` (manga, with upstream's table rename) and `51.sqm` (novels) merge every pair of rows sharing a source and url into one entry, and every pair of chapters sharing an entry and url into one chapter, so both tables can take a unique index on the pair. Foreign keys are off inside a migration and nothing cascades, so each Reikai child table is re-pointed by hand: the survivor's own row wins, otherwise the first copy's by upstream's precedence; one copy supplies the adult-source metadata, tags and titles together; a merge group left with fewer than two members is deleted with its child rows. The stored stitch keeps every row whose chapter and group survive, so only a group the merge touched restitches.

State outside the database follows through a record. Both migrations write each merged-away id, its survivor and its old title to `dedupe_merged_ids`, and each merged-away chapter with its kept row to `dedupe_merged_chapter_ids`, since a migration cannot touch files or preferences. Three readers use it:

- `NovelDownloadRekeyMigration` (182) maps an old-scheme novel download whose ids no longer resolve to its survivor, written after every file that resolves directly, overwriting nothing.
- `MigrateMergePrefsToGroupsMigration` (189) maps merged-away ids in the old merge and unmerge preferences, so a manual merge naming a copy is kept.
- `MergedDuplicateCarryMigration` (198) moves a copy's custom cover to the survivor when the survivor has none and deletes the copy's file either way (neither entry table uses AUTOINCREMENT, so a new entry could be handed the freed id), then re-points both saved download queues (`MergedDuplicateDownloads.remapQueues`). Download folders are left for `carryFolders`, which `App` runs after `Migrator`, off the main thread, on every launch until every folder is merged; only then is the record emptied.

A folder named for a copy's title is renamed in place to the survivor's title when the survivor has none (`renameDownloadFolder`, the rule the novel title rename uses). When both have one, `DownloadFolderMerge` copies each chapter the survivor lacks under a `_merge_tmp` name, checks names and lengths, renames it into place and only then deletes the source; a chapter the survivor already has stays in both folders. The pair is skipped as unfinished when `hasRoomToCopy` says the volume would drop below the download floor.

### Creating a backup

`BackupCreator` streams the file field by field to the gzip sink instead of encoding one `Backup` object, since the one-shot encode ran out of memory on large libraries. A protobuf message is its fields concatenated in any order, so the stream is wire-identical to the encoded form. Manga (field 1) and novels (700) go through one driver, `backupEntries` in `BackupEntryDriver.kt`, with `MangaBackupCreator` and `NovelBackupCreator` as its two `BackupEntryParts`: the driver decides which series are written and which parts each carries, the type fills its own message. The driver builds them `BACKUP_CHUNK` (100) series at a time (`backupChunk`), and each part reads its table once for the whole chunk, so a backup makes a few queries per hundred series rather than several per series, and holds only one chunk's chapters at a time. Three reads stay outside that: custom info (both types) and adult-source metadata (manga) are read per series, and manga history reads the chunk's chapters a second time to key history by url, where Mihon's creator reads them once for both parts; novels key history by url in the query instead. Merge group members outside the library always come along, written with favorite off, because a restore resolves a group member only against a row it restored. The same `BackupOptions` gate both types; there is no separate novel toggle.

After writing, `BackupCreator` calls `BackupFileValidator.checkReadable`, the same streamed decode the restore uses with no source or tracker lookup, so a malformed file fails the backup without loading every LN plugin.

### Restoring

`BackupRestorer` reads the file twice. Pass 1 (`readBackupSummary`) decodes only the small fields and counts entries; pass 2 streams fields 1 and 700 through `restoreEntryStream`, restoring `RESTORE_CHUNK` entries per transaction through `restoreBatch`, which retries entry by entry when a batch fails so one bad entry does not roll back its neighbours. Categories of both types restore first and are joined before anything else starts, because entries and the category-id settings map to live categories by name. The manga and novel streams then run beside the preference and extension-store jobs; each ends by restoring its merge groups (`restoreMerges`, through `RestoreMergeGroups`) once every entry has a fresh id, then putting every group of that type in its first library member's categories (`AlignGroupCategories`), since entries were filed one at a time from the backup and a group can otherwise come back disagreeing ([merged-series.md](merged-series.md) "Categories"). The whole restore runs inside `ReconcileMergedChapters.afterPass`, since a fresh install marks every migration done and nothing else would stitch the restored groups.

An entry is matched by `url + source` or inserted, and its children re-link by stable keys (chapters by url, categories by name, tracks by tracker id, history by chapter url). Where the series is already on the device, both restorers fold the backup in by the kernels in `RestoreMergeRules.kt`: details win only when the backup fetched them and the device never did, a chapter keeps read and bookmark from either side and the further progress, a bound track keeps the device's row and takes only a further chapter read, and history keeps the later read and the longer duration. Each entry carries its predicted next update (`BackupManga` 113, Mihon's, and `BackupNovel` 27); after re-predicting from the restored chapters, both restorers keep the one the entry came with, the device's over the backup's, while it is still ahead of today's window (`restoredNextUpdate`). A backup without the field restores the prediction.

Preferences split by path. `PreferenceRestorer.restoreApp` wraps the generic write in `AppPreferenceCarry`, which carries retired keys into their replacements and skips keys a backup someone else made must not set (JS snippets, WebView dev tools, the installed plugin list, the plugin repos, the extension installer, app-state keys). `restoreSource` runs the generic write alone, so Source settings never reach an app setting. Category-id settings of both types are translated by category name to the ids just minted.

Kitsu scores are stored on Kitsu's native 2 to 20 scale, and were stored out of 10 before; `38.sqm` doubles the device's own rows, but a restore runs no migration. So pass 1 also decodes each entry's tracks alone (`mangaTrackScores`, `novelTrackScores` in `KitsuBackupScores.kt`) and `KitsuBackupScoreScale.of` decides once for the whole file: the 720 marker, the 719 or 718 marker (both first written after `38.sqm`), or any Kitsu score above 10 on either type, means the native scale, and otherwise every Kitsu score of both types is doubled as it is decoded in pass 2 (`rescaleKitsu`). Doubled wrongly, when every Kitsu score in the file is 10 or lower: a backup from Reikai nightlies r1525 to r2424, one from r2648 or r2682 that carries only field 717, and a Mihon backup written since Mihon moved to the 2 to 20 scale.

A novel's title display took its own local-override bit in `46.sqm`, which gave it to every novel whose sort bit was set. A backup with neither field 719 nor any field 717 entry predates the bit, so each novel it inserts gets the same remap as it is decoded (`withDisplayBitFromSort`, pinned against `46.sqm` by `SchemaChainMigrationTest` and `NovelDisplayBitRestoreTest`). A novel already on the device keeps its own flags.

A restore installs only from repos the user trusts. `BackupFileValidator.validate` sorts, before the restore starts, the extension apps (field 710) and LN plugins (read from the App settings entries) the backup had and this install lacks, through `RestoreInstallSources` and the one rule both sides call, `planInstalls`: listed by a repo already on the device, it installs after the restore; listed only by repos the backup brings (field 106, or its plugin repos setting), it shows under each of them, since trusting any one installs it; listed nowhere, it is named. With Missing extensions off the first group is still named, for the user to install by hand. Every backup repo the device lacks is offered once on the Restore screen with its address and its signing key read live (none for a keyless store), and one that can't be read or now signs with another key is named instead. The repos are read side by side, since the Restore button waits on them. Only a Reikai backup records its apps and plugins; one from another app brings its repos and nothing to install. `ExtensionStoreRestorer` never writes a store the device lacks and `AppPreferenceCarry` never writes the plugin repos, so a repo comes back only through `RestoreExtensionInstaller.addTrusted`, which fetches it live and adds it only under the key the user saw. With Missing extensions on, `RestoreExtensionInstaller.install` then installs what is missing, one at a time through `installExtension` and `installFromUrl`, after the rest of the restore, and logs each one that did not come back with its reason.

### Backup fields Reikai adds

| Field | Holds |
|---|---|
| 700, 701 | Novels and novel categories |
| 702, 711 | Merge groups as `{url, source}` refs, novels and manga |
| 703, 712 | Unmerge pairs, read only from a 0.3.x backup |
| 710 | Installed manga and novel extension apps |
| 713, 714 | Custom info as 0.3.x wrote it, read only, folded onto entries by `LegacyCustomInfo` |
| 715, 716 | Saved searches and feed rows, keyed by serialized `SourceKey` |
| 717 | Each backed-up novel source's name, the twin of Mihon's 101 |
| 718 | Marker: every merge group is stored (absent in 0.3.x) |
| 719 | Marker: category flags carry the sort-override bit (absent from Mihon and Reikai 0.3.2 and older) |
| 720 | Marker: Kitsu scores are on Kitsu's native 2 to 20 scale (absent from Mihon and Reikai 0.3.2 and older) |

Inside entries, custom info rides at Komikku's and Yokai's `BackupManga` numbers (602, 603, 800 to 805), which `BackupNovel` reuses (`BackupCustomInfoFields`); `BackupChapter` adds `pageCount` (700) and `sourceChapterNumber` (701); `BackupCategory` adds `hidden` (900, Komikku's) and `contentType` (8001); the reading log rides at `BackupManga` 701 and `BackupNovel` 28 ([statistics.md](statistics.md)).

## Key files

- `data/src/main/sqldelight/tachiyomi/migrations/`: the Reikai-numbered migrations; `50.sqm` and `51.sqm` are the duplicate merge.
- `data/src/main/sqldelight/tachiyomi/data/dedupe_merged_ids.sq`: the record of what the merge discarded.
- `data/src/main/java/tachiyomi/data/DatabaseBindings.kt`: `providesSqlDriver`, which tests call rather than restating the adapters.
- `app/src/main/java/mihon/core/migration/migrations/MergedDuplicateCarryMigration.kt`: `invoke`, `carryFolders`.
- `app/src/main/java/reikai/data/dedupe/MergedDuplicateDownloads.kt` and `DownloadFolderMerge.kt`: `remapQueues`, `carryFolders`, `merge`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/Backup.kt` and `BackupFields.kt`: the root fields and their numbers.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupCustomInfo.kt`: `BackupCustomInfoFields`, `LegacyCustomInfo`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt`: `backup`, `writeEntries`.
- `app/src/main/java/reikai/data/backup/BackupEntryDriver.kt`: `BackupEntryParts`, `backupEntries`, `backupChunk`, `mergeGroupRefs`, `restoreBatch`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorer.kt`: `readBackupSummary`, `restoreMangaStream`, `restoreNovelsStream`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/NovelRestorer.kt`: the novel half; `MangaRestorer.kt` beside it.
- `domain/src/main/java/reikai/domain/backup/RestoreMergeRules.kt`: `backupDetailsWin`, `foldBackup`, `backupChapterReadAhead`, `mergedHistory`.
- `domain/src/main/java/reikai/domain/backup/KitsuBackupScoreScale.kt` and `app/src/main/java/reikai/data/backup/KitsuBackupScores.kt`: the Kitsu scale decision and its pass-1 scan.
- `app/src/main/java/reikai/data/backup/AppPreferenceCarry.kt`: every app-key carry and skip.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupFileValidator.kt`: `validate`, `checkReadable`.
- `app/src/main/java/reikai/data/backup/RestoreInstalls.kt`: `planInstalls`, `RestoreInstallSources` (`missing`, `plan`, `device`), `RestoreInstallPlan`, `ApprovedRepos`.
- `app/src/main/java/reikai/data/backup/RestoreExtensionInstaller.kt`: `addTrusted`, `install`.
- `app/src/main/java/reikai/presentation/backup/RestoreInstallItems.kt`: the Restore screen's install and trust cards.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupProtoReader.kt`: the streamed field reader both passes use.

## Invariants and traps

- **Never edit a shipped migration's SQL.** Devices have already run it; only `--` comment lines may change.
- **A table a migration rebuilds keeps every foreign key that points at it.** Either rename it aside under `PRAGMA legacy_alter_table = 1`, which leaves the referencing tables naming the table that takes its place (`62.sqm`), or rebuild every child in the same `.sqm` (`50.sqm`, which also merged rows). The driver checks foreign keys after migrating and refuses to open a database with a violation, and nothing cascades while a migration runs.
- **A migration cannot reach files or preferences.** State keyed by an id or title outside the database follows a merge through a record table and a preference migration that reads it, as the dedupe does.
- **A background job that reads migrated state waits for `Migrator.await()` first.** WorkManager can start a worker in a process with no Activity, before the preference migrations finish. The library, novel, tracker, adult-source update and backup workers wait; a migration that rewrites state another job reads adds the wait there.
- **Proto numbers are permanent.** A field once shipped is never reused or renumbered; a retired one stays declared or commented so its number is not taken.
- **Categories restore before entries.** An entry restored before its category exists falls into Default.
- **A plugin's captured site login is a sensitive key.** Its storage key has no private prefix, because the plugin reads it back by that exact name, so `PreferenceBackupCreator` drops it through `isSensitivePluginKey` unless sensitive settings are included.
- **A backup's Kitsu scale is one decision for the whole file, made before either stream restores.** Deciding per track would double a new-scale score of 10 or under from an unmarked file; the only per-track evidence is a score above 10, so it has to be read across every entry of both types first.
- **The download folder merge never overwrites.** A chapter not copied is never deleted, and the copy's folder goes only when this run emptied it, since a failed listing also reads as empty.

## Decisions

- **Reikai's backup fields sit in the 700 range.** Mihon uses 1 to 106 and Komikku 600 to 900 on entries; a clear range keeps an upstream or Komikku port from colliding.
- **Groups travel as `{url, source}` refs.** Ids are reassigned on every restore, so stored ids would mean nothing on the target device.
- **A restore installs extensions only from repos the user trusts.** A backup can be anyone's file, so its own repos and keys are never trusted: a repo it brings is offered with its live key and added only once the user ticks it, and the key is read again before adding. Installing from a crafted file's repo would otherwise let it choose which apps and plugin scripts arrive. A repo with nothing to install is offered too, since trusting it is the only way a backup repo comes back.
- **The restore keeps the further chapter progress, not the backup's.** Mihon's rule could rewind the device; both types share the kernel.
- **The stitch cache is carried through the dedupe, not wiped.** Wiping it restitched every group on first open; the stale checks already find the groups the merge changed.
- **Download folders merge after the migrations, not inside one.** `MainActivity` blocks the main thread on migrations, and a large copy would hold the splash and restart on the next launch if killed.
- **A backup with none of the 718, 719 and 720 markers has its Kitsu scores taken as out of 10 unless one is above 10.** Mihon's backups carry no marker and write the 2 to 20 scale, so a Mihon file whose Kitsu scores all sit at 10 or under is doubled wrongly, as is a file from the Reikai nightlies listed under Restoring; the score test catches every such file with one Kitsu rating above 5 out of 10. Void if every supported source app writes the 2 to 20 scale or carries the marker.
- **A backup names novel sources without loading plugins.** `NovelSourceManager.nameOf` answers from the registry, then the last-seen record, then the id, so an automatic backup never evaluates every plugin.
- **No in-place import from a Yokai-era database.** Reikai's `applicationId` is `app.reikai`, so a Yokai install meets it only as a separate app; the route is backup and restore, and a Yokai `.tachibk` restores through the normal pipeline (its per-category sort, field 800 on categories, is not read).

## Upstream divergences

`// RK` islands carry this subsystem in `Backup.kt`, `BackupCategory.kt`, `BackupChapter.kt`, `BackupManga.kt`, `BackupCreator.kt` (streaming, novels, markers, `checkReadable`), `BackupRestorer.kt` (two-pass streaming, category ordering, novels, merges), `CategoriesRestorer.kt` (sort-override marker), `PreferenceRestorer.kt`, `BackupFileValidator.kt`, `BackupCreateWorker.kt`, `BackupRestoreWorker.kt` (the trusted repos), `RestoreOptions.kt` (Missing extensions), `RestoreBackupScreen.kt` (the install cards), `ExtensionStoreRestorer.kt` (no store the device lacks), `DownloadStore.kt` and `App.kt`. Recorded divergences: [upstream-sync.md](../upstream-sync.md) "Deliberate divergences" (backup streaming).

## Extending

- **A new table or column**: add the next-numbered `.sqm` and the matching `.sq` change, run `verifySqlDelightMigration`, and for a migration that moves rows add a seeded case to `SchemaChainMigrationTest`.
- **A new backed-up field**: take the next free number in the 700 range (or the entry message's Reikai range), add it to `BackupFields` when it is a root field, write it for both types, and add a round-trip case.
- **A new restore rule that holds for both types**: put it in `RestoreMergeRules.kt` and pin it in `RestoreMergeConformanceTest`.
- **A new app key that a shared backup must not set, or a retired key**: handle it in `AppPreferenceCarry`.

## Tests

Schema: `SchemaChainMigrationTest` (the migrations over rows seeded into `43.db`, every dedupe case over both types, and a novel's retired Last read stamp carried into history), `InstantDatesMigrationTest` (unknown dates and removed history become NULL on both types, every foreign key and view survives the rebuild), `RawChapterNumberMigrationTest`, plus `./gradlew verifySqlDelightMigration`. Dedupe carry: `MergedDuplicateCarryMigrationTest`, `MergedDuplicateDownloadsTest`, `NovelDownloadRekeyMigrationTest`, `SavedQueueRestoreOrderTest`. Backup format: `BackupFieldsTest`, `BackupProtoReaderTest`, `BackupStreamFramingTest`, `BackupCustomInfoWireTest`, `BackupEntryDriverTest`, `BackupChunkConformanceTest` (two series in one chunk come out as each does alone, with chapter-number corrections, excluded scanlators and reading sessions each landing on its own series, over the real queries, both types), `GroupMemberBackupConformanceTest`, `BackupNovelSourceNameTest`. Restore: `RestoreMergeConformanceTest`, `RestoreGroupCategoriesConformanceTest`, `RestoreHistoryConformanceTest`, `RestoreBatchRollbackTest`, `RestoreCategoryGateConformanceTest`, `RestoreCategoryMatchConformanceTest` (a category reused by name, and an entry's category orders mapped to this device's ids, both types), `CategoriesRestorerTest`, `PreferenceRestorerTest`, `KitsuBackupScoreScaleTest` (`:domain`), `KitsuScoreRestoreConformanceTest`, `NovelDisplayBitRestoreTest`, `NovelBackupRoundTripTest`, `MangaMergeBackupRoundTripTest`, `BackupDatesConformanceTest` (unknown dates written as 0 and read back as unknown), `BackupCustomInfoConformanceTest`, `BackupFileValidatorTest`, `RestoreInstallsTest` (where each missing app or plugin installs from, over manga apps, novel apps and plugins, and which repos are offered), `RestoreExtensionInstallerTest` (a trusted repo is added only under the key shown), `ExtensionStoreRestorerTest`, `LauncherIntentTest` (a backup opened from Files reaches the Restore screen).

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`, or `:data:test` for the schema tests.

## Related

- User doc: [backups.md](../../guides/backups.md).
- Merge groups in a backup: [merged-series.md](merged-series.md).
