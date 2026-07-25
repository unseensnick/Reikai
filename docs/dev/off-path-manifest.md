# Off-path manifest

Every Mihon file Reikai has **deleted** because a Reikai-owned twin (`reikai.*`) fully replaced it. Most are UI files whose twin renders the surface instead; a few are domain interactors whose twin took over the behavior. Reikai is a standalone repo ported from Mihon by hand (see [upstream-sync.md](upstream-sync.md)), so a deleted upstream file leaves no local copy for the next sync to diff against. This manifest is that record, and [`scripts/off-path-check.ps1`](../../scripts/off-path-check.ps1) reads it during a sync to diff each listed path across the sync range in the matching `refs/` clone and fail loudly if one changed, so an upstream change can never land on a file Reikai no longer uses.

When the check flags a path, open its **Replacement** and reconcile the upstream change into that twin by hand, exactly as if the file were still `// RK: inert`. The `refs/mihon` clone holds the pre-delete blob, so the change is a diff of upstream-before against upstream-after, applied deliberately into the twin.

## What is NOT here

- **Engine files** (a ScreenModel, repository, or the source manager) are never deleted; they stay live and minimally patched on the render path, and sync normally. An interactor that is still called stays too: the category interactors listed below went off-path only because nothing calls them any more, while their type-agnostic siblings (`RenameCategory`, `UpdateCategory`) remain live and are not listed. Example still pending its surface: `eu/kanade/tachiyomi/ui/download/DownloadQueueScreenModel.kt` (replaced by `MangaDownloadQueueScreenModel`) is a dead ScreenModel kept `// RK: inert` until the download-subsystem unification (Road B) retires it there.
- **Partially collapsed files** keep their live remainder in place, marked `// RK` with what moved out, so they stay on the render path and are not listed here. Once nothing live remains, the file moves to the manifest below, as `MangaInfoHeader` did once its last live piece (the expandable description) became `ExpandableEntryDescription`.
- **Reikai-own files**, even under a shared `tachiyomi/` path. A file Reikai added (e.g. the retired `novel_categories.sq`) has no `refs/mihon` counterpart, so deleting it is not a Mihon reroute and the check has nothing to diff. Only files that exist in `refs/mihon` belong here.

## Manifest

The path is relative to the repo root and matches the `refs/` clone layout. `Upstream` selects which clone the check diffs (`mihon`, or `tsundoku` once the reader migrates). Every row whose first column starts with a lower-case module directory is machine-read by the sync script; keep the three-column shape.

| Upstream path | Upstream | Replacement |
|---|---|---|
| app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt | mihon | reikai/presentation/details/EntryDetailsContent.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaToolbar.kt | mihon | reikai/presentation/details/EntryToolbar.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaCoverDialog.kt | mihon | reikai/presentation/components/EntryCoverDialog.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt | mihon | reikai/presentation/browse/EntrySearchCardRow.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt | mihon | reikai/presentation/track/EntryTrackInfoDialog.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverScreenModel.kt | mihon | reikai/presentation/details/EntryCoverScreenModel.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt | mihon | reikai/presentation/details/EntryInfoBox.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/CreateCategoryWithName.kt | mihon | reikai/presentation/category/CategoryActions.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/ReorderCategory.kt | mihon | reikai/presentation/category/CategoryActions.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/DeleteCategory.kt | mihon | reikai/domain/category/DeleteCategoryCleanup.kt |

The three category interactors each scoped themselves to the manga-visible rows. Once a category can span both libraries those rows overlap the novel-visible ones, so a create, reorder or delete that only sees one library writes an order or a preference scrub that is wrong for the other. `CategoryActions` does all three over the whole table instead.
