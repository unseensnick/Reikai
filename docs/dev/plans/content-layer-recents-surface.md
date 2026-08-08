# Content-layer recents surface (history, updates and the combined feed)

> **Standing rules for every session working this surface.** The goal is "write once, both get" unless the content type genuinely doesn't support it; for the program context read [content-layer-architecture.md](content-layer-architecture.md) and [unified-content-ui.md](unified-content-ui.md) first. And hold the nullable-soup rule: divergent bits are typed capability slots, never nullable fields or boolean-flag combinations on shared models. Two companion rules live in content-layer-architecture.md's Decisions and bind here too: a shared component either derives a piece of state or does not own it (sharing the storage while each type interprets it its own way is the same defect wearing a different hat), and a behaviour test written for one engine gets its twin. Two more bind on this surface specifically. **All-first**, taken from the library ([library-all-chip.md](library-all-chip.md)): All is the real feed and the Manga and Novels chips are predicates over it, so anything describing the list or a row in it is one value, and only what describes a content type stays per type. And **every keyed structure keys on `EntryId`**, never a raw `Long`: the two id spaces overlap, so a `Long`-keyed map over a mixed feed cross-wires silently instead of crashing. The surfaces being replaced broke the first rule at the state level (two search queries, two loading flags, two dialog channels and two selections on one screen) and paid for it with a clear-all that prompts twice, a novel feed that gates the manga chip's spinner, and a long-press range that selects rows the user never swept.

## Goal

One Reikai-owned recent-activity surface serving manga and light novels across four modes: a combined title-centric feed, a combined grouped digest, Updates, and History. A Settings preference collapses the separate Updates and History tabs into one Recents tab holding those modes; with the preference off, today's two tabs remain, rendering the same engine at two of its modes.

This is the fifth surface of the content-layer program ([content-layer-architecture.md](content-layer-architecture.md)) and its third orchestration takeover, after the library and the migrate flow. It supersedes the forward half of [unified-updates.md](unified-updates.md), which stays the record of the shipped unified Updates tab.

## Why

Two forces meet on these two screens, and doing either alone reworks the other.

**The behaviour is still forked.** History and Updates were built when the program's seam was drawn at the UI leaf, so they share row composables over per-type feeds and nothing below. The measured shape of the fork: the novel Updates feed filters nothing in SQL where the manga feed filters four axes; both feeds resolve category membership per row in Kotlin behind a screen-lifetime cache, on both types; the "started" predicate disagrees with its SQL twin in the negated branch; and list-level state (search, loading, dialogs, selection, clear-all) is stored twice, once per content type, on a single screen. These are duplicate-implementation defects, the failure mode the program's 2026-08-06 ruling exists to remove.

**The combined feed reshapes the same screens.** `unseensnick/Reikai#57` asks for a title-centric feed merging recently read, recently updated and newly added, opening the most relevant chapter, with Updates and History preserved. The owner's answer is a preference that collapses the two tabs into one Recents tab with modes inside, so nobody loses one-tap access. Building the behaviour seam first and the tab shape second would rework both, so they are one piece of work.

**The sync cost is the lowest of any surface taken over so far.** Twelve months of upstream history on both screens: History took five commits, every one cross-cutting mechanical work (the datetime conversion, the ViewModel migration, a compose-workaround removal, the immutable-collections drop, preference properties) and none behaviour-specific; Updates took thirteen, of which three are behaviour (`mihonapp/mihon#3589`, the earlier filters commit, and a selection-signature cleanup) and the rest mechanical or revert churn. Compare the migrate flow's roughly eighteen commits with thirteen behaviour fixes.

## Approach

### The amendment: scope and hard lines

This is the fourth orchestration amendment to the program, recorded alongside the others in [content-layer-architecture.md](content-layer-architecture.md). It supersedes the history-and-updates part of the 2026-08-06 "redo at the behaviour seam" ruling by naming what the redo actually is.

- **The takeover owns pure UI plus orchestration**: the two Reikai shells (`ReikaiUpdatesScreen`, `ReikaiHistoryScreen`) and their builders, the two Reikai novel models (`NovelUpdatesViewModel`, `NovelHistoryViewModel`, which dissolve into providers), the two Voyager tabs' bodies, and the shared row leaves (`EntryUpdatesRow`, `EntryHistoryRow`), which survive into the new engine rather than being replaced.
- **The engine floor stays Mihon and synced**, never reimplemented: `GetUpdates`, `UpdatesRepository` and its impl, `GetHistory`, `RemoveHistory`, `GetNextChapters`, `SetReadStatus`, `DownloadManager`, and every interactor below them. The novel sinks (`GetNextNovelChapter`, the novel repositories, `NovelDownloadCache`) stay likewise. A step that starts reimplementing what `setReadStatus` does has gone too far.
- **`UpdatesViewModel` and `HistoryViewModel` stay live** as the manga providers, minimally patched, exactly as `LibraryViewModel` did. They are not manifested. Their three `// RK` category-filter islands retire when filtering moves into SQL, which shrinks the patch surface rather than growing it.
- **Replaced Mihon files are deleted and recorded** in the off-path manifest ([off-path-manifest.md](../off-path-manifest.md)). The candidates are pure UI: `UpdatesUiItem.kt`, `UpdatesDeleteConfirmationDialog.kt`, `eu/kanade/presentation/history/HistoryScreen.kt` and its dialog components, and `UpdatesFilterDialog.kt` if the mode-scoped sheet replaces it outright rather than keeping its two `// RK` slots.
- **The reader-adjacent verbs are out of scope.** Resume opens a chapter through the existing readers; nothing about reader behaviour changes here.

### All-first, taken from the library

The library's ruling ([library-all-chip.md](library-all-chip.md)) is adopted whole and is the biggest single difference from how these two screens work today.

- **All is the real feed; the chips are predicates over it.** Both providers always feed the assembly. The chip filters the assembled item stream, never gates what gets collected. Loading, emptiness and the last-updated line become one derived value each instead of two combined by hand.
- **Anything describing the list is one value.** One search query, one loading flag, one dialog channel, one selection, one clear-all confirmation. Anything describing a content type stays per type: the feed queries, the download-state source, the scanlator predicate.
- **The assembly algorithm is chip-blind**, and its output is chip-tagged, because the flow lags a chip flip by one emission and the screen must render an assembly only when the tag matches.
- **Counts follow the active chip**, computed over the filtered rows. **An empty section is always hidden**, no bare header.
- **Providers keep their own filtering and search.** They read different tables through different repositories, so filtering at the provider is honest; only assembly is shared.

### Ruled design picks (owner, 2026-08-08)

1. **Four modes**, matching Yokai's set: a grouped digest, a flat combined feed, Updates, and History. Cut to three later if the digest does not earn its place; because modes are render policies over one item stream, that is deleting a policy rather than unpicking a design.
2. **The flat combined feed is what the request asked for.** `unseensnick/Reikai#57` describes one main entry per title, deduplicated by title, sorted by latest activity, opening the most relevant chapter, which is Yokai's `UngroupedAll` (whose own label is "All"). The digest is Yokai's `GroupedAll`, a different thing: three capped sections with headers.
3. **Mode names are placeholders** until they can be seen in the app. Nothing downstream depends on the strings.
4. **The combined modes always collapse merged series**, and History's collapsing gap closes with them rather than being left as a per-mode special case. A title-centric feed over an input that double-counts a merged series is wrong by construction.
5. **Newly-added respects the category filter but not the chapter-state filters.** A newly-added row has no chapter, so unread, started and bookmarked have nothing to test.
6. **No paging.** The feeds stay bounded as they are today. Yokai pages every mode except the digest at fifty rows with a recursive top-up loop, and that is what forces cross-page deduplication bookkeeping; Reikai's feeds are bounded and unpaged and work.
7. **The whole Yokai affordance set is in scope** except paging: swipe actions, a per-row download button with its visibility preference, read-progress display, and History's time grouping.
8. **The four Updates category-filter preference keys are dropped**, not migrated, with a restore skip, matching how the library retired its per-type filter keys. The master toggle defaults off and a filter is cheaply re-picked, so a migration and a `versionCode` bump buy nothing.
9. **One chip preference for the surface.** `updates_content_type` and `history_content_type` retire onto a single key that serves the surface in both tab shapes.

### What the requester already has

Two ingredients of the request are shipped and must not be rebuilt: per-title collapsing (the Updates "Group by series" toggle, merge-aware) and manga-plus-novel interleaving on both screens. History additionally deduplicates to one row per entry in SQL already, through the `max_last_read` subquery in `historyView` and its novel twin. What is genuinely new is combining the three activity lanes, a newly-added query per content type, and the conditional tab set.

### Known defects in the surfaces being replaced

Recorded so the takeover fixes them deliberately rather than carrying them across or losing them. Found by the 2026-08-08 research pass and verified in current code.

- Clear-all under the All chip sets the delete-all dialog on both models, so the user confirms twice and gets two snackbars. The chip-scoped intent is right; the two-dialog execution is not.
- The novel feed's loading flag is unconditional in the shared loading expression, so the novel first emission gates the Manga chip's spinner.
- Long-press range selection exists only for manga and fills its range using indices into the manga model's own list, which is not the rendered order once the All chip interleaves or grouping collapses. Novels have no range selection at all.
- The "started" filter's negated branch disagrees between SQL and the novel Kotlin path: SQL requires unstarted and unread, the Kotlin negation lets read chapters through. Moving novels into SQL closes it.
- The excluded-scanlators switch renders unconditionally and is a silent no-op on the Novels chip, which the capability-slot rule forbids. It becomes hidden for novels.
- History resume is merge-unaware on both content types, and there are four different "next chapter to open" rules in the app: the manga library's (user filters, download filter, skips chapters read in other sources), manga history's (scanlator filter only), the novel library's (pools the merge group, sorts by cross-source chapter number), and novel history's (single novel, source order, no filters). The engine needs one rule per content type, reached through the provider.
- Tab-reselect resume on History compares manga-latest against novel-latest regardless of the active chip, so it can open a manga from the Novels chip.
- Both feeds search the raw SQL title while the row displays a custom override, so a renamed entry cannot be found by its displayed name.
- Novel update rows carry no live download progress; the provider hardcodes zero where manga rows merge the download manager's status and progress flows.
- Dead code to remove with the surfaces: `NovelHistoryViewModel` injects a merge manager it never references, `UpdatesViewModel.showConfirmDeleteChapters` has no caller (making its dialog branch unreachable), and its `InternalError` event is never sent.
- Deliberate and kept: the manga feed bounds on chapter upload date while the novel feed bounds on fetch date, because many light-novel sources leave upload date at zero. The reason is recorded in the novel view's own header comment and stays a typed divergence.

## Sequenced steps

1. **The data floor.** Port `mihonapp/mihon#3589` into `updatesView.sq` (the include and exclude category predicates with their two empty-set booleans, uncategorized as sentinel id zero via `NOT EXISTS`), write the novel twin against `novels_categories`, and move the novel feed's unread, started and bookmarked filters into SQL so both feeds filter the same way end to end. Downloaded stays in Kotlin on both, because download state is not in the database. Add a recently-added query per content type against the base tables rather than the library views, whose per-entry chapter-count aggregation a newly-added row has no use for. Index `novels_categories` on both columns in its own migration; it has none today where the manga junction has both, and the new predicate runs an `EXISTS` per row. Deletes `applyReikaiCategoryFilter` and both screen-lifetime membership caches, which also fixes a real wart for free: re-categorizing a series while the screen is open currently does not reflect until reopen, on both content types.
2. **The preference collapse.** The four per-type Updates category keys retire onto one shared include and exclude pair, moved into the `sharedSets` bucket of `CategoryIdPreferences` so the cleanup migration and both category-delete paths pick them up, with the dead keys skipped in `PreferenceRestorer`. Category ids are one space since the schema unification, so one set binds to both queries and a manga-only id simply matches no novel. `updates_content_type` and `history_content_type` retire onto one surface-wide chip key the same way. This is what lets step 1's queries take a single id list.
3. **The neutral feed item and the provider seam.** A `RecentsProvider` extending a `RecentsBehavior`, modelled on `LibraryProvider` over `LibraryBehavior`, one per content type. The item is neutral and carries entry identity as an `EntryId`, a timestamp, a lane (read, updated, added) and, separately from the row itself, the target chapter to open. Row-versus-target is the load-bearing distinction: in Updates a row is a chapter, in History a row is a history entry, and in the combined modes a row is a title whose timestamp comes from one event while its tap target is resolved by rule. Per-type payloads ride as opaque values the shared layer never inspects, the discipline the migrate flow's DTOs already use. Resume becomes one provider method, which is where the four divergent rules collapse.
4. **Assembly, chip-blind.** The engine concatenates the providers' lane flows, deduplicates by entry for the combined modes (survivor is the most recent, since the stream is newest-first), sorts, and hands the result to a render policy. Output is chip-tagged. The chip predicate is applied to the assembled stream. Every keyed structure in this step keys on `EntryId`.
5. **One value each.** Search, loading, dialogs and selection move onto the engine, selection as a `Set<EntryId>`. This is what removes the double clear-all prompt, the novel-gated spinner and the chip-blind reselect resume, structurally rather than one fix at a time.
6. **Merge collapse inside assembly.** Series keys resolve once for whichever lanes are showing, so History gains collapsing and Updates' group-by-series becomes a display toggle over the same keys instead of a second mechanism. Assembly never passes the chip down to the merge repository, which rejects the mixed type by design.
7. **The four modes as render policies** over that one stream: flat (deduplicated by title, bounded), digest (lane-bucketed, capped, headed, with footers that jump to the single-lane modes), Updates, History. A search query forces the flat form and includes read items, as Yokai does.
8. **The cutover.** The engine replaces both shells; the two Reikai novel models dissolve into providers; the two Mihon models stay live behind adapters. The manga-only Upcoming calendar and the scanlator predicate become typed capability slots, hidden for novels rather than inert.
9. **The affordance harvest.** Swipe actions over the existing `ChapterSwipeAction` preferences, which already offer toggle-read, toggle-bookmark, download and disabled per direction and already route through the shared details behaviour on both content types, so this is reuse rather than a new gesture system; the mark-read side effects (delete-after-read, tracker push) already fire from `SetReadStatus` and `DeleteNovelChaptersAfterRead`. Then the per-row download button with its visibility preference, read-progress display on a row, and History's time grouping. Yokai's three-tab options sheet folds into the existing filter sheet as mode-scoped sections rather than becoming a second sheet.
10. **The tab set.** A preference next to the tablet-UI mode in `UiPreferences`, rendered in the Appearance settings group. On flip, force the tab navigator's current tab back into the allowed set: Voyager persists the tab object rather than an index, and selection compares classes, so a stale current tab renders on with nothing highlighted instead of crashing. Re-key the unread badge off the tab type (its counter is already shared, since the novel update job increments the same preference the manga one does). Map both launcher shortcuts to the Recents tab pre-switched to the matching mode. Resolve the reselect conflict: Updates pushes the download queue today, History resumes the last read chapter.
11. **The behaviour inventory and the manifest rows.** Walk both replaced surfaces end to end, marking each behaviour present, deliberately dropped with the reason, or missing. This is the completion bar, not device verification; the migrate surface passed a device pass and still had two upstream behaviours silently dropped.

Steps 1 and 2 are independent of the mode rulings and can land first. Step 3 depends on both. Steps 9 and 10 depend on the cutover but not on each other.

## Key files

Paths are from the repo root, which **is** `.../yokai-y2k/app`, so app-module sources live under `app/src/main/java/`.

To be created, under `app/src/main/java/reikai/presentation/recents/`: the engine, the `RecentsProvider` and `RecentsBehavior` seam, the two adapters, the neutral item and screen state, and the render policies.

Surviving into the new engine:

- `reikai/presentation/updates/EntryUpdatesRow.kt` and `reikai/presentation/history/EntryHistoryRow.kt`: the neutral row leaves, already the manifested replacements for their Mihon originals.
- `reikai/domain/entry/EntryId.kt`: the identity every keyed structure uses.
- `reikai/presentation/library/LibraryProvider.kt`, `LibraryBehavior.kt`, `LibraryEngine.kt` and `LibraryAssembly.kt`: not consumed, but the structural template this surface imitates.

Dissolving into providers: `reikai/presentation/updates/NovelUpdatesViewModel.kt`, `reikai/presentation/history/NovelHistoryViewModel.kt`.

Staying live and synced behind adapters: `eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt`, `eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt`, `eu/kanade/tachiyomi/ui/updates/UpdatesSettingsViewModel.kt`.

Being replaced: `reikai/presentation/updates/ReikaiUpdatesScreen.kt`, `reikai/presentation/history/ReikaiHistoryScreen.kt`, `reikai/presentation/updates/ReikaiUpdatesCategoryFilter.kt`.

Elsewhere:

- `data/src/main/sqldelight/tachiyomi/view/`: `updatesView.sq`, `novelUpdatesView.sq`, `historyView.sq`, `novelHistoryView.sq`, plus the new recently-added queries.
- `data/src/main/sqldelight/tachiyomi/data/novels_categories.sq`: needs the two indexes its manga twin has.
- `app/src/main/java/reikai/domain/category/CategoryIdPreferences.kt`: where the retiring category keys move buckets.
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt` and `ui/main/MainActivity.kt`: the tab list, the sealed tab intents, the badge, and the shortcut mapping.
- `refs/yokai` (a sibling of this repo, not inside it): the Recents feature reference. `RecentsPresenter`, `RecentsController`, `RecentsViewType` and `history.sq`'s union query carry the algorithms; none of the code ports, since Yokai is Conductor plus RecyclerView over mutable models.

## Status

Not started. Planned 2026-08-08, grounded by a research pass across the two current surfaces, the Yokai reference, the tab-set consumers and the deferred upstream commit, with every reported finding re-read in current code before it entered this plan.

Both gates the forward work waited on are cleared: the ViewModel migration shipped for every Mihon file on these surfaces, and the upstream sync base reached upstream head. `mihonapp/mihon#3589` (mihon `1d8a2b05d`) is the one upstream commit sitting below the frontier and this surface owns it; `GetUpdates.kt` was deliberately taken at mihon `6d69903a5` rather than head so its category-filter hunks are still there to port.

## Decisions & tradeoffs

- **All-first rather than a mode-scoped chip.** Chosen by the owner after the library proved it. The alternative, letting the chip decide what gets collected, is what produces the class of defect this surface already has: state that describes one list stored once per content type, with the two copies free to disagree.
- **Four modes, cut down later rather than added later.** The digest and the flat feed answer different questions and neither substitutes for the other, and the flat one is what was actually requested. Building both as render policies over one stream makes the reduction cheap and the addition-later expensive, so the risk is taken in the direction that is reversible.
- **The combined feed is bounded, not paged.** Yokai's endless paging is the source of its cross-page deduplication bookkeeping. Reikai's feeds are already bounded (three months and five hundred rows on Updates; History is unpaged but SQL-deduplicated to one row per entry), and a bounded combined feed keeps the deduplication a single pass over a known set.
- **One shared category-filter set rather than two per-type sets.** Category ids are one space since the schema unification, so per-type sets encode a distinction the data does not have. This follows the library's identical ruling, and it is the reason the SQL step takes one id list instead of two.
- **The chip preference is one key for the surface, not one per mode.** The chip describes the list, and there is one list with modes over it.
- **Row identity and target chapter are separate fields, deliberately.** Conflating them is what makes a title-centric feed impossible to add later without reworking both screens, and it is the single design choice that most shapes the item model.
- **The two Mihon models stay live.** The takeover stops at orchestration. This surface's upstream churn is the lowest of any taken over so far, so there is no case for absorbing engine files here even though the amendment would permit it.

## Gotchas worth knowing before starting

- **Merging the two flows couples their emission rates.** Each content type has its own download-cache tick, and one completed download would re-run assembly over both types' rows. The library's guards are unequal (the manga combine has two `distinctUntilChanged`, the novel one has none, and each debounces search on its own timer), so hoist the query above both and do not let the weaker guard become the shared one.
- **The merge group repository rejects the mixed content type explicitly**, at every entry point. That is correct and should stay: merge groups are per type, and only the providers reach it, each with its own hardcoded type. Assembly must never pass the chip down.
- **Voyager persists the tab object, not an index**, and tab selection compares classes. A preference flip that removes the current tab therefore fails quietly (nothing highlighted, the orphan tab still rendering) rather than loudly. Verify the restore path with an actual process kill, not just a flip.
- **`TabOptions.index` is read nowhere in this repo**, so index collisions when the tab set changes shape are cosmetic.
- **The unread badge's counter is already shared**: the novel update job increments the same preference the manga job does, and the Updates screen resets it. Only the badge's tab-type key is manga-specific.
- **History is already one row per entry in SQL.** What it does not collapse is a series merged across sources, which are distinct rows. Do not "fix" the per-entry deduplication that is already there.
- **The two feed bounds use different columns on purpose.** Manga bounds on chapter upload date, novels on fetch date, because many light-novel sources leave upload date at zero. Unifying them would hide novel updates.
- **A recently-added query must coalesce on the novel side.** `date_added` is not null on the manga table and nullable on the novel one.
- **No Compose UI tests exist**, so a bulk deletion of composables cannot be caught by the suite. Verify a cutover by function inventory and by walking the consumers of every state field that moves.
