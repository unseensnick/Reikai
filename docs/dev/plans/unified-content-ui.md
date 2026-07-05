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

**Sequencing (screen-by-screen, never big-bang):** cover dialog (small, shared) → details
screen (highest impact) → browse → library grid → migrate / global-search. Reader stays
separate. Prove the pattern on the small surfaces first.

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

**Planned, not started.** Captured from a design discussion (2026-07-05) while finishing the
MD enhanced-source port. Next step when picked up: a `/scout` over the manga↔novel
duplication to map every shared-vs-divergent surface and draft the `EntryDetailsUiState` seam +
mapper contract, then a per-screen execution plan. Best done right after an upstream sync, since
the mappers depend on current State shapes.

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
