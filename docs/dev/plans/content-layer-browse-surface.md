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
  the one-shot category dialog) lives once in the generic `EntryBulkFavoriteViewModel<T>`.
  `BulkFavoriteViewModel` (manga) and `NovelBulkFavoriteViewModel` are thin facades supplying
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
  default-category decision tree, used by both adders and the bulk-favorite engine. Declined while
  their premises hold: the sealed-dialog-type collapse (needs a generic carrier for payloads that
  differ by construction) and extracting `seedCategoriesFromGroup` (~8 identical lines against a
  category-port interface). **The polymorphic adder decline has expired**: it rested on no shared
  caller existing, and the recents engine is one, so the add sequence is being collapsed in
  [content-layer-add-flow.md](content-layer-add-flow.md).
- **Step 3, hide-in-library for novel browse (shipped `cadf22edb`).** The novel pager filters each
  fetched page against the live favorited keys behind the same preference manga reads
  (`hideInLibraryItems`), at the same load-time snapshot semantics. One mechanic manga gets from
  Paging 3 for free is hand-built here: a page that filters down to nothing must not stall the
  manual pager (the scroll trigger only re-arms when the visible list changes), so `loadMore` loops
  to the next page until something visible lands or the catalog ends, and a fully-hidden first page
  hands off to that loop.
- **Step 4, per-language switch for novel sources (shipped `09cb80e27`).** Each language heading in
  the novel sources filter is a switch, mirroring manga's; a disabled language hides its sources
  from the Sources list and global search. Stored as a deny-list (`disabledNovelLanguages`),
  deliberately inverted from manga's enabled-set: novel languages arrive with whatever plugins the
  user installs, so a deny-list keeps every language on by default and a newly appearing language
  visible without a migration. Grounded: the LNReader registry's `lang` field is first-class (a
  fixed 16-language list).
- **Step 5, delete-and-manifest the dead tab builders (shipped `df4d6e752`).** Mihon's
  `sourcesTab()` and `migrateSourceTab()` had no callers (the Reikai chip tabs replaced them);
  both files are deleted and manifested. `extensionsTab()` stays, it is wrapped live by
  `ReikaiExtensionsTab`.
- **Step 6, one owner for where a new favorite lands.** A behaviour-seam takeover of browse was
  scouted and ruled out (see the decision below); what the evidence supported instead was the
  add-to-library verb family, which spans browse, details and history. The default-category rule
  had nine statements: one tested kernel (`resolveDefaultCategoryIds`), three helpers reading it,
  and five inline copies. The novel details screen was the copy that got it wrong, favoriting and
  then prompting whenever any category existed, so a configured default novel category never
  applied there; it now calls `NovelLibraryAdder.applyDefaultCategoryOrPrompt` like its own history
  sibling. The four surviving copies (both details screens' add and add-to-group paths, both
  history screens') now read the kernel. Each keeps its own write ordering, because manga's two
  favorite first and abandon the add when that write fails, where the adder favorites after
  resolving categories; only the rule moved, not the sequence. The add-to-group pair was inventoried
  case for case against its novel twin at the same time, which turned up the `dateAdded` gap below.
  Both adders now carry the same seven cases, each mutation-verified.

## Key files

- Shared engine: `reikai/presentation/browse/EntryBulkFavoriteViewModel.kt`, facades in
  `reikai/presentation/browse/BulkFavoriteViewModel.kt` and
  `reikai/presentation/novel/browse/NovelBulkFavoriteViewModel.kt`.
- Adders: `reikai/presentation/browse/MangaLibraryAdder.kt`,
  `reikai/presentation/novel/browse/NovelLibraryAdder.kt`.
- Hosts: `eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt`,
  `reikai/presentation/novel/browse/NovelBrowseScreen.kt`, both global-search screens, and
  `exh/md/follows/MangaDexFollowsScreen.kt`.

## Status

Shipped in full on `feat/0.4.0`: step 1 `1392f58c9`, step 2 `66dd5d007`, step 3 `cadf22edb`,
step 4 `09cb80e27`, step 5 `df4d6e752`. Steps 1 to 5 are Fold-verified on both content types (the
add / remove round trips, the hide-in-library toggle, the language switch). Step 6 closes the
surface; the remaining content-layer surfaces are history and updates, then downloads, then the
reader.

## Decisions & tradeoffs

- **No takeover, no reopened parks.** The 2c body/toolbar shell and the generic search orchestrator
  stay declined; pagination (Paging 3 vs the manual probe pager), the filter dispatch (typed
  `FilterList` vs plugin JSON schema), `SearchViewModel` and everything under `migrate/` are out
  of scope. `SearchViewModel` belongs to the migrate/global-search surface.
- **The behaviour-seam takeover is ruled out while the two pagers stay apart; the adder half of
  that ruling has expired (2026-08-09).** A later plan proposed redoing browse the way migrate,
  details and library were
  redone, on the grounds that upstream churn is near zero (measured: five commits in twelve months
  across the eleven upstream browse paths, one behavioural, all already below the synced base). Low
  churn makes a takeover affordable, not necessary, and the two engines diverge exactly where a
  takeover has to bite: manga paginates through Paging 3 with a typed `FilterList`, novels through
  a hand-rolled probe pager with JSON-schema filters, both source-API-driven. Reimplementing either
  is what the spine rule forbids, and that half stands. The adder half does not. It declined a
  polymorphic adder because no shared caller existed and the takeover was the only thing that would
  have created one; the recents engine has since become one, so the decline expired with its premise
  and the sequence is collapsed in [content-layer-add-flow.md](content-layer-add-flow.md). What the
  two adders share already lives in `resolveDefaultCategoryIds` and `EntryBulkFavoriteViewModel`;
  what stays per-type is the carrier, since the manga adder returns a neutral result for its caller
  to render while the novel one owns the dialogs, and a browsed novel has no library row to favorite
  until it is materialized.
- **Parity gap closed by levelling manga up (owner-ruled).** The novel adder skips the favorite
  write when adding an already-favorited row to a group, so a grouping change does not reset
  `dateAdded` and move the entry in a date-added sort; `UpdateManga.awaitUpdateFavorite` stamps
  `dateAdded` on every write, and the manga adder had no such skip. Manga now does, and reads the
  row through `GetManga` rather than the `Manga` the caller hands it: skipping the write on a stale
  "already favorited" snapshot would merge an unfavorited entry, which is the invisible-member
  failure the atomic pair exists to prevent. The read is safe on every caller path, because browse
  and global search both put their results through `NetworkToLocalManga` before a dialog can reach
  them, and details and history operate on library rows. Nothing changes today, since no manga path
  reaches `addToGroup` with a favorited row; the value is that the two adders are now case-for-case
  twins with matching tests.
- **Parity rulings (owner, 2026-08-02): level up what the plugin format supports, gate the rest.**
  Hide-in-library and enabled-languages level up (both genuinely supportable). NSFW flagging is
  gated: the LNReader plugin format carries no nsfw field anywhere, so novel lewd filtering stays
  genre-tag-only.
- **The two installed-source loaders stay separate, pinned by a named mechanism (2026-08-16).**
  `ExtensionManager` (installed APKs, `PackageManager`, signature trust, dex class loading) and
  `LnPluginInstaller` (URLs, network download, a repo vouching for each, JS eval in the QuickJS
  host) share no stage of their mechanism, so a shared shell parameterized by two "config helpers"
  would be a bag of lambdas doing the entire job. `ExtensionManager` is also an engine file on the
  upstream sync path, which the ownership rule keeps minimally patched. What the two genuinely share
  is three sentences: the load never blocks startup, a reload cannot be overwritten by an in-flight
  load, and a failure is retried on a later trigger rather than needing a restart. The middle one is
  pinned by each side holding its full scan behind a `Mutex` (`ExtensionManager.loadMutex`,
  `LnPluginInstaller.loadMutex`), each naming the other. A conformance test is declined: pinning it
  would mean inventing seams for a `Context` and `PackageManager` on one side and the network plus
  the JS host on the other, to assert what `Mutex` already guarantees per side. Manga needed the
  mutex only once upstream moved its initial scan off the main thread
  (mihonapp/mihon#3788), which let a slow startup scan land after a re-trust and undo it.
- **Re-trusting is driven by the store list rather than by callers (2026-08-16).** Trust is judged
  against the signing keys a scan reads as it starts, so `ExtensionManager` collects
  `GetExtensionStores.subscribe()`, maps it to that key set and re-scans whenever the set changes,
  dropping the first emission (the list the startup scan already saw). Both explicit callers, the
  repo-add screen and the backup restorer, are gone with it, so a repo arriving from any path,
  including one nobody has wired yet, re-trusts on its own. The manual "Re-check extensions" lever
  stays for what that flow cannot see: a scan that failed transiently, or a package change the
  install receiver missed. Novels have no counterpart to build, because a plugin's trust is a repo
  vouching for its URL, checked at load time rather than cached from a signature.
- **The All chip re-wires the manga row's clicks, so it owes Mihon's row dialogs with them.**
  Mihon's extension list owns its own scroll container, so the unified list cannot nest it and
  hand-wires the same click lambdas over a shared `ExtensionItem`. It shipped without the trust
  prompt and the private-uninstall confirmation, and the trust half did nothing observable at all:
  an untrusted row was routed to the extension details screen, which resolves only
  `installedExtensionsFlow` and so pops straight back off a package that is untrusted rather than
  installed. Both dialogs are now hosted by the unified list, with Mihon's two composables made
  public rather than copied. Novels need no counterpart, for the reason the ruling above gives.
- **The ROADMAP browse feature items ride after the collapse** (genre-tap-search, source-row
  polish, find-a-source search), on the shared parts, rather than landing inside this surface.
