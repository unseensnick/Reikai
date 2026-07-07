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
The cover dialog shipped next (`EntryCoverDialog`, replacing the two near-identical copies). The
details screen (the large, highest-impact surface) is next. Reader stays separate.

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

**In progress. List surfaces + cover dialog shipped; the details screen is collapsing in phases (P1-P3
done, P4-P6 remain).** Captured from a design discussion (2026-07-05) while finishing the MD
enhanced-source port; a code-research pass (2026-07-07) confirmed the list surfaces already carried the
merge half of the seam, so only the leaf row remained there.

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

Remaining on the details screen: a shared `EntryToolbar` (fills the shell's `topBar` slot; smaller now the
shell exists), manga hide/unhide-chapters (pref-based, mirroring novels), and a shared Komikku-style
edit-info editor plus the manga override data layer (`CustomMangaInfo`). The larger standalone parity items
are ROADMAP entries. A 2026-07-07 parity audit ruled the manga/novel divergences (close vs gate).

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
