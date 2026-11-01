# Live twins

Mihon files that stay live on the manga path with a Reikai-owned novel file doing the same job. Nothing is deleted, so the [off-path manifest](off-path-manifest.md) never sees them, and an upstream fix can land on the manga half alone. `scripts/off-path-check.ps1` reads this table and lists every row whose Mihon file changed in the sync range. A listed row never fails the check: it is a prompt to read the upstream change and take it to the novel side too, or to say why it does not apply.

The pin is what holds the two together today: the shared kernel, the capability or the conformance test. A row with `none` is an open gap. A pure data twin (`manga.sq`, a repository) has `type only`.

Add a row when a Reikai file starts mirroring a Mihon one. Reikai-to-Reikai twins do not belong here.

| Mihon file | Reikai twin | Pin |
|---|---|---|
| app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateWorker.kt | app/src/main/java/reikai/data/novel/update/NovelUpdateWorker.kt | `updateBySource`, `checkUpdateEntry`, `runLibraryUpdate` |
| app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt | app/src/main/java/reikai/data/novel/update/NovelUpdateNotifier.kt | none |
| app/src/main/java/eu/kanade/tachiyomi/data/library/MetadataUpdateWorker.kt | app/src/main/java/reikai/data/novel/NovelMetadataRefresh.kt | `updateBySource`, `refreshedCover` |
| app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt | app/src/main/java/reikai/novel/download/NovelDownloadManager.kt | `DownloadRetry`, `hasRoomToDownload`, `downloadNetworkIssue` |
| app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt | app/src/main/java/reikai/novel/download/NovelDownloadManager.kt | `EmptiedPausedQueueConformanceTest`, `movesDownloadFolder` |
| app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt | app/src/main/java/reikai/novel/download/NovelDownloadCache.kt | `DownloadIndexRules` |
| app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt | app/src/main/java/reikai/novel/download/NovelDownloadProvider.kt | names reused from `DownloadProvider` |
| app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadNotifier.kt | app/src/main/java/reikai/novel/download/NovelDownloadNotifier.kt | `downloadErrorTitle` |
| app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt | app/src/main/java/reikai/presentation/reader/NovelReaderViewModel.kt | `ReaderEngine`, `ScrollCrossingConformanceTest` |
| app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt | app/src/main/java/reikai/presentation/updates/NovelUpdatesViewModel.kt | recents engine |
| app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt | app/src/main/java/reikai/presentation/history/NovelHistoryViewModel.kt | recents engine |
| app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt | app/src/main/java/reikai/data/novel/NovelChapterSync.kt | `ChapterSyncConformanceTest`, `foundRelease` |
| app/src/main/java/mihon/domain/source/interactor/UpdateMangaFromRemote.kt | app/src/main/java/reikai/data/novel/NovelRefresh.kt | `refreshedCover`, `keptCover`, `refreshedTitle` |
| app/src/main/java/mihon/domain/migration/usecases/MigrateMangaUseCase.kt | app/src/main/java/reikai/domain/novel/interactor/MigrateNovelUseCase.kt | migrate conformance tests |
| data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt | data/src/main/java/reikai/data/novel/NovelRepositoryImpl.kt | type only |
| data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt | data/src/main/java/reikai/data/novel/NovelChapterRepositoryImpl.kt | type only |
| data/src/main/sqldelight/tachiyomi/data/manga.sq | data/src/main/sqldelight/tachiyomi/data/novels.sq | type only |
