# Data and backup

## Purpose

How Reikai stores both content types and carries them between installs. Mihon's SQLDelight schema is the base; Reikai adds its own tables beside it (novels, merge groups, custom info, the adult-source metadata, chapter-number corrections) and its own fields in Mihon's backup format, so one `.tachibk` holds the manga and the novel library and a restore rebuilds both.

## How it works

### Schema and migrations

The schema lives in `data/src/main/sqldelight/tachiyomi/` as `.sq` files (fresh installs build from them through `Schema.create`) and numbered `.sqm` migrations (existing installs reach the same schema through them). Migrations carry Reikai's own numbers, never Mihon's: a Mihon schema change is ported as the next Reikai-numbered `.sqm`, and its header names the upstream file. The driver runs them off the database's own `user_version` against `Database.Schema.version` (`DatabaseBindings.providesSqlDriver`), so adding a `.sqm` needs no `versionCode` bump. The rules for writing one are [database.md](../../../.claude/rules/database.md).

Novels keep their own tables, never manga's: `novels`, `novel_chapters`, `novel_history`, `novel_tracks`, `novels_categories`, `custom_novel_info`, `novel_chapter_number_override`, with the views `novelLibraryView`, `novelHistoryView` and `novelUpdatesView` mirroring manga's. Categories are one shared `category` table with a `content_type` column. Reikai-owned columns keep Reikai's names (`page_count`, `content_type`, the `_id` columns of Reikai tables) rather than taking upstream's prefixes.

Reikai's domain types and repository interfaces live in `:domain` (`reikai.domain.*`), the implementations in `:data` (`reikai.data.*`), as upstream does for its own. No database type reaches `:app`; an app-side read goes through a repository.

### The duplicate merge on upgrade

`50.sqm` (manga, with upstream's table rename) and `51.sqm` (novels) merge every pair of rows sharing a source and url into one entry, and every pair of chapters sharing an entry and url into one chapter, so both tables can take a unique index on the pair. Foreign keys are off inside a migration and nothing cascades, so each Reikai child table is re-pointed by hand: the survivor's own row wins, otherwise the first copy's by upstream's precedence; one copy supplies the adult-source metadata, tags and titles together; a merge group left with fewer than two members is deleted with its child rows. The stored stitch keeps every row whose chapter and group survive, so only a group the merge touched restitches.

State outside the database follows through a record. Both migrations write each merged-away id, its survivor and its old title to `dedupe_merged_ids`, and each merged-away chapter with its kept row to `dedupe_merged_chapter_ids`, since a migration cannot touch files or preferences. Three readers use it:

- `NovelDownloadRekeyMigration` (182) maps an old-scheme novel download whose ids no longer resolve to its survivor, written after every file that resolves directly, overwriting nothing.
- `MigrateMergePrefsToGroupsMigration` (189) maps merged-away ids in the old merge and unmerge preferences, so a manual merge naming a copy is kept.
- `MergedDuplicateCarryMigration` (198) moves a copy's custom cover to the survivor when the survivor has none and deletes the copy's file either way (neither entry table uses AUTOINCREMENT, so a new entry could be handed the freed id), then re-points both saved download queues (`MergedDuplicateDownloads.remapQueues`). Download folders are left for `carryFolders`, which `App` runs after `Migrator`, off the main thread, on every launch until every folder is merged; only then is the record emptied.

A folder named for a copy's title is renamed in place to the survivor's title when the survivor has none (`renameDownloadFolder`, the rule the novel title rename uses). When both have one, `DownloadFolderMerge` copies each chapter the survivor lacks under a `_merge_tmp` name, checks names and lengths, renames it into place and only then deletes the source; a chapter the survivor already has stays in both folders. The pair is skipped as unfinished when `hasRoomToCopy` says the volume would drop below the download floor.

### Creating a backup

`BackupCreator` streams the file field by field to the gzip sink instead of encoding one `Backup` object, since the one-shot encode ran out of memory on large libraries. A protobuf message is its fields concatenated in any order, so the stream is wire-identical to the encoded form. Manga (field 1) and novels (700) go through one driver, `backupEntries` in `BackupEntryDriver.kt`, with `MangaBackupCreator` and `NovelBackupCreator` as its two `BackupEntryParts`: the driver decides which series are written and which parts each carries, the type fills its own message. Merge group members outside the library always come along, written with favorite off, because a restore resolves a group member only against a row it restored. The same `BackupOptions` gate both types; there is no separate novel toggle.

After writing, `BackupCreator` calls `BackupFileValidator.checkReadable`, the same streamed decode the restore uses with no source or tracker lookup, so a malformed file fails the backup without loading every LN plugin.

### Restoring

`BackupRestorer` reads the file twice. Pass 1 (`readBackupSummary`) decodes only the small fields and counts entries; pass 2 streams fields 1 and 700 through `restoreEntryStream`, restoring `RESTORE_CHUNK` entries per transaction through `restoreBatch`, which retries entry by entry when a batch fails so one bad entry does not roll back its neighbours. Categories of both types restore first and are joined before anything else starts, because entries and the category-id settings map to live categories by name. The manga and novel streams then run beside the preference and extension-store jobs; each ends by restoring its merge groups (`restoreMerges`, through `RestoreMergeGroups`) once every entry has a fresh id. The whole restore runs inside `ReconcileMergedChapters.afterPass`, since a fresh install marks every migration done and nothing else would stitch the restored groups.

An entry is matched by `url + source` or inserted, and its children re-link by stable keys (chapters by url, categories by name, tracks by tracker id, history by chapter url). Where the series is already on the device, both restorers fold the backup in by the kernels in `RestoreMergeRules.kt`: details win only when the backup fetched them and the device never did, a chapter keeps read and bookmark from either side and the further progress, a bound track keeps the device's row and takes only a further chapter read, and history keeps the later read and the longer duration.

Preferences split by path. `PreferenceRestorer.restoreApp` wraps the generic write in `AppPreferenceCarry`, which carries retired keys into their replacements and skips keys a backup someone else made must not set (JS snippets, WebView dev tools, the installed plugin list, the extension installer, app-state keys). `restoreSource` runs the generic write alone, so Source settings never reach an app setting. Category-id settings of both types are translated by category name to the ids just minted.

A restore installs nothing. `BackupFileValidator.validate` lists, before the restore starts, the extension apps (field 710) and LN plugins (read from the App settings entries) the backup had and this install lacks, beside Mihon's missing sources and trackers.

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
| 719 | Marker: category flags carry the sort-override bit (absent from Mihon and pre-0.3.0 Reikai) |

Inside entries, custom info rides at Komikku's and Yokai's `BackupManga` numbers (602, 603, 800 to 805), which `BackupNovel` reuses (`BackupCustomInfoFields`); `BackupChapter` adds `pageCount` (700) and `sourceChapterNumber` (701); `BackupCategory` adds `hidden` (900, Komikku's) and `contentType` (8001).

## Key files

- `data/src/main/sqldelight/tachiyomi/migrations/`: the Reikai-numbered migrations; `50.sqm` and `51.sqm` are the duplicate merge.
- `data/src/main/sqldelight/tachiyomi/data/dedupe_merged_ids.sq`: the record of what the merge discarded.
- `data/src/main/java/tachiyomi/data/DatabaseBindings.kt`: `providesSqlDriver`, which tests call rather than restating the adapters.
- `app/src/main/java/mihon/core/migration/migrations/MergedDuplicateCarryMigration.kt`: `invoke`, `carryFolders`.
- `app/src/main/java/reikai/data/dedupe/MergedDuplicateDownloads.kt` and `DownloadFolderMerge.kt`: `remapQueues`, `carryFolders`, `merge`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/Backup.kt` and `BackupFields.kt`: the root fields and their numbers.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupCustomInfo.kt`: `BackupCustomInfoFields`, `LegacyCustomInfo`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt`: `backup`, `writeEntries`.
- `app/src/main/java/reikai/data/backup/BackupEntryDriver.kt`: `BackupEntryParts`, `backupEntries`, `mergeGroupRefs`, `restoreBatch`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorer.kt`: `readBackupSummary`, `restoreMangaStream`, `restoreNovelsStream`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/NovelRestorer.kt`: the novel half; `MangaRestorer.kt` beside it.
- `domain/src/main/java/reikai/domain/backup/RestoreMergeRules.kt`: `backupDetailsWin`, `foldBackup`, `backupChapterReadAhead`, `mergedHistory`.
- `app/src/main/java/reikai/data/backup/AppPreferenceCarry.kt`: every app-key carry and skip.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupFileValidator.kt`: `validate`, `checkReadable`, `missingExtensionApps`, `missingPlugins`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupProtoReader.kt`: the streamed field reader both passes use.

## Invariants and traps

- **Never edit a shipped migration's SQL.** Devices have already run it; only `--` comment lines may change.
- **Every Reikai child row of a rebuilt Mihon table is rebuilt in the same `.sqm`.** The driver checks foreign keys after migrating and refuses to open a database with a violation, and nothing cascades while a migration runs.
- **A migration cannot reach files or preferences.** State keyed by an id or title outside the database follows a merge through a record table and a preference migration that reads it, as the dedupe does.
- **A background job that reads migrated state waits for `Migrator.await()` first.** WorkManager can start a worker in a process with no Activity, before the preference migrations finish. The library, novel, tracker, adult-source update and backup workers wait; a migration that rewrites state another job reads adds the wait there.
- **Proto numbers are permanent.** A field once shipped is never reused or renumbered; a retired one stays declared or commented so its number is not taken.
- **Categories restore before entries.** An entry restored before its category exists falls into Default.
- **A plugin's captured site login is a sensitive key.** Its storage key has no private prefix, because the plugin reads it back by that exact name, so `PreferenceBackupCreator` drops it through `isSensitivePluginKey` unless sensitive settings are included.
- **The download folder merge never overwrites.** A chapter not copied is never deleted, and the copy's folder goes only when this run emptied it, since a failed listing also reads as empty.

## Decisions

- **Reikai's backup fields sit in the 700 range.** Mihon uses 1 to 106 and Komikku 600 to 900 on entries; a clear range keeps an upstream or Komikku port from colliding.
- **Groups travel as `{url, source}` refs.** Ids are reassigned on every restore, so stored ids would mean nothing on the target device.
- **A restore lists extensions rather than installing them.** A backup can be anyone's file, and installing from it would let a crafted file choose which apps and plugin scripts arrive. Void if restore gains a trust check the file cannot supply.
- **The restore keeps the further chapter progress, not the backup's.** Mihon's rule could rewind the device; both types share the kernel.
- **The stitch cache is carried through the dedupe, not wiped.** Wiping it restitched every group on first open; the stale checks already find the groups the merge changed.
- **Download folders merge after the migrations, not inside one.** `MainActivity` blocks the main thread on migrations, and a large copy would hold the splash and restart on the next launch if killed.
- **A backup names novel sources without loading plugins.** `NovelSourceManager.nameOf` answers from the registry, then the last-seen record, then the id, so an automatic backup never evaluates every plugin.
- **No in-place import from a Yokai-era database.** Reikai's `applicationId` is `app.reikai`, so a Yokai install meets it only as a separate app; the route is backup and restore, and a Yokai `.tachibk` restores through the normal pipeline (its per-category sort, field 800 on categories, is not read).

## Upstream divergences

`// RK` islands carry this subsystem in `Backup.kt`, `BackupCategory.kt`, `BackupChapter.kt`, `BackupManga.kt`, `BackupCreator.kt` (streaming, novels, markers, `checkReadable`), `BackupRestorer.kt` (two-pass streaming, category ordering, novels, merges), `CategoriesRestorer.kt` (sort-override marker), `PreferenceRestorer.kt`, `BackupFileValidator.kt`, `BackupCreateWorker.kt`, `BackupRestoreWorker.kt`, `DownloadStore.kt` and `App.kt`. Recorded divergences: [upstream-sync.md](../upstream-sync.md) "Deliberate divergences" (backup streaming).

## Extending

- **A new table or column**: add the next-numbered `.sqm` and the matching `.sq` change, run `verifySqlDelightMigration`, and for a migration that moves rows add a seeded case to `SchemaChainMigrationTest`.
- **A new backed-up field**: take the next free number in the 700 range (or the entry message's Reikai range), add it to `BackupFields` when it is a root field, write it for both types, and add a round-trip case.
- **A new restore rule that holds for both types**: put it in `RestoreMergeRules.kt` and pin it in `RestoreMergeConformanceTest`.
- **A new app key that a shared backup must not set, or a retired key**: handle it in `AppPreferenceCarry`.

## Tests

Schema: `SchemaChainMigrationTest` (the migrations over rows seeded into `43.db`, every dedupe case over both types), plus `./gradlew verifySqlDelightMigration`. Dedupe carry: `MergedDuplicateCarryMigrationTest`, `MergedDuplicateDownloadsTest`, `NovelDownloadRekeyMigrationTest`, `SavedQueueRestoreOrderTest`. Backup format: `BackupFieldsTest`, `BackupProtoReaderTest`, `BackupStreamFramingTest`, `BackupCustomInfoWireTest`, `BackupEntryDriverTest`, `GroupMemberBackupConformanceTest`, `BackupNovelSourceNameTest`. Restore: `RestoreMergeConformanceTest`, `RestoreHistoryConformanceTest`, `RestoreBatchRollbackTest`, `RestoreCategoryGateConformanceTest`, `CategoriesRestorerTest`, `PreferenceRestorerTest`, `NovelBackupRoundTripTest`, `MangaMergeBackupRoundTripTest`, `BackupCustomInfoConformanceTest`, `BackupFileValidatorTest`, `LauncherIntentTest` (a backup opened from Files reaches the Restore screen).

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`, or `:data:test` for the schema tests.

## Related

- User doc: [backups.md](../../guides/backups.md).
- Merge groups in a backup: [merged-series.md](merged-series.md).
