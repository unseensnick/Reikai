# Content-layer recents surface (history, updates and the combined feed)

> **Standing rules for every session working this surface.** The goal is "write once, both get" unless the content type genuinely doesn't support it; for the program context read [content-layer-architecture.md](content-layer-architecture.md) and [unified-content-ui.md](unified-content-ui.md) first. And hold the nullable-soup rule: divergent bits are typed capability slots, never nullable fields or boolean-flag combinations on shared models. Two companion rules live in content-layer-architecture.md's Decisions and bind here too: a shared component either derives a piece of state or does not own it (sharing the storage while each type interprets it its own way is the same defect wearing a different hat), and a behaviour test written for one engine gets its twin. Two more bind on this surface specifically. **All-first**, taken from the library ([library-all-chip.md](library-all-chip.md)): All is the real feed and the Manga and Novels chips are predicates over it, so anything describing the list or a row in it is one value, and only what describes a content type stays per type. And **every keyed structure keys on `EntryId`**, never a raw `Long`: the two id spaces overlap, so a `Long`-keyed map over a mixed feed cross-wires silently instead of crashing. The surfaces being replaced broke the first rule at the state level (two search queries, two loading flags, two dialog channels and two selections on one screen) and paid for it with a clear-all that prompts twice, a novel feed that gates the manga chip's spinner, and a long-press range that selects rows the user never swept.

## Goal

One Reikai-owned recent-activity surface serving manga and light novels across four modes: a combined title-centric feed, a combined grouped digest, Updates, and History. A Settings preference collapses the separate Updates and History tabs into one Recents tab holding those modes; with the preference off (the default), today's two tabs remain, rendering the same engine at two of its modes.

This is the fifth surface of the content-layer program ([content-layer-architecture.md](content-layer-architecture.md)) and its third orchestration takeover, after the library and the migrate flow. It supersedes the forward half of [unified-updates.md](unified-updates.md), which stays the record of the shipped unified Updates tab.

## Why

Two forces meet on these two screens, and doing either alone reworks the other.

**The behaviour is still forked.** History and Updates were built when the program's seam was drawn at the UI leaf, so they share row composables over per-type feeds and nothing below. The measured shape of the fork: the novel Updates feed filters nothing in SQL where the manga feed filters four axes; both feeds resolve category membership per row in Kotlin behind a screen-lifetime cache, on both types; the "started" predicate disagrees with its SQL twin in the negated branch; and list-level state (search, loading, dialogs, selection, clear-all) is stored twice, once per content type, on a single screen. These are duplicate-implementation defects, the failure mode the program's 2026-08-06 ruling exists to remove.

**The combined feed reshapes the same screens.** `unseensnick/Reikai#57` asks for a title-centric feed merging recently read, recently updated and newly added, opening the most relevant chapter, with Updates and History preserved. The owner's answer is a preference that collapses the two tabs into one Recents tab with modes inside, so nobody loses one-tap access. Building the behaviour seam first and the tab shape second would rework both, so they are one piece of work.

**The sync cost is the lowest of any surface taken over so far.** Twelve months of upstream history on both screens: History took five commits, every one cross-cutting mechanical work (the datetime conversion, the ViewModel migration, a compose-workaround removal, the immutable-collections drop, preference properties) and none behaviour-specific; Updates took thirteen, of which three are behaviour (`mihonapp/mihon#3589`, the earlier filters commit, and a selection-signature cleanup) and the rest mechanical or revert churn. Compare the migrate flow's roughly eighteen commits with thirteen behaviour fixes.

## Approach

### The amendment: scope and hard lines

This is the fourth orchestration amendment to the program, recorded alongside the others in [content-layer-architecture.md](content-layer-architecture.md). It supersedes the history-and-updates part of the 2026-08-06 "redo at the behaviour seam" ruling by naming what the redo actually is.

- **The takeover owns pure UI plus orchestration**: the two Reikai shells (`ReikaiUpdatesScreen`, `ReikaiHistoryScreen`) and their row builders, the two Reikai novel models (`NovelUpdatesViewModel`, `NovelHistoryViewModel`, which dissolve into providers), the bodies of the two Voyager tabs (`UpdatesTab`, `HistoryTab`), and the category-filter control (`ReikaiUpdatesCategoryFilter`). The shared row leaves (`EntryUpdatesRow`, `EntryHistoryRow`) survive into the new engine rather than being replaced.
- **The engine floor stays Mihon and synced**, never reimplemented: `GetUpdates`, `UpdatesRepository` and its impl, `GetHistory`, `RemoveHistory`, `GetNextChapters`, `SetReadStatus`, `DownloadManager`, and every interactor below them. The novel sinks (`SetNovelReadStatus`, `GetNextNovelChapter`, the novel repositories, `NovelDownloadCache`) stay likewise. A step that starts reimplementing what `setReadStatus` does has gone too far.
- **`UpdatesViewModel` and `HistoryViewModel` stay live** as the manga providers, minimally patched, and are not manifested. The library takeover is the shape being followed, though note that whether `LibraryViewModel` is eventually manifested is recorded as unresolved in [content-layer.md](../../../.claude/rules/content-layer.md); this surface rules explicitly rather than inheriting that ambiguity, and the churn measured above gives no case for absorbing these two.
- **Replaced Mihon files are deleted and manifested in the same commit**, which the `pre-commit` and `commit-msg` hooks enforce. See "What can and cannot be deleted" below, because two of the obvious candidates are already ruled off the list.
- **The reader-adjacent verbs are out of scope.** Resume opens a chapter through the existing readers; nothing about reader behaviour changes here.

### All-first, taken from the library

The library's ruling ([library-all-chip.md](library-all-chip.md)) is adopted whole and is the biggest single difference from how these two screens work today. The mechanism matters as much as the slogan, so it is stated the way `LibraryEngine` actually implements it.

- **Every provider's flow always runs; the chip selects which providers' rows enter the assembly.** Nothing is gated at collection time, and the assembly algorithm itself is chip-blind. Derived values (loading, emptiness, whether a search is active, the last-updated line) are computed over the **active** providers only, which is what makes the novel feed stop gating the manga chip's spinner.
- **Anything describing the list is one value.** One search query, one loading flag, one dialog channel, one selection, one clear-all confirmation. Anything describing a content type stays per type: the feed queries, the download-state source, the scanlator predicate.
- **The assembly output is chip-tagged**, because the flow lags a chip flip by one emission and the screen must render an assembly only when the tag matches.
- **Counts follow the active chip**, computed over the filtered rows. **An empty section is always hidden**, no bare header.
- **Providers keep their own filtering and search.** They read different tables through different repositories, so filtering at the provider is honest; only assembly is shared. The engine holds the one query string and fans it out to the providers, exactly as `LibraryEngine.search` does.

### Ruled design picks (owner, 2026-08-08)

1. **Four modes**, matching Yokai's set: a grouped digest, a flat combined feed, Updates, and History. Cut to three later if the digest does not earn its place; because modes are render policies over one item stream, that is deleting a policy rather than unpicking a design.
2. **The flat combined feed is what the request asked for.** `unseensnick/Reikai#57` describes one main entry per title, deduplicated by title, sorted by latest activity, opening the most relevant chapter, which is Yokai's `UngroupedAll` (whose own label is "All"). The digest is Yokai's `GroupedAll`, a different thing: three capped sections with headers (at most four new-chapter rows, then continue-reading up to a combined nine, then at most four newly-added).
3. **Mode names are placeholders** until they can be seen in the app. Nothing downstream depends on the strings.
4. **The combined modes always collapse merged series**, and History's collapsing gap closes with them rather than being left as a per-mode special case. A title-centric feed over an input that double-counts a merged series is wrong by construction.
5. **Newly-added respects the category filter but not the chapter-state filters.** A newly-added row has no chapter, so unread, started and bookmarked have nothing to test.
6. **No paging.** Each lane keeps its own bound (see the bounds note below). Yokai pages every mode except the digest at fifty rows with a recursive top-up loop, and that is what forces cross-page deduplication bookkeeping; Reikai's feeds are bounded and unpaged and work.
7. **The whole Yokai affordance set is in scope** except paging: swipe actions, a per-row download button with its visibility preference, read-progress display, History's time grouping, and search within the surface.
8. **The four Updates category-filter preference keys are dropped, and their values are not carried over.** A new shared include and exclude pair is introduced, the four old keys are skipped on restore, and nothing migrates a value from one to the other. This matches how the library retired its per-type filter keys: the master toggle defaults off and a filter is cheaply re-picked, so a migration and a `versionCode` bump buy nothing.
9. **One chip preference for the surface.** `updates_content_type` and `history_content_type` retire the same way, replaced by a single new key.

### Feed bounds

Stated here because a fresh implementer would otherwise guess. Each lane keeps its own bound and the combined modes are the deduplicated union of whatever the active lanes return.

- **Updated lane**: three months and five hundred rows, as today.
- **Read lane**: unbounded in Kotlin, because the SQL already reduces it to one row per entry through the `max_last_read` subquery, so its size is bounded by how many entries have ever been read.
- **Added lane**: needs an explicit limit, since nothing bounds it naturally. Match the updated lane's five hundred unless measurement says otherwise.

### Deduplication, stated once

Three different phrasings would otherwise creep in. There is one rule: **one row per merge group, falling back to one row per entry for an entry that is in no group.** The survivor is the most recent, which falls out of the stream already being newest-first. Step 4 implements the per-entry half; step 6 upgrades it to per-group by resolving series keys, and that upgrade changes step 4's output rather than sitting beside it.

### The target chapter is decided per lane

The row a user sees and the chapter a tap opens are different things, and the rule is the lane's, not one global winner. This is why the item model separates them, and it is the single design choice that most shapes the item.

- **Read lane**: resume where you were, which is Mihon's existing history semantics (reopen the recorded chapter if it is unfinished, otherwise the next one). Do not "upgrade" this to the library's next-unread rule; they encode different intents and both are correct where they are.
- **Updated lane**: open the first unread chapter of the update burst, Yokai's rule (the first unread chapter fetched within twelve hours of the row's own chapter), falling back to the row's chapter.
- **Added lane**: the first unread chapter.

Per content type the mechanics differ and stay behind the provider: manga resolves through `GetNextChapters` with the scanlator filter, novels through `GetNextNovelChapter` in source order. Both are merge-unaware today, and closing that is step 6's job, not a per-lane special case.

### What can and cannot be deleted

Checked against [off-path-manifest.md](../off-path-manifest.md), which already rules on two of the obvious candidates.

- **Already manifested, no action**: `UpdatesScreen.kt` (replaced by `ReikaiUpdatesScreen`), `HistoryviewModelStateProvider.kt`, `HistoryItem.kt` and `HistoryWithRelationsProvider.kt` (replaced by `EntryHistoryRow`).
- **Explicitly ruled live and unlisted, do not delete**: `eu/kanade/presentation/history/HistoryScreen.kt` still holds `HistoryUiModel`, which Mihon's own model emits and the shared screen consumes, and `eu/kanade/presentation/updates/UpdatesUiItem.kt` still holds the type-neutral last-updated line. The manifest records both as partially collapsed. If the takeover does remove the last live symbol from either, that is when the row gets added.
- **Genuine candidates**: `UpdatesDeleteConfirmationDialog.kt`, `eu/kanade/presentation/history/components/HistoryDialogs.kt`, and `UpdatesFilterDialog.kt` if the mode-scoped sheet replaces it outright rather than keeping its two `// RK` slot parameters.
- **Note that `EntryUpdatesRow` has no manifest row**, unlike its history twin. Nothing is wrong with that (its Mihon original is still partially live), but do not assume symmetry.

### What the requester already has

Two ingredients of the request are shipped and must not be rebuilt: per-title collapsing (the Updates "Group by series" toggle, merge-aware) and manga-plus-novel interleaving on both screens. History additionally deduplicates to one row per entry in SQL already, through the `max_last_read` subquery in `historyView` and its novel twin. What is genuinely new is combining the three activity lanes, a newly-added query per content type, search on the Updates lane (which has none today), and the conditional tab set.

### Known defects in the surfaces being replaced

Recorded so the takeover fixes them deliberately rather than carrying them across or losing them. Found by the 2026-08-08 research pass and re-verified in current code. Each names the step that owns it.

- Clear-all under the All chip sets the delete-all dialog on both models, so the user confirms twice and gets two snackbars. The chip-scoped intent is right; the two-dialog execution is not. **Step 5.**
- The novel feed's loading flag is unconditional in the shared loading expression, so the novel first emission gates the Manga chip's spinner. **Step 4**, by deriving loading over the active providers.
- Long-press range selection exists only for manga and fills its range using indices into the manga model's own list, which is not the rendered order once the All chip interleaves or grouping collapses. Novels have no range selection at all. **Step 5**, where selection moves to the engine over the rendered order.
- The "started" filter's negated branch disagrees between SQL and the novel Kotlin path: SQL requires unstarted and unread, the Kotlin negation lets read chapters through. **Step 1**, closed by moving novels into SQL.
- The excluded-scanlators switch renders unconditionally and is a silent no-op on the Novels chip, which the capability-slot rule forbids. **Step 8**, as a typed slot hidden for novels.
- History resume is merge-unaware on both content types. **Step 6.**
- Tab-reselect resume on History compares manga-latest against novel-latest regardless of the active chip, so it can open a manga from the Novels chip. **Step 10**, with the rest of the reselect decision.
- History search runs on the raw SQL title while the row displays a custom override, so a renamed entry cannot be found by its displayed name. The library solved the same problem with a neutral overlay holder (`LibraryQueryOverlay`) read at query time; reuse that shape. **Step 5**, with the one query string. Note this is History only: the Updates surface has no search at all today.
- Novel update rows carry no live download progress; the provider hardcodes zero where manga rows merge the download manager's status and progress flows. **Deferred to Road B**, which owns the download-state reconciliation. Not fixed here, and the row keeps the honest zero rather than faking progress.
- Marking a chapter read from either feed pushes nothing to trackers. The shared, preference-gated `EntryAutoTrackOnMarkRead` exists and takes its three per-type halves as lambdas, but it is wired only from the two details models; the readers push separately. Upstream's Updates tab does not push either, so wiring it here is a deliberate divergence. **Owner decision, open question 1.**
- Dead code to remove with the surfaces: `NovelHistoryViewModel` injects a merge manager it never references, `UpdatesViewModel.showConfirmDeleteChapters` has no caller (making its dialog branch unreachable), and its `InternalError` event is never sent though the tab still consumes it. **Step 8.**
- Deliberate and kept: the manga feed bounds on chapter upload date while the novel feed bounds on fetch date, because many light-novel sources leave upload date at zero. The reason is recorded in the novel view's own header comment and stays a typed divergence.

## Sequenced steps

Steps 2 through 7 are compile-gated and render nothing: the existing shells stay on screen until step 8 swaps them. That is deliberate (it is how the details and migrate surfaces were cut over), and it means none of the defects above are actually fixed for a user until the cutover lands, whatever step owns them.

1. **The data floor**, in three ordered parts, because the later parts depend on the earlier ones.
   - **1a, the preferences.** Introduce one shared include and exclude category pair and one surface-wide chip key; retire the four per-type category keys and the two per-surface chip keys with a restore skip and no value carry-over. The new pair goes in the `sharedSets` bucket of `CategoryIdPreferences` so the cleanup migration and both category-delete paths pick it up. This is what lets the queries take a single id list, so it lands first.
   - **1b, the queries.** Port `mihonapp/mihon#3589` into `updatesView.sq` (the include and exclude predicates with their two empty-set booleans, uncategorized as sentinel id zero via `NOT EXISTS`). Add a **new** filtered novel query beside the existing `getRecentNovelUpdates` rather than changing it, mirroring how the manga side carries both `getRecentUpdates` and `getRecentUpdatesWithFilters`: the unfiltered one has two other callers in the home-screen widget, which must keep seeing raw recent updates. The new novel query carries the category predicate against `novels_categories` plus unread, started and bookmarked, so both feeds filter the same way end to end. Downloaded stays in Kotlin on both, because download state is not in the database. Add a recently-added query per content type against the base tables rather than the library views, whose per-entry chapter-count aggregation a newly-added row has no use for. Index `novels_categories` on both columns in its own `.sqm`, per the rule that index changes are not bundled; a SQLDelight migration needs no `versionCode` bump.
   - **1c, the consumption.** Point both live models at the new queries and delete `applyReikaiCategoryFilter` and both screen-lifetime membership caches. **This wiring is not throwaway**: the manga models stay live as the providers, and the novel models reshape into providers rather than being deleted, so the call sites move at most one seam. It also fixes a real wart, since re-categorizing a series while the screen is open currently does not reflect until reopen, on both content types. Expect a residual `// RK` island in `UpdatesViewModel`: its third delimited island opens with the category filter but closes after the custom-info overlay, which does not retire.
2. **The neutral feed item and the provider seam.** A `RecentsProvider` extending a `RecentsBehavior`, modelled on `LibraryProvider` over `LibraryBehavior`, one per content type. The item carries entry identity as an `EntryId`, a timestamp, a lane (read, updated, added) and, separately, the target chapter to open. Per-type payloads ride as opaque values the shared layer never inspects, the discipline the migrate flow's DTOs already use. Write the item and the seam as concrete types before any consumer exists, so the shapes are reviewable on their own.
3. **The assembly kernel, pinned by tests before it is wired.** A pure function over rows, lanes and inputs, the way `assembleLibrary` is, with a unit test standing in for the models (which resolve Injekt at init and cannot run in a unit test). Pin the deduplication rule, the lane ordering, the section caps, the empty-section rule and the count rule. Verify each test by mutation: delete the clause it names, watch it go red, restore it.
4. **The engine flow.** Combine the providers' lane flows, apply the chip by selecting active providers, run the kernel, tag the output with the chip. Derive loading, emptiness and the last-updated line over the active providers. Every keyed structure keys on `EntryId`.
5. **One value each.** Search, loading, dialogs and selection move onto the engine, selection as a `Set<EntryId>`, the query fanned out to the providers and matched through a neutral overlay holder so a custom title is searchable. Range selection is computed over the rendered order rather than a model's own list.
6. **Merge collapse inside assembly.** Series keys resolve once for whichever lanes are showing, upgrading step 3's per-entry deduplication to per-group and giving History collapsing it has never had. Updates' group-by-series becomes a display toggle over the same keys instead of a second mechanism. Assembly never passes the chip down to the merge repository, which rejects the mixed type at every content-typed entry point.
7. **The four modes as render policies** over that one stream: flat (deduplicated, bounded), digest (lane-bucketed, capped, headed, with footers that jump to the single-lane modes), Updates, History. A search query forces the flat form and includes read items, as Yokai does.
8. **The cutover.** The engine replaces both shells; the two Reikai novel models dissolve into providers; the two Mihon models stay live behind adapters. The manga-only Upcoming calendar and the scanlator predicate become typed capability slots, hidden for novels rather than inert. Delete the replaced Mihon files and add their manifest rows **in the same commit**, and remove the dead code listed above. Verify on a debug build and on a minified `preview` build before moving on, per the program's per-surface bar.
9. **The affordance harvest.** Swipe actions bind the existing `ChapterSwipeAction` preferences, which already offer toggle-read, toggle-bookmark, download and disabled per direction and already route through the shared details behaviour on both content types; the delete-after-read side effect rides `SetReadStatus` and `SetNovelReadStatus`, but tracker push does not (see open question 1). Then the per-row download button with its visibility preference, read-progress display on a row, and History's time grouping. Yokai's three-tab options sheet folds into the existing filter sheet as mode-scoped sections rather than becoming a second sheet.
10. **The tab set.** A preference next to the tablet-UI mode in `UiPreferences`, rendered in the Appearance settings group, defaulting off so nothing moves for an existing user until they opt in. On flip, force the tab navigator's current tab back into the allowed set: removing the current tab from the list leaves it rendering with nothing selected rather than crashing, since selection compares classes. Re-key the unread badge off the tab type (its counter is already shared, since the novel update job increments the same preference the manga one does). The two launcher shortcuts resolve through the preference: to their own tabs when it is off, and to the Recents tab pre-switched to the matching mode when it is on. Resolve the reselect conflict, including the chip-blind resume defect: Updates pushes the download queue today, History resumes the last read chapter.
11. **The behaviour inventory.** Walk both replaced surfaces end to end, marking each behaviour present, deliberately dropped with the reason, or missing. This is the completion bar, not device verification; the migrate surface passed a device pass and still had two upstream behaviours silently dropped.

Dependencies: 1a before 1b before 1c. Step 2 depends on nothing but the rulings. Steps 3 and 4 depend on 2; 5, 6 and 7 depend on 4 and are independent of each other. Step 8 depends on 5, 6 and 7. Steps 9, 10 and 11 depend on 8; 9 and 10 are independent of each other.

## Key files

Paths are from the repo root, which **is** `.../yokai-y2k/app`, so app-module sources live under `app/src/main/java/`.

To be created, under `app/src/main/java/reikai/presentation/recents/`: the engine, the `RecentsProvider` and `RecentsBehavior` seam, the two adapters, the neutral item and screen state, the assembly kernel and its test, and the render policies.

Surviving into the new engine:

- `reikai/presentation/updates/EntryUpdatesRow.kt` and `reikai/presentation/history/EntryHistoryRow.kt`: the neutral row leaves.
- `reikai/domain/entry/EntryId.kt`: the identity every keyed structure uses.
- `reikai/presentation/library/LibraryProvider.kt`, `LibraryBehavior.kt`, `LibraryEngine.kt` and `LibraryAssembly.kt`: not consumed, but the structural template this surface imitates, down to the chip-tagged assembly and the lazily-resolved preference flows.
- `reikai/presentation/details/EntryAutoTrackOnMarkRead.kt`: the shared tracker push, if open question 1 rules it in.

Dissolving into providers: `reikai/presentation/updates/NovelUpdatesViewModel.kt`, `reikai/presentation/history/NovelHistoryViewModel.kt`.

Staying live and synced behind adapters: `eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt`, `eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt`, `eu/kanade/tachiyomi/ui/updates/UpdatesSettingsViewModel.kt`.

Being replaced: `reikai/presentation/updates/ReikaiUpdatesScreen.kt`, `reikai/presentation/history/ReikaiHistoryScreen.kt`, `reikai/presentation/updates/ReikaiUpdatesCategoryFilter.kt`, and the bodies of `eu/kanade/tachiyomi/ui/updates/UpdatesTab.kt` and `eu/kanade/tachiyomi/ui/history/HistoryTab.kt`.

Elsewhere:

- `data/src/main/sqldelight/tachiyomi/view/`: `updatesView.sq`, `novelUpdatesView.sq`, `historyView.sq`, `novelHistoryView.sq`, plus the new filtered novel query and the recently-added queries.
- `data/src/main/sqldelight/tachiyomi/data/novels_categories.sq`: needs the two indexes its manga twin has.
- `app/src/main/java/reikai/domain/category/CategoryIdPreferences.kt`: where the new shared category pair goes and the old keys leave.
- `app/src/main/java/reikai/presentation/widget/UnifiedUpdatesWidgetManager.kt` and `UnifiedUpdatesGlanceWidget.kt`: the other two callers of the unfiltered novel updates query. They must keep it.
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt` and `ui/main/MainActivity.kt`: the tab list, the sealed tab intents, the badge, and the shortcut mapping.
- `refs/yokai` (a sibling of this repo, not inside it): the Recents feature reference. `RecentsPresenter`, `RecentsController`, `RecentsViewType` and `history.sq`'s union query carry the algorithms; none of the code ports, since Yokai is Conductor plus RecyclerView over mutable models.

## Status

Not started. Planned 2026-08-08, grounded by a research pass across the two current surfaces, the Yokai reference, the tab-set consumers and the deferred upstream commit, then cold-read twice (once for sequencing and internal consistency, once against current code) with the corrections folded back in.

Both gates the forward work waited on are cleared: the ViewModel migration shipped for every Mihon file on these surfaces, and the upstream sync base reached upstream head. `mihonapp/mihon#3589` (mihon `1d8a2b05d`) is the one upstream commit sitting below the synced base and this surface owns it; `GetUpdates.kt` was deliberately taken at mihon `6d69903a5` rather than head, and is byte-identical to that blob, so the four lines that commit adds are still there to port.

## Open questions

1. **Should marking a chapter read from this surface push progress to trackers?** Today nothing does outside the details screens and the readers, and upstream's Updates tab does not either, so wiring the existing shared `EntryAutoTrackOnMarkRead` here is a deliberate divergence from Mihon. It is also the kind of parity gap the standing rules say to surface rather than decide quietly. Recommendation: wire it, since a swipe-to-mark-read affordance makes the surface a normal place to finish a chapter, and the component is already preference-gated with an ask option. Blocking only for step 9.

## Decisions & tradeoffs

- **All-first rather than a mode-scoped chip.** Chosen by the owner after the library proved it. The alternative, letting the chip decide what gets collected, is what produces the class of defect this surface already has: state that describes one list stored once per content type, with the two copies free to disagree.
- **Four modes, cut down later rather than added later.** The digest and the flat feed answer different questions and neither substitutes for the other, and the flat one is what was actually requested. Building both as render policies over one stream makes the reduction cheap and the addition-later expensive, so the risk is taken in the direction that is reversible.
- **The combined feed is bounded, not paged.** Yokai's endless paging is the source of its cross-page deduplication bookkeeping. Reikai's feeds are already bounded per lane, and a bounded combined feed keeps the deduplication a single pass over a known set.
- **One shared category-filter set rather than two per-type sets.** Category ids are one space since the schema unification, so per-type sets encode a distinction the data does not have. This follows the library's identical ruling, and it is the reason the queries take one id list instead of two.
- **The chip preference is one key for the surface, not one per mode.** The chip describes the list, and there is one list with modes over it.
- **Row identity and target chapter are separate fields, and the target rule is the lane's.** Conflating them is what makes a title-centric feed impossible to add later without reworking both screens. Picking one global rule instead would silently change what tapping a history row does.
- **The two Mihon models stay live.** The takeover stops at orchestration. This surface's upstream churn is the lowest of any taken over so far, so there is no case for absorbing engine files here even though the amendment would permit it.
- **The assembly kernel is pinned before it is wired.** The library did this and it is the only part of a takeover that can be tested without a device, since the models resolve Injekt at construction.

## Gotchas worth knowing before starting

- **The home-screen widget reads the unfiltered novel updates query.** Two of its three callers are the widget, so change the query's signature and the widget silently starts filtering, or stops compiling. Add a filtered query beside it instead.
- **Merging the two flows couples their emission rates.** Each content type has its own download-cache tick, and one completed download would re-run assembly over both types' rows. The library's guards are unequal (the manga combine has two `distinctUntilChanged`, the novel one has none, and each debounces search on its own timer), so hoist the query above both and do not let the weaker guard become the shared one.
- **The merge group repository rejects the mixed content type at every content-typed entry point**, thirteen of them, though three methods take no content type at all and are therefore unguarded. Merge groups are per type and only the providers reach the repository, each with its own hardcoded type; assembly must never pass the chip down.
- **Removing the current tab from the tab list fails quietly, not loudly.** Tab selection compares classes, so the orphan tab keeps rendering with nothing highlighted. Whether the tab also survives process death is a separate question about how Voyager saves the navigator; verify that path with an actual process kill rather than a preference flip.
- **`TabOptions.index` is read nowhere in this repo**, so index collisions when the tab set changes shape are cosmetic.
- **The unread badge's counter is already shared**: the novel update job increments the same preference the manga job does, and the Updates screen resets it. Only the badge's tab-type key is manga-specific.
- **History is already one row per entry in SQL.** What it does not collapse is a series merged across sources, which are distinct rows. Do not "fix" the per-entry deduplication that is already there.
- **The two feed bounds use different columns on purpose.** Manga bounds on chapter upload date, novels on fetch date, because many light-novel sources leave upload date at zero. Unifying them would hide novel updates.
- **A recently-added query must coalesce on the novel side.** `date_added` is not null on the manga table and nullable on the novel one.
- **`UpdatesViewModel`'s `// RK` islands are not all category-filter islands.** Twelve markers and seven islands, of which three are delimited; the third opens with the category filter and closes after the custom-info overlay, so moving filtering into SQL leaves a residual island rather than clearing it. Count them case-sensitively, or `work` and `Network` match too.
- **No Compose UI tests exist**, so a bulk deletion of composables cannot be caught by the suite. Verify a cutover by function inventory and by walking the consumers of every state field that moves.
