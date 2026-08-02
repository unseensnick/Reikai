# Content layer: the browse surface

## Goal

Collapse the remaining manga/novel behavior twins on the browse surface (bulk add-to-library, the
library-adder contract and its dialogs) and close two ruled parity gaps (hide-in-library, source
languages), so a browse behavior change is written once and reaches both content types.

## Why

A 2026-08-02 deep research pass over both browse stacks found the UI seam largely done (the grid
cell, search cards, source-options dialog and selection toolbar are shared) and the shell collapses
deliberately declined (see [content-parity-drift-and-collapse.md](content-parity-drift-and-collapse.md)
2c and Phase 3, both marked do-not-re-flag). What remained forked was behavior: the two
bulk-favorite ScreenModels were line-for-line twins, the two library adders expose the same
favorite/duplicate/categories flow with per-type signatures, and their Remove dialogs are unshared
twins. The spine rule of [content-layer-architecture.md](content-layer-architecture.md) holds
unamended here: no library-style takeover, verbs stay in per-type code, Mihon files stay live.

## Approach

Five steps, each independently shippable and device-verified before the next.

- **Step 1, the shared bulk-favorite engine (shipped `1392f58c9`).** The whole selection and
  category state machine (toggle / select-all / invert, the default-category-or-prompt decision,
  the one-shot category dialog) lives once in the generic `EntryBulkFavoriteScreenModel<T>`.
  `BulkFavoriteScreenModel` (manga) and `NovelBulkFavoriteScreenModel` are thin facades supplying
  the selection key, the category source, the default-category preference and the add verb, so
  every call site keeps its current types. A sealed cross-type selection key was considered and
  dropped: no browse screen shows a mixed list, and the generic keeps Mihon's `selection:
  List<Manga>` parameters untouched.
- **Step 2, the shared remove dialog + default-category kernel (shipped `66dd5d007`).** The
  scouted "one neutral adder contract" narrowed on inspection: the two adders are not twins (the
  manga one returns neutral results and leaves orchestration to its callers, the novel one owns the
  long-press orchestration and returns dialogs; the carriers differ because a `NovelItem` has no
  row until insert). What was genuinely twinned now lives once: `EntryRemoveDialog` replaces
  Mihon's `RemoveMangaDialog` (deleted and manifested) and the novel twin across all five hosting
  screens, and `resolveDefaultCategoryIds` (`reikai/domain/category/`) is the one
  default-category decision tree, used by both adders and the bulk-favorite engine. Assessed and
  declined, do not re-flag: a polymorphic adder interface (no shared caller exists, so it would be
  ceremony), the sealed-dialog-type collapse (needs a generic carrier for payloads that differ by
  construction), and extracting `seedCategoriesFromGroup` (~8 identical lines against a
  category-port interface).
- **Step 3, hide-in-library for novel browse.** The novel pager filters fetched items against the
  already-held favorited keys behind the same preference manga reads (`hideInLibraryItems`).
- **Step 4, enabled-languages for novel sources.** A novel enabled-languages preference filtering
  the source list and global search, mirroring manga's, seeded all-on so nothing disappears on
  update. Grounded: the LNReader registry's `lang` field is first-class (a fixed 16-language list).
- **Step 5, delete-and-manifest the dead tab builders.** Mihon's `sourcesTab()` and
  `migrateSourceTab()` have no callers (the Reikai chip tabs replaced them; `extensionsTab()` stays,
  it is wrapped live by `ReikaiExtensionsTab`).

## Key files

- Shared engine: `reikai/presentation/browse/EntryBulkFavoriteScreenModel.kt`, facades in
  `reikai/presentation/browse/BulkFavoriteScreenModel.kt` and
  `reikai/presentation/novel/browse/NovelBulkFavoriteScreenModel.kt`.
- Adders: `reikai/presentation/browse/MangaLibraryAdder.kt`,
  `reikai/presentation/novel/browse/NovelLibraryAdder.kt`.
- Hosts: `eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt`,
  `reikai/presentation/novel/browse/NovelBrowseScreen.kt`, both global-search screens, and
  `exh/md/follows/MangaDexFollowsScreen.kt`.

## Status

In progress on `feat/0.4.0`. Step 1 shipped (`1392f58c9`, Fold-verified both types: select, invert,
category prompt, add, remove). Step 2 shipped (`66dd5d007`, Fold-verified: the add-then-remove
round trip on both content types). Steps 3 through 5 ahead.

## Decisions & tradeoffs

- **No takeover, no reopened parks.** The 2c body/toolbar shell and the generic search orchestrator
  stay declined; pagination (Paging 3 vs the manual probe pager), the filter dispatch (typed
  `FilterList` vs plugin JSON schema), `SearchScreenModel` and everything under `migrate/` are out
  of scope. `SearchScreenModel` belongs to the migrate/global-search surface.
- **Parity rulings (owner, 2026-08-02): level up what the plugin format supports, gate the rest.**
  Hide-in-library and enabled-languages level up (both genuinely supportable). NSFW flagging is
  gated: the LNReader plugin format carries no nsfw field anywhere, so novel lewd filtering stays
  genre-tag-only.
- **The ROADMAP browse feature items ride after the collapse** (genre-tap-search, source-row
  polish, find-a-source search), on the shared parts, rather than landing inside this surface.
