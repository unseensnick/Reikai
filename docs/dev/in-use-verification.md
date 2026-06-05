# In-use verification backlog

Behaviors that compile clean and follow a verified-parity pattern, but whose trigger
conditions are awkward to force on demand (they need a source to actually add or remove
chapters between refreshes). Confirm these opportunistically during normal use rather
than blocking a phase on them.

Tick an item once it has been seen working on-device in real use.

## Details refresh side effects

- [ ] **Novel , auto-download new chapters on pull-to-refresh.** With Settings -> Downloads
  -> "Download new novel chapters" on, refreshing a library novel that gained a chapter at
  the source should enqueue the new chapter for download.
  (`NovelDetailsScreenModel.handleSyncedChapters`)
- [ ] **Novel , removed-chapter download prompt.** When a refresh finds a downloaded chapter
  was removed at the source, the "Delete removed novel chapters" setting should apply:
  always delete, always keep, or ask (default ask -> confirmation dialog listing the count).
  (`NovelDetailsScreenModel.handleSyncedChapters` + `NovelDetailsDialog.ConfirmRemovedDownloads`)
- [ ] **Manga , auto-download new chapters on pull-to-refresh.** Same as the novel case for
  the Compose manga details screen. (`MangaDetailsScreenModel.handleSyncedChapters`)
- [ ] **Manga , removed-chapter download prompt.** Same as the novel case; honors the manga
  "Delete removed chapters" setting (always/keep/ask).
  (`MangaDetailsScreenModel.handleSyncedChapters` + `MangaDetailsDialog.ConfirmRemovedDownloads`)
