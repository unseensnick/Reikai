# Off-path manifest

Every Mihon file Reikai has **deleted** because a Reikai-owned twin (`reikai.*`) fully replaced its UI. Reikai is a standalone repo ported from Mihon by hand (see [upstream-sync.md](upstream-sync.md)), so a deleted upstream file leaves no local copy for the next sync to diff against. This manifest is that record, and [`scripts/off-path-check.ps1`](../../scripts/off-path-check.ps1) reads it during a sync to diff each listed path across the sync range in the matching `refs/` clone and fail loudly if one changed, so an upstream change can never land on a file Reikai no longer renders.

When the check flags a path, open its **Replacement** and reconcile the upstream change into that twin by hand, exactly as if the file were still `// RK: inert`. The `refs/mihon` clone holds the pre-delete blob, so the change is a diff of upstream-before against upstream-after, applied deliberately into the twin.

## What is NOT here

- **Engine files** (a ScreenModel, repository, or the source manager) are never deleted; they stay live and minimally patched on the render path, and sync normally. Example still pending its surface: `eu/kanade/tachiyomi/ui/download/DownloadQueueScreenModel.kt` (replaced by `MangaDownloadQueueScreenModel`) is a dead ScreenModel kept `// RK: inert` until the download-subsystem unification (Road B) retires it there.
- **Partially collapsed files** keep their live remainder in place, marked `// RK` with what moved out, so they stay on the render path and are not listed here. Once nothing live remains, the file moves to the manifest below, as `MangaInfoHeader` did once its last live piece (the expandable description) became `ExpandableEntryDescription`.

## Manifest

The path is relative to the repo root and matches the `refs/` clone layout. `Upstream` selects which clone the check diffs (`mihon`, or `tsundoku` once the reader migrates). Each `app/`-prefixed row is machine-read by the sync script; keep the three-column shape.

| Upstream path | Upstream | Replacement |
|---|---|---|
| app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt | mihon | reikai/presentation/details/EntryDetailsContent.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaToolbar.kt | mihon | reikai/presentation/details/EntryToolbar.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaCoverDialog.kt | mihon | reikai/presentation/components/EntryCoverDialog.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt | mihon | reikai/presentation/browse/EntrySearchCardRow.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt | mihon | reikai/presentation/track/EntryTrackInfoDialog.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverScreenModel.kt | mihon | reikai/presentation/details/EntryCoverScreenModel.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt | mihon | reikai/presentation/details/EntryInfoBox.kt |
