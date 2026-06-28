# Manga merge engine (P4)

Fold same-title manga from different sources into one library card with a unified, deduplicated chapter list, manage merges by hand, rank a preferred source, and mirror tracker links across the group, plus the FlareSolverr escape hatch for Cloudflare-gated sources that shipped alongside it.

This is the developer-facing record for P4. The user-facing feature docs are separate: see [multi-source.md](../../multi-source.md) (grouping, source-switcher, manage sources), [tracker-sync.md](../../tracker-sync.md) (cross-source tracker mirroring), and [flaresolverr.md](../../flaresolverr.md) (the Cloudflare proxy setup). This doc does not repeat those; it explains how the engine works and why it is built this way.

## Goal

Give the library one card per real series even when the same manga is favorited from several sources, and make that card behave like a single manga: one merged chapter list, read/bookmark state shared across sources, one tracker binding that follows the whole group. Let the user merge or split groups manually, and pick which source "wins" when sources disagree. Ship a Cloudflare workaround (FlareSolverr) so sources behind strict bot-protection tiers stay usable.

## Why

Popular manga are listed on many sources. Without grouping the library shows the same title three or four times, each a separate card with its own partial chapter list and its own tracker. P4 makes those duplicates read as one entry: you see the union of chapters across all sources, marking a chapter read on one source marks it read on the others, and a tracker bound on any source advances for the whole group. The preferred-source ranking lets you bias the merge toward a source you trust (better scans, faster releases) without hand-managing every series.

FlareSolverr is unrelated to merging but shipped in the same phase because both were Reikai features ported forward from the Yokai-era code in one P4 pass. It is the escape hatch for sources behind Cloudflare tiers that Mihon's built-in WebView challenge solver cannot clear: the app hands the request to a self-hosted FlareSolverr instance, which uses a real headless browser to pass the challenge and returns the cookies.

## Approach

### How the pref-based merge works

A merge group is not a database row. It is computed on the fly from two preference sets plus an optional auto-rule:

- **Manual merges** (`mangaManualMerges`): a set of strings, each a comma-joined sorted list of manga ids that the user merged together (e.g. `"12,87,140"`).
- **Manual unmerges** (`mangaManualUnmerges`): a set of normalized `"min,max"` id pairs the user explicitly split apart.
- **Auto same-title** (`autoMergeSameTitle`): a toggle. When on, any two favorited manga with the same case-insensitive title are auto-grouped, unless an unmerge pair says otherwise.

So a manga's group is: the manual-merge members it appears in, plus same-title favorites (if auto is on), minus any pair explicitly unmerged. Storing groups as preferences (not a join table) means the whole feature is just set math over strings: no schema and no migration. The id-based group prefs are serialized as stable `{url, source}` refs for backup and rebuilt on restore (`BackupMangaMerge`, `MangaRestorer.restoreMerges`), since the local ids change on a fresh install.

### Merge-group algebra

The pure set math lives in [`MergeGroupAlgebra`](../../../app/src/main/java/reikai/domain/MergeGroupAlgebra.kt), shared by the manga and novel sides so neither carries domain types (it operates only on `Long` ids and the `Set<String>` pref encodings, making it fully unit-testable). The operations:

- `computeGroupIds(targetId, merges, sameTitleIds, unmerges)`: the group for one manga. Unions its manual-merge members with same-title ids, drops any pair unmerged from the target, always includes the target itself.
- `computeMerge(ids, ...)`: merge a list of ids into one group. Absorbs any existing overlapping entry (transitive merge into one clean sorted entry) and drops every pairwise unmerge between the members, so they collapse even with different titles or auto-merge off. Returns null for fewer than 2 distinct ids.
- `computeSplit(relatedIds, targetIds, ...)`: split some members out while keeping the survivors grouped. Records each removed id as unmerged from every other member.
- `computeDissolve(group, ...)`: fully separate every member of a group, recording an unmerge pair for each combination so nothing (manual or same-title) regroups them. This backs the library "Unmerge" bulk action and the "split all sources" case.
- `parseMergeKeys` / `parseUnmergedPairs` / `splitByUnmergedPairs`: the collapse parsers (see below).

The manga-specific manager [`MangaMergeManager`](../../../app/src/main/java/reikai/domain/manga/MangaMergeManager.kt) wraps these with the manga concerns: reading/writing the prefs, resolving same-title favorites, and a **tracker-based healing pass** (`computeHealing`, kept as a pure companion function). Healing walks every merge entry containing the target and drops a sibling only if both sides are tracked and their tracker keys (trackerId + remoteId) disagree, on the theory that two genuinely different series wrongly grouped will have conflicting tracker links. It records the resulting unmerges and surfaces a count so the screen can show a one-shot cleanup snackbar.

### Collapse parsers (one card per group)

The library does not merge in SQL. It collapses *after* loading favorites, at the grouping layer. The collapse parsers turn the pref sets into bucketing instructions:

- `parseMergeKeys(merges)` returns a map of member id to a canonical group key (the sorted comma-joined ids), so favorites sharing a merge group bucket together.
- `parseUnmergedPairs(unmerges)` returns the normalized pairs.
- `splitByUnmergedPairs(bucket, pairs, id)` is a greedy first-fit split that breaks a same-title bucket back apart wherever an unmerge pair forbids two members from sharing a card.

[`MangaMergeCollapse`](../../../app/src/main/java/reikai/presentation/library/MangaMergeCollapse.kt) uses these to produce one display card per group, picking a representative (the primary source) and rolling up the badge count, the summed unread, and the group-max "last read". The collapse runs per-category, so a manga in two categories shows once in each.

### Preferred-source ranking

A global ordered list (`preferredMangaSources`) lets the user mark sources as preferred, highest priority first. The aggregator reads it as `preferredSourceIds`: a source on the list wins the trunk regardless of chapter count; unranked sources fall back to the distinct-count rule among themselves. With the list empty the ranking is a no-op (every source ranks `MAX_VALUE`), so default behavior is unchanged. The settings UI lives under `reikai.presentation.library.preferredsources` (a Voyager Screen + ScreenModel + Content). Ranking is global, not per-manga, by design.

### Cross-source chapter pooling

[`ChapterAggregation.aggregate`](../../../app/src/main/java/reikai/domain/manga/ChapterAggregation.kt) stitches each sibling source's chapters into one unified list. It is pure and stateless (unit-tested in isolation):

1. **Pick the trunk** = the source with the most *distinct recognized* chapter numbers, after the preferred-source rank. Counting distinct numbers (not raw rows) stops a scanlator-heavy source from winning the trunk just because it lists each chapter many times. This is the Comick case: many duplicate rows, fewer real numbers.
2. **Gap-fill:** every recognized number the trunk lacks is borrowed from the next source (in rank order) that has it.

The result holds one row per recognized chapter number across the whole group: a source's own scanlator variants collapse to one, and a number an earlier source already supplied is not repeated. The trunk's unrecognized-number chapters (number < 0) are kept; siblings' unrecognized chapters are dropped to avoid unmatchable duplicates. Each returned chapter keeps its own `mangaId`, so the reader and downloader resolve each chapter to its origin source.

**The dedup key is the chapter number narrowed to `Float`.** The source API stores `chapter_number` as a 32-bit float, so a source that *reports* a number hands back e.g. `1.1f` (about 1.10000002384), while a source that does not falls through to chapter-number recognition (which parses in `Double`) and yields exact `1.1`. Those differ by about 2.4e-8, so an exact-double key would leave the same logical chapter duplicated across sources. Narrowing both to `Float` snaps them onto one grid while keeping real sub-chapters (`x.005`, `x.1`) distinct, since their spacing is far wider than a float ULP at realistic chapter magnitudes.

### Read/bookmark and tracker propagation

When the unified list is active, marking a chapter read or bookmarked propagates to every sibling row sharing that recognized chapter number, across the whole group (`expandToGroup` in the screen model's `markChaptersRead` / `bookmarkChapters`). Read and bookmark only: page position (`last_page_read`) is per-source, since page counts differ across scans.

Tracker links mirror across the group via [`PropagateTrackerLinks`](../../../app/src/main/java/reikai/domain/manga/PropagateTrackerLinks.kt), gated by `syncTrackerLinksGrouped`. It is copy-on-write: when a tracker lives on one member, every favorited member of the group gets its own `manga_sync` row for that tracker. Because each member ends up with its own row, the links survive an unmerge with no cleanup needed. Only missing trackers are added (an existing binding is never overwritten), and a tracker whose remote id disagrees across the group is skipped rather than guessed. Removal is intentionally not propagated: dropping a tracker affects only the manga it was removed from. (How this is exposed in the UI is in [tracker-sync.md](../../tracker-sync.md).)

### FlareSolverr

[`FlareSolverrClient`](../../../core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/FlareSolverrClient.kt) (in `core/common`) owns all FlareSolverr internals: the request/response DTOs, the FS HTTP client, a warm shared session, per-host request dedup, cookie install, and per-host User-Agent pinning. Mihon's `CloudflareInterceptor` keeps the challenge detection and decides when to delegate to it, inside a small `// RK` island. On a detected challenge, if FlareSolverr is enabled the request goes to the FS instance (which drives a real headless browser through the challenge) and the returned cookies are installed into the shared cookie jar; otherwise it falls back to Mihon's WebView solver. Setup and the enable toggle are documented in [flaresolverr.md](../../flaresolverr.md).

## Key files

Confirmed present on `main`:

- [`app/src/main/java/reikai/domain/MergeGroupAlgebra.kt`](../../../app/src/main/java/reikai/domain/MergeGroupAlgebra.kt): the pure group set-algebra + collapse parsers, shared with novels.
- [`app/src/main/java/reikai/domain/manga/MangaMergeManager.kt`](../../../app/src/main/java/reikai/domain/manga/MangaMergeManager.kt): manga merge/split/dissolve operations, same-title resolution, tracker healing.
- [`app/src/main/java/reikai/domain/manga/ChapterAggregation.kt`](../../../app/src/main/java/reikai/domain/manga/ChapterAggregation.kt): cross-source chapter stitcher (trunk pick + gap-fill + float-keyed dedup).
- [`app/src/main/java/reikai/presentation/library/MangaMergeCollapse.kt`](../../../app/src/main/java/reikai/presentation/library/MangaMergeCollapse.kt): one card per group at the library grouping layer.
- [`app/src/main/java/reikai/presentation/manga/MergeSourceChips.kt`](../../../app/src/main/java/reikai/presentation/manga/MergeSourceChips.kt): source-switcher chips on the details screen.
- [`app/src/main/java/reikai/presentation/manga/ManageSourcesDialog.kt`](../../../app/src/main/java/reikai/presentation/manga/ManageSourcesDialog.kt): manual merge / split / remove dialog.
- [`app/src/main/java/reikai/domain/manga/PropagateTrackerLinks.kt`](../../../app/src/main/java/reikai/domain/manga/PropagateTrackerLinks.kt): cross-group tracker-link mirroring.
- `app/src/main/java/reikai/presentation/library/preferredsources/`: the preferred-source ranking screen (Screen + ScreenModel + Content).
- [`app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt`](../../../app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt): the merge prefs (`mangaManualMerges`, `mangaManualUnmerges`, `autoMergeSameTitle`, `preferredMangaSources`, `syncTrackerLinksGrouped`).
- [`app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`](../../../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt): `// RK` islands: group-aware chapter pipeline, manage-sources dialog, cross-source read/bookmark, healing snackbar.
- [`app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt`](../../../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt): collapse wired into the favorites flow, merge/unmerge bulk selection actions.
- [`core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/FlareSolverrClient.kt`](../../../core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/FlareSolverrClient.kt): the FlareSolverr client (`CloudflareInterceptor`, `NetworkHelper`, `NetworkPreferences`, `UserAgentInterceptor`, `AndroidCookieJar` carry the `// RK` islands).

Tests: [`MergeGroupAlgebraTest`](../../../app/src/test/java/reikai/domain/MergeGroupAlgebraTest.kt), [`MangaMergeManagerTest`](../../../app/src/test/java/reikai/domain/manga/MangaMergeManagerTest.kt), [`ChapterAggregationTest`](../../../app/src/test/java/reikai/domain/manga/ChapterAggregationTest.kt), [`PropagateTrackerLinksTest`](../../../app/src/test/java/reikai/domain/manga/PropagateTrackerLinksTest.kt).

## Status

Shipped. P4 is done and on-device verified (Roadmap P4, marked done in `ROADMAP.md`). The same engine was later generalized to novels in P5 (`NovelMergeManager`, `NovelChapterAggregation`, `NovelMergeCollapse` reuse `MergeGroupAlgebra`), and the Tier 0 cleanup pass extracted the shared algebra and collapse parsers into `MergeGroupAlgebra` so both content types share one implementation.

## Decisions & tradeoffs

- **Pref-based, not a DB merge table.** Komikku's approach is a `merged.sq` join table plus a `MergedSource` virtual source and roughly 443 patched files. Reikai deliberately avoids that weight: a group is computed from two `Set<String>` prefs, so there is no schema and no migration. The id-based group prefs are serialized as stable `{url, source}` refs for backup and rebuilt on restore (`BackupMangaMerge`, `MangaRestorer.restoreMerges`), since the local ids change on a fresh install. Tradeoff: groups are recomputed rather than queried, and the set math has to be careful (hence the pure, heavily-tested `MergeGroupAlgebra`).
- **Collapse at the library grouping layer, not in SQL.** Favorites load normally; collapse happens after, alongside the existing dynamic grouping. Keeps the merge feature off the query path and out of Mihon's data layer.
- **Distinct-count trunk, not most-rows.** Borrowing Komikku's "most chapters" trunk would let a scanlator-heavy source (many duplicate rows) win over a source that actually covers more real chapter numbers. Counting distinct recognized numbers fixes the Comick case.
- **Float-narrowed dedup key.** Forced by the source API storing `chapter_number` as a 32-bit float: a reported `1.1f` and a recognition-parsed `1.1` differ by about 2.4e-8, so an exact-double key silently leaks duplicates. This also affects the novel side (same float column).
- **Copy-on-write tracker propagation.** Each member gets its own `manga_sync` row, so links survive an unmerge with no split-time cleanup. Removal is not propagated (it would be surprising to lose a tracker on an unrelated source). A conflicting remote id across the group is skipped, not guessed.
- **Auto same-title is non-destructive.** The toggle governs only automatic same-title grouping; turning it off does not break manual merges (those are separated via Manage Sources, the long-press chip, or "Separate all merged series"). Same-title members are auto-grouped on first sight unless an unmerge pair already exists.
- **Global preferred-source ranking, not per-manga.** Per-manga priority was deliberately not built (KISS): one ordered list covers the real use case, and when unset the aggregator behaves exactly as before.
- **FlareSolverr internals extracted to its own class.** Mihon's `CloudflareInterceptor` keeps only challenge detection and the delegate decision inside a `// RK` island; all FS mechanics live in net-new `FlareSolverrClient`, keeping the patch on Mihon's file minimal and the surface greppable.
