# Unified content UI (manga + novels + adult)

## Goal

Collapse the three near-duplicate presentation stacks (Mihon manga, Reikai novels, Komikku
adult) into one Reikai-owned UI layer, so each browsing / details / library surface is built and
designed once and serves every content type. Two payoffs at once: kill the manga↔novel shadow
duplication, and get a single place to move the app off stock Material 3 onto a Reikai design.

## Why

- **Duplication.** The light-novel port copy-adapted Mihon's manga screens, producing a parallel
  `reikai/presentation/novel/` tree that mirrors `eu/kanade/presentation/manga/`:
  `NovelScreen`↔`MangaScreen`, `NovelInfoBox`↔`MangaInfoHeader`, `NovelToolbar`↔`MangaToolbar`,
  `NovelCoverDialog`↔`MangaCoverDialog`, `NovelNotesScreen`↔`MangaNotesScreen`, plus parallel
  `browse/`, `migrate/`, `globalsearch/` folders. Every design tweak or upstream UI change has to
  be applied twice.
- **Adult is not a third stack.** Adult manga is Mihon's `Manga` model from adult sources plus
  gallery metadata (`GalleryInfoBox`, `NamespaceTags`), so it already shares the manga screens;
  its only extra is a metadata-display slot.
- **Design.** The app reads as stock M3. Owning the pixels once lets us modernize everything at
  once instead of restyling three stacks.
- **Sync.** A Reikai-owned pixel layer removes UI divergence with upstream entirely (net-new
  files never conflict), while the engine keeps tracking Mihon.

## Approach

**Core principle: unify at a UI-model seam, not at the domain model.**

- Keep the three data / logic stacks as-is: Mihon's `Manga` model + ScreenModel (manga and
  adult), Reikai's `Novel` model + `NovelScreenModel`. Do **not** merge domain models into one
  `Entry` type; that re-types Mihon's whole manga stack (the fork-everything swamp) and severs
  upstream engine flow.
- Define Reikai-owned, content-agnostic UI models (`EntryDetailsUiState`, `EntryListItem`,
  `EntryCover`) carrying only what the pixels draw: title, cover, badges, description, chips,
  progress.
- One Reikai composable per surface renders that neutral model (`EntryDetailsScreen`,
  `EntryLibraryGrid`, `EntryBrowseScreen`, `EntryCoverDialog`, `EntryNotesScreen`) in
  `reikai.presentation.*`.
- Each ScreenModel **maps** its domain model into the shared UI model (`Manga → EntryDetailsUiState`,
  `Novel → EntryDetailsUiState`). Adult rides the manga mapper plus a metadata slot.
- **Content-specific bits are slots, not forks:** manga page previews; novel TTS entry and no
  scanlator filter; adult namespace chips. The shared composable exposes optional slots and each
  mapper fills what applies, keeping the UI model from rotting into nullable soup.

**Proof the pattern already works:** `MangaNotesTextArea` takes a plain `notes: String` and is
shared by both `MangaNotesScreen` and `NovelNotesScreen` (fenced `// RK`). Scale that to
details / browse / cover and the ~30 shadow files collapse into mappers plus one shared UI.

**What stays separate (deliberately not unified): the readers.** The manga reader (image
pager / webtoon, View-based) and the novel reader (`NovelReader*`, HTML / WebView / TTS) are
different machines; one abstraction over both costs more than it saves.

**Wiring and the sync story.** The shared composable is net-new `reikai.*`, so it never conflicts
with upstream. Mihon's own manga screens become unused-but-retained, routed around with a `// RK`
redirect in the Voyager `Screen.Content()`; upstream updates to them land verbatim and inert
(mark, don't delete). The only remaining upstream coupling is the **mapper** (`Manga.State →
EntryUiState`), which sits at the state seam and is compile-caught: a renamed upstream field
fails to compile in the mapper, never a pixel hunt.

**Complementary theme layer.** A Reikai theme (custom `ColorScheme` / typography / shapes /
component defaults through the single `TachiyomiTheme` → `MaterialExpressiveTheme` entry point)
reskins color / type / shape globally; this initiative handles structure / layout. Do both for
the full redesign; seed the tokens in `DESIGN.md` first (brand in `PRODUCT.md`: quiet, dense,
deliberate), via the `impeccable` skill.

**Sequencing (screen-by-screen, never big-bang):** the list surfaces went first. History and
Updates each merge the manga and novel feeds into a shared row model (`HistoryRow` / `UpdateRow`)
and route around Mihon's screen via a `// RK` redirect in the tab (built during the light-novel
port), so only the leaf row needed unifying; both shipped (`EntryHistoryRow`, `EntryUpdatesRow`).
The cover dialog shipped next (`EntryCoverDialog`, replacing the two near-identical copies), then
the details screen (the large, highest-impact surface) in phases P1-P6. The download subsystem
(Road B) is the next surface. Reader stays separate.

## Key files

- Duplication to collapse: `eu/kanade/presentation/manga/` (Mihon manga screens) vs
  `reikai/presentation/novel/` (the mirror). Adult metadata components:
  `manga/components/GalleryInfoBox`, `manga/components/NamespaceTags`.
- Existing proof of the seam: `manga/components/MangaNotesTextArea` (shared notes editor) +
  `reikai/presentation/novel/notes/NovelNotesScreen`.
- Theme entry point (complementary): `eu/kanade/presentation/theme/TachiyomiTheme` (single
  `MaterialExpressiveTheme`, already carries a `// RK` cover-based-theming island).
- Design tokens to seed first: `PRODUCT.md`, `DESIGN.md`.

## Status

**In progress. List surfaces + cover dialog shipped; the details screen is collapsing in phases (P1-P5
done; P6 editor + custom-info overlay + Fill-from-tracker shipped).** Captured from a design discussion (2026-07-05) while finishing the MD
enhanced-source port; a code-research pass (2026-07-07) confirmed the list surfaces already carried the
merge half of the seam, so only the leaf row remained there. P6 was deep-researched (2026-07-08) to
ground the manga custom-info data layer before implementation.

Shipped (list + cover): the History and Updates screens each collapsed their twin manga/novel row
composables into one shared `Entry*Row` fed by per-type mappers/slots (`EntryHistoryRow` `0628f5f43`;
`EntryUpdatesRow` plus the grouped-row cover-tap `43fff525e`), then the full-cover dialog into one shared
`EntryCoverDialog` (`78e8d8825`).

Shipped (details screen, in phases): the shared action row (`EntryActionRow` `9b7e4bea3`), info header
(`EntryInfoBox` + `EntryHeaderUi` + `Manga`/`Novel` mappers `7cc5197e0`), and the phone + tablet screen
shell (`EntryDetailsScaffold` `39d5e0c52`, `EntryDetailsTwoPaneScaffold` `4d9f07ae2`), all under
`reikai/presentation/details/` and driven by a neutral `EntryDetailsUiState` + `entryInfoItems` builder.
Both Voyager screens' impls now delegate to the shells; the per-type toolbar, selection bar, content
cards (gallery/previews/related/page-bar/merge-chips), and chapter emitter stay slots. Parity closes rode
along: novel long-press-categories, novel contextual selection gating (`8f733b2ca`), novel fast-scroller,
and a pre-existing merged per-source metadata-viewer fix (`05e86c70a`). Note the Phase 3 reshuffle: a
standalone "column only" step was low-value and forced awkward `stringResource` hoisting, so the shell was
pulled forward into P3 and the toolbar left as a slot. All verified on-device (debug and minified).

Also shipped (details screen): the shared `EntryToolbar` (`57bbd8055`, P4, fills the shell's `topBar` slot)
and manga hide/unhide-chapters (`8f7014353`, P5, pref-based mirroring novels, hidden excluded from list +
resume + download on both types). A 2026-07-07 parity audit ruled the manga/novel divergences (close vs
gate); several fixes rode along (novel toggle lingering, novel hidden-chapter download/resume leak,
settings-search crash, cross-tracker merge, merged-view dedup).

**P6, the shared edit-info editor + a non-destructive custom-info overlay for BOTH types.** Design LOCKED
(Option B), deep-researched (2026-07-08).

**The editor is one shared dialog, and its form is native XML (the one sanctioned exception to the
Compose-native rule).** After a pure-Compose form could not keep the soft keyboard stable on Android 15+
edge-to-edge (blink on every field switch + an inset gap / field hidden behind the keyboard) and a
full-screen-Screen host still showed the gap, the editor was rebuilt as Komikku's shape: a Compose Material3
`AlertDialog` (floats over the details page) hosting a native `edit_entry_info.xml` form via `AndroidView`
(`EntryEditInfoDialog`). Native `EditText` fixes the keyboard. Fields: cover preview, exposed-dropdown status,
Title/Author/Artist/Cover-URL/Description, an inline Add-tag field feeding removable chips, a Fill-from-tracker
button, and three resets (Reset info: non-tag fields to source, keeping tags; Reset tags: chips to source;
Reset all: clear every override). Shipped (manga: `9bb98572d` + `368165f78`; novel wired to the same dialog: `f33dc83e1`).

**Storage = a non-destructive overlay table per type**, applied as a display-layer overlay via a reactive
Flow combine in the details ScreenModel (NOT in a mapper, NOT a synchronous store), favorites only. The
source row is never written, so Reset cleanly restores the source (even offline). A pure `withCustomInfo`
helper produces a display-only entry; the raw entry stays source-accurate for tracker search, refresh,
duplicate detection, download folder names, and merge (manga reuses the `mergeDisplayManga` display path).
- Manga: `custom_manga_info` (migration 27), `CustomMangaInfo` + `Manga.withCustomInfo`, `Get/SetCustomMangaInfo`.
- Novels: `custom_novel_info` (migration 28), `CustomNovelInfo` + `Novel.withCustomInfo`, `Get/SetCustomNovelInfo`.
  Novels were REFACTORED off their old destructive in-row model (`editedFlags` lock bits + `mergeRefreshedNovel`):
  those are retired, `28.sqm` one-time-migrates existing edits into the overlay (per lock bit) and drops the
  `edited_flags` column, and a refresh now writes source straight into the row. `BackupNovel.editedFlags`
  (proto 18) is kept as an inert reserved slot for backup round-trip compat.
- The SQLDelight driver is async-only (`generateAsync`), which is why a synchronous mapper overlay was ruled
  out; the Flow combine mirrors how the details ScreenModels already read. A cover-URL override re-renders on
  its own (the URL is in Coil's key), except while a local custom-cover file is set (that file wins).

**Overlay reach = details + library + updates + history, DISPLAY-ONLY, for BOTH types.** A custom
title/author/cover changes only what is shown; auto-merge, dynamic grouping, A-Z sort, status filter, and
search all keep using the RAW source values (applied at the last display-map step, keyed by real id, after
collapse/sort; the manga library overlays the per-category display read, the novel library overlays only the
rendered representative). This matches manga's original auto-merge-on-raw-title choice and prevents a rename
silently reshuffling or unmerging the library. Documented in [FAQ.md](../../FAQ.md) to pre-empt bug reports.

Backup: each override table gets its own section (`backupCustomMangaInfo` 713, `backupCustomNovelInfo` 714),
re-keyed by url+source on restore.

**Shipped: Fill-from-tracker** (the last P6 piece). The editor's "Fill from tracker" button pulls
title/author/artist/cover/description **and genres** from a bound tracker, for both manga and novels; a
`TrackerSelectDialog` (ported from Komikku) picks when more than one is bound. Grounded by a deep-research
pass (2026-07-08) that traced Komikku's API and verified every tracker against its live API.

- **New spine:** `TrackMangaMetadata` (`data/track/model/`, plus a Reikai-added `genres` field) and a
  `Tracker.getMangaMetadata(track)` with a throwing `BaseTracker` default; the whole API is a Komikku
  addition absent from Mihon. Candidates come from `GetTracks` (manga) / `GetNovelTracks.awaitGroup`
  (novel, merge-group-aware, mirrors `RefreshNovelTracks`), resolved via `TrackerManager`, minus
  `EnhancedTracker`s (the self-hosted trio, which can't autofill). Wired ungated (metadata is public), so a
  logged-out but bound tracker still fills where the API allows.
- **8 trackers, not Komikku's 6.** AniList, MyAnimeList, Shikimori, Bangumi, MangaUpdates, MdList are
  Komikku-derived (MangaUpdates needed a `getSeries` + `MUAuthor`/`MUGenre` backport; Bangumi a polymorphic
  `Infobox` parser with corrected `作者`/`插画` keys; Shikimori kept Reikai's `shikimori.io`, not Komikku's dead
  `.one`). **Kitsu is bespoke:** Komikku's `findLibraryEntryById` GraphQL is now 403-gated, so it runs on the
  JSON:API `api/edge/` REST the tracker already uses, reading genres from `categories` (Kitsu's `genres`
  relationship is usually empty). **MdList** reuses the enhanced MangaDex source's own `getMangaUpdate` parse
  pipeline. **Hikka** has no Komikku reference (Komikku lacks Hikka); its DTO was extended to the live
  `MangaInfoResponse` (synopsis/authors/genres), with markdown-link stripping on the synopsis.
- **Level-up over Komikku:** genres are filled everywhere the tracker exposes a clean list (Komikku fetches
  none), merged append-distinct into the chips so autofill never wipes curated tags; Bangumi is the only
  tracker with no usable genre field.
- **Genres decision:** genres-only, skip the noisy tag/category dumps (AniList `tags` 66 w/ spoiler+adult
  flags, MangaUpdates `categories` 262 vote-noise). MangaDex uses the source's genre string as-is.
- **Tests:** the two fragile parsers get pure DTO tests (`BGMSubjectInfoboxTest` for the polymorphic infobox,
  `HKMangaMetadataTest` for Hikka's role/genre split); the rest are HTTP-fused like all existing tracker code
  and verified on-device against live APIs (Fold, all 8 trackers).

**Post-P6 parity closes (shipped in 0.3.0).** A deeper full-surface manga/novel parity re-audit (2026-07-09,
six research agents + inline grounding, superseding the 2026-07-07 surface pass; record kept locally per
the audits convention) drives a phased program to close the gaps the surface collapse left in the still-twin
areas (browse, migration, the library settings sheet, per-type action handlers, the reader). SHIPPED on the
branch: the silent-bug sweep (novel migration flags, failed-download notification, Updates delete-confirm,
browse paging retry, sources Filter + Last-used); novel library tracker filter/sort/group + novel-browse bulk
multi-select; per-API novel tracker search (`searchNovel` for Shikimori/Hikka/MangaBaka; Bangumi + MdList
gated); the **reader-chrome unification** (shared top/bottom bars, vertical rail, bar animation/scrim, and
configurable novel bottom-bar buttons, so a reader-chrome change reaches both readers); the **settings
content-type reorg** (manga/novel options grouped + labeled per type across Reader / Downloads / Library /
Advanced via a shared `contentTypedCategory` helper + group-aware search) — this REPLACED the earlier plan for
a dedicated novel reader-defaults settings page, which tested worse on the Fold; and **reader 7b** (an
always-on reading percentage, volume-key navigation, and a manga long-strip volume scroll-amount slider).
Those three reader/settings blocks (chrome unification, settings reorg, 7b reader features) are what the
ROADMAP and `Handoff.md` track as **Phase 7** (7a = the settings reorg, which reversed the dedicated-page plan;
7b = the reader sub-features). The rest of the program shipped too: the manga reader skip-hidden fix, an
independent library scroll position per content type, the novel download-storage re-key
([novel-download-storage.md](novel-download-storage.md)), and the `[S]` Novels quick wins. The round-2 drift
fixes + twin collapses that followed are recorded in
[content-parity-drift-and-collapse.md](content-parity-drift-and-collapse.md).

**Remaining: the download subsystem unification (Road B)**, collapsing the parallel novel download
cache/provider into one shared disk-scan layer serving both types. Queued under ROADMAP "Next".

**A separate reader track (evaluated, deferred).** [novel-reader-tsundoku.md](novel-reader-tsundoku.md)
captures tsundoku (a maintained, Apache-2.0 Mihon-fork novel reader) as the reader's forward direction:
seamless chapter transitions ported onto the current reader (Option 1), and later, on its own branch, a
migration to tsundoku's native reader folded into `ReaderActivity` (Option 3). Both are queued AFTER the
remaining parity phases above, not ahead of them.

## Decisions & tradeoffs

- **Unify at the UI-model seam, not the domain model:** preserves upstream engine flow and
  avoids re-typing Mihon's manga stack.
- **Divergent bits are slots, not forks or nullable fields:** if a field only one content type
  ever sets, prefer a slot composable.
- **Readers excluded:** too different to share; a shared chrome layer is tracked separately in
  [unified-reader.md](unified-reader.md).
- **A deliberate multi-screen initiative,** not incidental cleanup, so it is exempt from the
  "no standalone refactor sprints" rule, but it must be planned and sequenced, not big-banged.
- **Complementary to, not a replacement for, the theme-layer reskin.**
