# Off-path manifest

Every Mihon file Reikai has **deleted** because a Reikai-owned twin (`reikai.*`) fully replaced it. Most are UI files whose twin renders the surface instead; a few are domain interactors whose twin took over the behavior. Reikai is a standalone repo ported from Mihon by hand (see [upstream-sync.md](upstream-sync.md)), so a deleted upstream file leaves no local copy for the next sync to diff against. This manifest is that record, and [`scripts/off-path-check.ps1`](../../scripts/off-path-check.ps1) reads it during a sync to diff each listed path across the sync range in the matching `refs/` clone and fail loudly if one changed, so an upstream change can never land on a file Reikai no longer uses.

When the check flags a path, open its **Replacement** and reconcile the upstream change into that twin by hand, exactly as if the file were still `// RK: inert`. The `refs/mihon` clone holds the pre-delete blob, so the change is a diff of upstream-before against upstream-after, applied deliberately into the twin.

## What enforces this

The manifest used to be enforced by remembering to run the sync check. Three things now fail loudly instead,
because the two worst failures (a deleted file coming back, and a reroute nobody declared) were previously
silent and left no trace.

- **`pre-commit`, every commit.** No manifested path may exist in the working tree, and every `Replacement`
  must exist. A resurrected file means two implementations of one surface; a replacement that does not exist
  means the row protects nothing.
- **`pre-commit`, when `refs/mihon` is present.** Staging the deletion of a file Mihon still has requires a
  manifest row for it in the same commit, which closes the "a new reroute that skips the manifest is
  invisible" hole. Without the clone it warns instead of blocking, so a fresh clone is never stuck.
- **`commit-msg`, on sync commits.** `scripts/off-path-check.ps1` writes `.git/off-path-checked` recording the
  upstream HEAD it ran against, and a `chore: sync Mihon...` subject is rejected unless that stamp exists and
  matches the current `refs/mihon` HEAD. Running the check stops being optional.
- **`docs-lint` CI** mirrors the first of these. CI has no `refs/` clones, so it cannot diff against upstream.

Install the hooks on a fresh clone with the command in [upstream-sync.md](upstream-sync.md).

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
| app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceDialogs.kt | mihon | reikai/presentation/browse/components/EntryRemoveDialog.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt | mihon | reikai/presentation/track/EntryTrackInfoDialog.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverViewModel.kt | mihon | reikai/presentation/details/EntryCoverScreenModel.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt | mihon | reikai/presentation/details/EntryInfoBox.kt |
| app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt | mihon | reikai/presentation/library/LibrarySettingsSheet.kt |
| app/src/main/java/mihon/feature/library/QueryNodeExtensions.kt | mihon | reikai/presentation/library/LibraryQueryMatch.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/CreateCategoryWithName.kt | mihon | reikai/presentation/category/CategoryActions.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/ReorderCategory.kt | mihon | reikai/presentation/category/CategoryActions.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/DeleteCategory.kt | mihon | reikai/domain/category/DeleteCategoryCleanup.kt |

**A row tracks the file's CURRENT upstream path, not the name Reikai deleted.** When upstream renames a
manifested file, repoint the row at the new path, because the check `cat-file`s the path at upstream HEAD and,
finding nothing, reports VANISHED and **skips the diff entirely**. A renamed row therefore reports the same
message forever while silently covering up every later change to it, which is the opposite of what the manifest
is for. The cover model is the worked example: Reikai deleted `MangaCoverScreenModel.kt`, the deferred ViewModel
migration (`mihonapp/mihon#3594`, mihon `c3b99aea0`) renamed it to `MangaCoverViewModel.kt` upstream, and the row
now names the new path so a real change to it is caught. The rename itself still arrives with the migration.

So treat **VANISHED as unresolved, never as expected**: find whether upstream renamed the file
(`git log --oneline --follow --diff-filter=R -- <new path>`) and repoint the row, or confirm it was genuinely
deleted and drop the row with a note. Only a deliberate, recorded conclusion closes one.

`TrackInfoDialog.kt` carries one deferred upstream change: mihon `98705910e` (`mihonapp/mihon#3609`), the
deferred ViewModel migration's crash fix, which lands with that bundle rather than on its own. That commit is
now BELOW the synced base, so the off-path check reports the row clean and no longer nags; the deferred-changes
record in [upstream-sync.md](upstream-sync.md) is the only tracker of it until the migration is ported. On a
sync, still confirm nothing new touched the file (`git log --oneline <base>..HEAD -- "*TrackInfoDialog.kt"`).

The three category interactors each scoped themselves to the manga-visible rows. Once a category can span both libraries those rows overlap the novel-visible ones, so a create, reorder or delete that only sees one library writes an order or a preference scrub that is wrong for the other. `CategoryActions` does all three over the whole table instead.
