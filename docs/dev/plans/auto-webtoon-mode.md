# Auto webtoon mode

Developer-facing record of the automatic long-strip reading mode: how a series is classified, why it silently did nothing on a merged library for months, and what it does now. Ported from Komikku (which inherited it from TachiyomiSY, originally J2K's) in `f576d38ed`; root-caused and fixed 2026-08-21.

## Goal

Open a manhwa, manhua or webtoon in webtoon (long strip) reading mode without the user setting a mode on every series by hand, while a mode they picked themselves always wins.

## Why

A mixed library is the normal case here: paged manga and long-strip manhwa sit side by side, and one global default reading mode is wrong for half of it. The alternative is a per-series mode set by hand on hundreds of entries.

## Approach

Classification reads two signals and nothing else: an entry's **genre tags** and its **source's display name**. Never image dimensions, so it costs nothing at page-load time. The tag tokens work because the Madara and MangaThemesia extension themes scrape each site's own series-type field into the genre list, so several hundred sources emit "Manga" / "Manhwa" / "Manhua" as a genre directly.

`Manga.mangaType(sourceName)` returns a `MangaType`; `defaultReaderType(type)` maps only the three long-strip types to `ReadingMode.WEBTOON.flagValue` and everything else to null, meaning "leave the global default alone". The manga and comic branches run **before** manhua/manhwa on purpose: they suppress a long-strip guess when the site itself calls the entry a manga or a comic, which is what stops a mixed-content aggregator from webtooning everything.

The result is **computed per reader open and never stored**. Nothing writes `viewer_flags`, so the user's own choice remains the only thing persisted, and switching the preference off restores the previous behaviour exactly.

### The merge vote

The single most important property: **any member of a merge group calling the series long strip decides it**, via the `defaultReaderType(entries, sourceNameOf)` overload. Classifying only the opened entry is what broke the feature (see Status), because grouped sources describe one series differently and the tagged member is rarely the trunk source the reader opens from.

Edit-info overrides still apply, and only to the entry they belong to rather than to its siblings, since `CustomMangaInfo` is loaded for the opened manga id alone.

### Ordering constraint

`ReaderActivity` builds the viewer when `state.manga` becomes non-null, so the merge group must be resolved **before** that state update or the classifier sees no members. This is load-bearing and easy to undo by accident when editing `ReaderViewModel.init`.

## Key files

- `app/src/main/java/exh/util/MangaType.kt`: the classifier, the token lists, and the group vote overload.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`: `autoWebtoonMode()` (the single predicate feeding both the viewer and the toast), memoized on the member id set; the init ordering above.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`: the override notice, shown only when the picked mode differs from the global default.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt`: `eh_use_auto_webtoon`, default on.
- `app/src/test/java/exh/util/MangaTypeTest.kt`: 19 cases covering precedence and the group vote.

## Status

**Shipped and device-verified.** Originally shipped in `f576d38ed`, then found to be doing nothing on most of a real library and fixed in `d1ad3380e`.

**The failure, measured rather than estimated.** The reader is launched with `chapter.mangaId` (`MangaScreen`), which on a merged series is the member that owns the chapter, not the entry the user opened. Classification ran on that member. On a real 291-favourite library: **136 favourites classify as long strip on their own row, and 55 of them sat in a group whose lead member does not**, so the mode was never applied. Reproduced both ways on one series in one session: opening its Manganato-owned chapter gave the paged viewer, scoping to the MangaDex member (the only one tagged `Long Strip`) gave the webtoon viewer.

Spelling was ruled out as a cause. The whole library holds three spellings (`Webtoons`, `Webtoon`, `Webtoon/Webcomic`), all matched by a case-insensitive substring test.

**Verified after the fix** on two real groups with different trunk sources and different tags: `Long Strip` on a MangaDex sibling, `Manhwa` on two siblings of a Manga Demon trunk. Both moved from `DirectionalViewPager` to `WebtoonViewer`. Six new tests, verified by mutation: reverting the vote to the opened entry alone turns exactly the three group cases red.

**Not done:** nothing outstanding on the feature itself.

## Decisions & tradeoffs

- **Any member votes, rather than the trunk source only** (owner, 2026-08-21). The trunk-only rule is more predictable but misses the exact case that motivated the fix. Safe on real data: manga-tagged and long-strip-tagged rows are disjoint across all 1494 rows, so a sibling's "Manga" tag cannot currently cancel a correct vote. A test pins the case so a future library change surfaces it.
- **On by default** (owner, 2026-08-21), matching Komikku. It shipped off, which is why most users never saw it work. Nothing writes the key until the toggle is touched, so the new default reaches existing installs too, not just new ones.
- **Never persisted** (owner, 2026-08-21). Writing the pick into `viewer_flags` would make it visible and editable, at the cost of turning a computed guess into a stored user choice that survives the preference being switched off.
- **The override notice fires only on a real override.** For a reader whose global default is already webtoon the pick changes nothing, and announcing one was a lie on every long-strip series. Komikku has this defect on both of its call sites.
- **Manga only.** Webtoon is an image-layout mode with no analog in reflowed novel text, so the write-once rule's mechanism exit applies.

## Divergence from Komikku

The token lists are **byte-identical**: 55 `contains` predicates on each side, verified by extraction and diff. Only two commented-out lines and a dead localized display overload were left behind in the port.

Reikai is ahead in five ways, none of which Komikku has: the merge vote (Komikku has no merge system, so it cannot have this bug or its fix), one memoized predicate feeding both viewer and toast where Komikku recomputes at two independent sites that can disagree, Edit-info overrides in the classification path, the honest override notice, and 19 tests against none.

Related: [merge-aware manga reader](merge-aware-manga-reader.md) for the group resolution this depends on.
