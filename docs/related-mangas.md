# Related-mangas carousel

A horizontal carousel of similar / related titles, shown below the description on the manga details screen. Pulls from several independent input streams and merges them into one row, so a single fast input can't dominate the result list and the same manga can't appear twice via different streams.

Base feature ported from [Komikku](https://github.com/komikku-app/komikku); Reikai adapts it into a single merged carousel and layers a taste-profile-driven personalization layer on top: extra candidates based on what you already track, plus a rerank step that reorders the row against your taste and can hide manga already in your library.

## Where it appears

*Manga details → below the description, above the start-reading button.*

The carousel renders as a horizontal row. While loading, a skeleton placeholder holds the slot so the rest of the screen doesn't jump when results arrive. Once results land, the skeleton swaps out and the carousel populates; if nothing comes back, the section hides entirely instead of leaving an empty row.

The fetch fires once when the screen first attaches, then caches for roughly 30 minutes. Switching source via the chip row re-runs the full fetch against the new source.

## Where candidates come from

Several independent streams feed the same pool. The first three are the Komikku-equivalent baseline; the last two are the Reikai personalization layer.

**The two Reikai-injected streams (#4 and #5) only run when the current manga is itself tracked on a recommendations-capable tracker (AniList, MyAnimeList, MangaUpdates, or Shikimori).** If the current manga is untracked, neither injected stream contributes.

### 1. Source-native related-manga API

Sources that ship a "related manga" hook expose those entries directly. Most HTTP-backed extensions (~377 in the Keiyoushi catalog) are opted in by default; a small number opt out explicitly. Sources whose details page has no parseable listing simply contribute nothing.

### 2. Keyword-search fallback

The current manga's title is split into keywords and each keyword runs through the source's standard search endpoint. Results stream in as each keyword completes, so the carousel fills incrementally rather than waiting for all keywords to finish.

### 3. Tracker recommendations

Four public tracker endpoints, each running without a tracker sign-in:

- **AniList**: recommendations on the series record
- **MyAnimeList**: community recommendations via Jikan
- **MangaUpdates**: community recommendations on the series record
- **Shikimori**: related/recommended titles on the series record

Per tracker: if you already have a track entry for the current manga, the existing remote id is used directly. Otherwise a title-search resolves the id first, then the recommendation fetch runs.

> **Implementation note (alt-title dedup).** Only AniList recommendations currently carry alternative titles (romaji / english / native / synonyms) into the dedup keying. MyAnimeList (Jikan), MangaUpdates, and Shikimori recommendations dedup on their primary title only: their responses don't parse alternative titles yet, so a tracker listing a series under an English title while a source lists the romaji can still produce two cards. The title normalizer (accents, fullwidth, punctuation, case) covers most collisions; full synonym parsing for these was deferred. Revisit if duplicate tracker cards show up in practice.

### 4. Tag search on current source *(Reikai)*

Your top three taste-profile tags (see [Taste profile](#taste-profile) below) run as searches against the current source. Stays on the current source: no source-switching while reading. Limitation: depends on the source supporting tag-style search; on sources that only do title search this contributes nothing.

Gated by Settings → Library → Recommendations → Candidate injection → **Tag search on current source** *(default on)*. Only contributes when the current manga is tracked on a recommendations-capable tracker.

### 5. Cross-recommendation from your tracked titles *(Reikai)*

This is a tracker-graph lookup. It takes the titles you have highly rated on a tracker (your top tracked titles), intersects them with the current manga's own tracker-recommendation list, then pulls each of those titles' tracker recommendations into the pool. Higher precision: surfaces matches that connect both to what you've already endorsed and to the manga you're viewing.

Gated by Settings → Library → Recommendations → Candidate injection → **Cross-recommendation from favorites** *(default on)*. Only contributes when the current manga is tracked on a recommendations-capable tracker.

## Taste profile

A weighted set of tags derived from your tracked manga across the configured trackers. The tag-search and cross-recommendation streams (#4 and #5) consume it.

### Cache lifecycle

The tracker library is cached locally so the recommendations row doesn't refetch from each tracker on every screen open. Refresh paths:

- **Manual**: Settings → Library → Recommendations → **Refresh now** (60 s cooldown after each press).
- **Event-driven**: when you add or update a track entry from inside the app, the cache row updates in place.
- **Optional auto-refresh**: Settings dropdown: `Never` (default) / `7 days` / `30 days`. Opt-in for users who want a fail-safe.

The cache rides the regular app backup pipeline, so it survives a backup-and-restore.

### Trackers in scope

You can pull your library from five trackers as taste-profile sources: AniList, MyAnimeList, Kitsu, Shikimori, and Bangumi. All default off (opt-in), and each appears only once you're logged into it. MangaUpdates is a recommendations-only tracker, not a taste-profile source.

### Tag scoring

Each tag's score combines your rating of the manga tagged with it and the manga's tracker status (Completed and Reading weigh positive, Dropped weighs negative, Plan-to-read is signal-free). The three highest-scoring tags become the top-3 used by the tag-search stream.

## How clicks work

- **Source-origin card** (came from source-native or keyword-search): opens the manga's detail page in the current source.
- **Tracker-origin card** (came from one of the trackers): opens Global Search pre-filled with the manga's title, so you can pick an installed source to read it on. Tracker links don't belong to any installed extension, so the Global Search detour avoids creating an orphan library row.

## Pool composition

A single deduplicated pool, capped at 30 entries in the carousel:

- **Title-level dedup**: the same manga arriving via source-native, a tracker, and a cross-recommendation collapses to one card. Source-origin entries win ties since they route directly to the reader on tap.
- **Ordering**: taste-reranked source-origin entries come first; tracker-origin entries follow in the order they arrived. There's no slot reservation or round-robin, just the 30-entry cap.

## Reranking *(Reikai)*

Once the merged pool is assembled, source-origin candidates are reordered against your taste profile, and (when the filters below are enabled) manga already in your library can be hidden. Tracker-origin entries keep their arrival ordering: their recommendations are already personalized by construction.

### Anti-echo (governed by the status filters)

Library entries can be hidden before scoring, controlled entirely by your [filter prefs](#filters-y2k). With every filter off (the default), nothing is hidden. The filter runs independently of the rerank toggle: turning *Rerank by taste* off keeps whatever filtering you've enabled active.

### Scoring

Each source-origin candidate is scored on three axes that the Reranking sliders (see [Settings](#settings) below) blend:

- **Popularity**: where the candidate landed in the pool's arrival order. Sources surface their most-relevant matches first, so arrival order is itself a useful signal.
- **Taste**: the average taste-profile weight of the candidate's tags. Untagged candidates score neutral and land near the popularity-only ordering.
- **Novelty boost**: small bonus for tags you have few tracked entries for. Encourages discovery without overwhelming the row.

The **Recommendation style** slider controls how much the taste / novelty axes matter relative to popularity. **Serendipity** controls how big the novelty bonus is *and* reserves a fraction of slots that keep popularity order regardless of taste, a built-in guard against echo collapse at the *Personalized* end.

### Diversity cap

No more than two of the taste-ranked picks may share the same dominant tag. Offenders are pushed to the end of the ordering, so a single dominant taste tag can't carpet-bomb the row.

### Empty-profile path

When the taste profile has no entries or no scored tags, scoring is bypassed and the pool returns with only the enabled filters applied: same effect as setting *Recommendation style* fully toward *Popular*.

## Full-screen browse (See all) *(Reikai)*

The 30-cap carousel only shows a fraction of what gets fetched. Many sources combined with tracker fan-out routinely produce 100–200 candidates that never reach the visible row. When the underlying pool has more than fits in the row, a **See all** text link appears in the carousel's header, next to the "Related" title; tapping opens a dedicated full-screen grid showing the full ranked pool with no cap.

### What the browse view shows

Same ordering rules as the carousel (taste-aware rerank, the enabled filters, exploration slots, diversity cap) applied to the full pool instead of the merged-30 slice. The grid scales column count to screen width: phones stay at 3 columns, foldables (unfolded) reach ~6, tablets land in between.

### Bulk selection

Long-press a card to enter selection mode. The action toolbar exposes:

- **Add to library**: applies a single category set to every selected card. Honors your *Default category* preference (the default category, or "Always ask", or "Last-used categories", as configured).
- **Select all**: selects every card in the grid.
- **Invert selection**: flips the selection state of every card.

Tracker-origin candidates are skipped on bulk-add (their links don't map to any installed extension); the completion toast reports how many were skipped: *"Added 5 to library: 2 skipped (tracker recommendations)"*.

Duplicate-library detection (the per-item "this is already in your library, want to migrate?" prompt the single-add path uses) is deliberately skipped for bulk. The workflow here is "browse and add quickly" rather than careful per-item curation.

## Settings

*Settings → Library → Recommendations.*

Five sections:

### Recommendation sources *(Komikku baseline)*

- **Tracker-backed recommendations** *(master, default on)*: turn the entire tracker-recommendation stream off without affecting source-native + keyword-search results.
- AniList / MyAnimeList / MangaUpdates / Shikimori *(per-tracker, each default on)*: toggle each tracker independently. Sub-toggles disable when the master toggle is off.

### Taste profile *(Reikai)*

- **Pull library from these trackers**: per-tracker toggles for AniList / MyAnimeList / Kitsu / Shikimori / Bangumi. All default off; a tracker's toggle is shown only once you're logged into it (the pull reads your private library).
- **Auto-refresh tracker library**: `Never` (default) / `every 7 days` / `every 30 days`.
- **Refresh now**: manual refresh button. 60 s cooldown after each press.
- **Last refresh**: per-tracker timestamp summary line.

### Candidate injection *(Reikai)*

- **Tag search on current source** *(default on)*: runs your top-3 taste-profile tags as searches on the current source.
- **Cross-recommendation from favorites** *(default on)*: runs the tracker-graph cross-recommendation lookup.

### Reranking *(Reikai)*

- **Rerank by taste** *(default on)*: gates the scoring pipeline. When off, the carousel keeps its arrival ordering (filters still apply).
- **Recommendation style** *(slider, default Mostly popular)*: `[Popular only, Mostly popular, Balanced, Mostly personalized, Pure personalized]`. Disabled when *Rerank by taste* is off.
- **Serendipity** *(slider, default Mostly familiar)*: `[Familiar, Mostly familiar, Balanced, Adventurous, Very adventurous]`. Controls exploration-slot count and novelty bonus magnitude. Disabled when *Rerank by taste* is off.

### Filters *(Reikai)*

All filters default off (opt-in). With every toggle off, nothing is hidden from your suggestions.

- **Hide already-tracked** *(default off)*: hides candidates whose tracker status is Reading or Completed.
- **Hide on-hold** *(default off)*: hides candidates marked On-hold on a tracker.
- **Hide plan-to-read** *(default off)*: hides candidates marked Plan-to-read on a tracker.
- **Hide dropped** *(default off)*: hides candidates marked Dropped on a tracker.
