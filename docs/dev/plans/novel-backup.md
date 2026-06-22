# Novel backup & restore

The novel twin of Mihon's backup/restore: favorited light novels (with chapters, read state, history, categories, tracker links, and cross-source merges) ride the same backup file as manga, alongside a record of which sources you had installed so a restore can bring them back.

For the user-facing architecture of backups (file format, what is and isn't included, sync), see [docs/backup-restore.md](../../backup-restore.md). For the features this backs up, see the [novel merge](novel-merge.md) and [novel tracking](novel-tracking.md) plans.

## Goal

A restore on a fresh install reproduces the full light-novel library exactly as it was: the same novels, read positions, reading history, categories, tracker bindings, and merge groups, plus the sources needed to read them. Novels are first-class library content, so they live in the one backup file with manga rather than in a separate export.

## Why

Before this, a backup captured only the manga library. A user with a sizeable novel library had no way to move it to a new device or recover it after a wipe, while their manga came back intact. The light-novel vertical (domain, reader, downloads, merge, tracking) was complete, so backup was the last core piece: it serializes everything the earlier work added (history, tracker links, categories, merges, grouping). That ordering is also why it shipped last in the active sequence.

## Approach

Novels piggyback on the existing backup pipeline. Creating a backup walks the favorited novels and writes each one (plus its chapters, history, tracks, and category names) into the same `.proto.gz` file manga go into. On restore, it reads those entries back and rebuilds the novel library, matching each novel to one that may already exist or inserting a new one. Two things need special care: the sources (which aren't part of the library data) and the merge groups (which are stored by internal id, and ids change on restore).

**Proto classes (proto = Protocol Buffers, the compact binary format backups serialize to).** Each novel concept gets a backup model that mirrors its Mihon manga counterpart: `BackupNovel`, `BackupNovelChapter`, `BackupNovelTracking`, `BackupNovelHistory`, `BackupNovelCategory`, and (in `BackupNovelMerge.kt`) `BackupNovelMergeGroup`, a group serialized as a list of `BackupNovelSourceRef` (`{url, source}`) refs. Every field carries a `@ProtoNumber`. The novel classes are grafted onto the root `Backup` object in the 700 number range, fenced with `// RK`, so they sit clear of Mihon's own fields (1-106) and Komikku's (600/610) and won't collide if upstream or Komikku patches are pulled in later. The root fields are `backupNovels` (700), `backupNovelCategories` (701), `backupNovelMerges` (702), and `backupNovelUnmerges` (703).

**Creating the backup.** `NovelBackupCreator` reads the favorited novels and builds the proto objects, the novel twin of `MangaBackupCreator`. `BackupCreator` calls it inside its own `// RK` island, gated by the same `BackupOptions` toggles as manga (no separate novel toggle, since novels are library content like any other). History rows come from a new query pair on `novel_history.sq`: one read for backup, one idempotent upsert for restore.

**Restoring.** `NovelRestorer` mirrors `MangaRestorer`: it does a match-or-insert by `url + source` (find an existing novel with that source-and-url pair, otherwise insert a fresh row), then re-links the child data by stable keys rather than by id, since ids are assigned fresh on insert: chapters by url, categories by name, tracks by tracker id, history by chapter url. `BackupRestorer` runs the novel restore as a self-contained sequential job (categories first, then each novel, then the merge prefs) so it doesn't race the parallel manga and preference restore jobs.

**Merge/grouping surviving id reassignment.** A merge group is stored in preferences as a set of internal novel ids. Those ids are reassigned on restore, so the raw stored values would point at the wrong (or nonexistent) novels. The fix: each manual merge group and each unmerge pair is serialized into the backup as stable `{url, source}` refs (`BackupNovelMergeGroup` / `BackupNovelSourceRef`), and `NovelRestorer.restoreMerges` rebuilds the id-keyed preference against the freshly restored ids. To stop the stale raw values from being written back, the two id-keyed merge preference keys are excluded from the generic preference restore in `PreferenceRestorer` (a `// RK` skip). See the [novel merge](novel-merge.md) plan for the merge model itself.

Manga merges use the same `{url, source}` re-keying, since manga ids are reassigned on restore too. `BackupMangaMerge` holds `{url, source: Long}` refs (root proto fields 711/712), `BackupCreator` serializes the manga merge and unmerge groups, the manga merge keys join the `PreferenceRestorer` skip, and `MangaRestorer.restoreMerges` rebuilds them against the restored ids (covered by `MangaMergeBackupRoundTripTest`). The manga restore also runs categories first: `BackupRestorer` awaits the category job before the manga and app-settings jobs, because `MangaRestorer.restoreCategories` maps each backup category to a live one by name, so the categories must already be in the database or the entry falls into the Default category.

**Installed-sources follow-on.** A library is only useful if you can reach its sources, and Mihon backs up the extension *repos* but not which extensions you had installed. Two halves close that gap:

- Manga extensions: `BackupExtension` records the installed manga extensions (`ExtensionBackupCreator` writes them). On restore, `ExtensionRestorer` runs after the repos are restored, looks each one up in the now-populated available list by package name, and reinstalls via the standard `ExtensionManager` installer (respecting the user's installer mode). The installs are awaited on the restore's own scope, bounded per install, so they complete within the restore rather than lingering on the app-lifetime scope and racing the trust evaluation, which would otherwise leave the same extension showing as both trusted and untrusted. The restored repo's signing key auto-trusts the reinstalled extensions; the manually-trusted list (`SourcePreferences.trustedExtensions`) is app-state and is never backed up, by design. Anything whose repo is missing can't be matched and is written to the restore log.
- Novel plugins: no new proto is needed. A novel plugin's install state (its canonical URL plus metadata) already rides the preference backup, so once preferences restore, the normal lazy loader (`LnPluginInstaller.ensureLoaded`) re-downloads each plugin from its saved URL on the next novel-screen open. The restore path deliberately does *not* reinstall them eagerly: a restore-time reinstall just duplicated that work and stalled on any unreachable repo.

**Backward compatibility.** Older backups made before novels existed still restore: Protocol Buffers default any absent field (so the novel lists come back empty), and the backup validator does not version-gate, so a pre-novel file is accepted unchanged.

## Key files

Backup models (proto classes), all `// RK` net-new:
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupNovel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupNovelChapter.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupNovelTracking.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupNovelHistory.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupNovelCategory.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupNovelMerge.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupExtension.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/Backup.kt` (`// RK` fields, 700 range)

Create / restore:
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/NovelBackupCreator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/ExtensionBackupCreator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/NovelRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/ExtensionRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/PreferenceRestorer.kt` (`// RK` skip of the id-keyed merge keys)
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt` and `.../restore/BackupRestorer.kt` (`// RK` grafts)

Supporting:
- `app/src/main/sqldelight/tachiyomi/data/novel_history.sq` (backup read + idempotent restore upsert)
- `app/src/main/java/reikai/novel/install/LnPluginInstaller.kt` (lazy re-download of novel plugins from saved URLs)
- `app/src/test/java/eu/kanade/tachiyomi/data/backup/NovelBackupRoundTripTest.kt` (merge id-remap round-trip + proto mapping)

Note: the original commit included a `NovelPluginRestorer.kt`; it has since been removed. Novel-plugin re-download now happens through the lazy loader (`LnPluginInstaller`) off the preference backup, with no dedicated restorer, and `BackupRestorer` documents this in a `// RK` comment.

## Status

Shipped (commit `a6027edf2`), on-device verified (Z Fold): a real backup includes the novel library (titles absent from the pre-novel backup, about +1.9 MB decompressed) and restores it, with merge groups rebuilt so grouped novels stay grouped. Unit-tested via `NovelBackupRoundTripTest` (merge id-remap round-trip and proto mapping).

Round-2 restore robustness fixes (on-device verified by the user, fresh install + personal backup): `29df060d2` (manga category-restore race + manga merge re-keying via `BackupMangaMerge`), `850b18dae` (extensions no longer show twice after a restore: the reinstall is awaited on the restore scope and a now-trusted extension clears any stale untrusted entry), `72e5be282` (the "what to restore" options screen no longer needs a second tap; the file-picker result is pushed from a `LaunchedEffect`).

Deferred follow-up (round 2, "Restore-path onboarding"): today, sources a restore can't auto-bring-back (a manga extension whose repo or APK is missing, or a novel plugin that failed to re-download) only land in the restore log. The follow-up turns that passive log into a guided post-restore step that surfaces the unmatched extensions/plugins as an actionable list, grouped by repo, that walks the user through reinstalling them. It builds directly on the installed-sources backup shipped here.

## Decisions & tradeoffs

- **Proto numbering in the 700 range.** Chosen to leave Mihon's existing field numbers (1-106) and Komikku's patch range (600/610) untouched, so a future upstream sync or Komikku-derived patch can't collide with Reikai's novel fields. Proto numbers are permanent once shipped (they're the wire identity of a field), so picking a clear, documented range up front avoids a painful renumber later.
- **Id remap via `{url, source}` refs instead of trusting stored ids.** Merge groups are stored by internal novel id, but ids are reassigned on every restore, so the stored ids are meaningless on the target device. Serializing each group as `{url, source}` refs and rebuilding against the restored ids is the only correct option. The cost is excluding the two id-keyed merge preference keys from the generic preference restore, so the raw (stale) values never get written; the rebuild owns those keys instead.
- **Match-or-insert by `url + source`, re-link children by stable keys.** Mirrors the manga restore exactly. It makes restore idempotent and merge-friendly: restoring onto a device that already has some of the novels updates them in place rather than duplicating, and child rows re-link by url/name/tracker-id rather than by the ids that just changed.
- **No eager novel-plugin reinstall on restore.** Their state rides the preference backup and the lazy loader self-heals on the next novel-screen open, so an eager reinstall only duplicated work and risked stalling the whole restore on one down repo. Manga extensions, by contrast, do need an explicit `ExtensionRestorer` because Mihon backs up only the repos, not the installed set.
- **Backward compatibility comes free.** Protobuf defaults absent fields and the validator doesn't version-gate, so pre-novel backups restore unchanged with empty novel lists. No version flag or migration was added to the backup format.
- **No separate novel backup toggle.** Novels are gated by the same `BackupOptions` as manga because they're first-class library content; adding a distinct toggle would be surface area for no real user need.
