# Novel download storage re-key

**Goal.** Downloaded novel chapters survive an uninstall/reinstall, a backup restore, and a storage-location move, and their on-disk folders are human-readable, matching how manga downloads already behave.

**Why.** Novel downloads were keyed by unstable numeric DB ids: `novel_downloads/<novelId>/<chapterId>.html`, with "downloaded" read from a `novel_chapters.is_downloaded` flag. On reinstall/restore the DB is rebuilt and every row gets a fresh id, so the path computed for a re-added chapter no longer matched the file already on disk, while the wiped flag reported "not downloaded". The download was effectively unrecoverable (tapping download was a silent no-op, since the engine skipped a chapter whose file existed), and the numeric folders were opaque. Manga avoided all of this by keying on stable sanitised names and deciding "downloaded" from a disk scan (`DownloadCache`), so its downloads survive the same events.

**Approach.** Give novels the manga architecture rather than patch the flag.

- **Stable-name paths.** `NovelDownloadProvider` now writes to `novel_downloads/<source>/<title>/<chapter>_<urlhash>.html`. Title + chapter naming is *reused* from the manga `DownloadProvider` (its `getMangaDirName` / `getChapterDirName`), not copied, so the two schemes cannot drift. The `<source>` segment keys on the stable plugin id (`Novel.source`) rather than a display name, since a novel source's name can change on a plugin update.
- **Disk-scan cache as the single source of truth.** New `NovelDownloadCache` holds an in-memory `source -> title -> chapter-file-names` tree rebuilt from disk, exposes a `changes` flow, renews on an interval, and invalidates on a storage-location change. The `is_downloaded` flag is no longer read anywhere; every reader (library badge/filter/sort, details chapter list, Updates row, reader chapter dialog, the download/delete actions) goes through the cache. Composables never call the cache directly: each ScreenModel computes a `downloadedChapterIds: Set<Long>` (reactive on `cache.changes`) and threads it in. The cache's query API is string-keyed (`source, title, chapterName, chapterUrl`) with typed `Novel`/`NovelChapter` shims, so the denormalised Updates rows query it without rebuilding a `Novel`, and a future unified layer can lift the string core.
- **Rename-on-sync.** Because the chapter title is now part of the path, a source re-titling a chapter would orphan its download. `syncChaptersWithNovelSource` detects a name change and renames the on-disk file (via `NovelDownloadManager.renameChapter`), mirroring manga's `DownloadManager.renameChapter`. The download manager is threaded through the sync and its callers (details, the library-update job, migration).
- **Crash/scan safety.** Writes go to a temp file then rename, reusing the manga `Downloader.TMP_DIR_SUFFIX`; the cache scan skips temp files, so a half-written chapter is never counted.
- **One-time migration.** `NovelDownloadRekeyMigration` (version `182f`) relocates existing `novel_downloads/<novelId>/<chapterId>.html` files into the new layout (resolve the novel + chapter from the DB, write at the new path, delete the old, prune the empty numeric dir). Best-effort per file: an unresolvable row is left in place (no data loss).

**Key files.**
- `reikai/novel/download/NovelDownloadProvider.kt` — path scheme, string + typed name API, temp-then-rename, `renameChapter`.
- `reikai/novel/download/NovelDownloadCache.kt` — disk-scan tree, `changes`, renew + storage-move invalidation.
- `reikai/novel/download/NovelDownloadManager.kt` — routes through the cache, `renameChapter`, no flag writes.
- `reikai/data/novel/NovelChapterSync.kt` (+ `NovelPageWalk.kt`, `NovelRefresh.kt`) — rename-on-sync threading.
- `data/.../view/novelLibraryView.sq`, `novelUpdatesView.sq`, migration `29.sqm` — views recreated without the download columns (the `is_downloaded` column is kept).
- `mihon/core/migration/migrations/NovelDownloadRekeyMigration.kt` — the one-time file relocation.

**Status.** Shipped in 0.3.0 (commit `004c8ce16`). Verified on-device: downloads survive reinstall and backup restore; storage-move shares the same disk-scan path and was not separately exercised. Unit tests cover the rename-on-sync wiring; the disk-scan cache and the relocation migration are Android file-I/O and were validated on-device.

**Decisions & tradeoffs.**
- **Chose full parity (a parallel `NovelDownloadCache`) over reconciling the flag from disk.** The decisive factor was reliability: a single source of truth (disk) makes flag-vs-disk drift structurally impossible, whereas a repair-the-flag approach depends on every sync path firing the reconcile. The cost is a novel-only cache class, retired by the download-subsystem unification (Road B), which the naming reuse keeps a code merge.
- **Kept the physical `is_downloaded` column**, recreating only the two views without it, to avoid a destructive table rebuild and keep backups round-tripping. The column is now dead (never read or written).
- **Bumped versionCode to 182** so the relocation migration reliably fires for every upgrader (a same-version migration is skipped on a reinstall of the same build).
- **No proto snapshot** for the cache (manga persists one for fast startup): a scan is always the fallback, novel libraries are smaller, and Road B inherits manga's snapshot anyway.
- **Deferred:** unifying the manga and novel download subsystems into one shared layer (Road B on the roadmap).
