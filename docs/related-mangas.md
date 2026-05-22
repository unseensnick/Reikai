# Related-mangas carousel

A horizontal carousel of similar / related titles, shown below the description on the manga details screen. Pulls from five independent input streams and merges them into one row, so a single fast input can't dominate the result list and the same manga can't appear twice via different streams.

Base feature ported from [Komikku](https://github.com/komikku-app/komikku); Reikai adapts it into a single merged carousel and layers a taste-profile-driven personalization layer on top — extra candidates based on what you already track, plus a rerank step that reorders the row against your taste and drops manga already in your library.

## Where it appears

*Manga details → below the description, above the start-reading button.*

The carousel renders as a horizontal row. While loading, a skeleton placeholder holds the slot so the rest of the screen doesn't jump when results arrive. Once results land, the skeleton swaps out and the carousel populates; if nothing comes back, the section hides entirely instead of leaving an empty row.

The fetch fires once when the screen first attaches, then caches for the lifetime of the screen. Switching source via the chip row re-runs the full fetch against the new source.

## Where candidates come from

Five independent streams feed the same pool. The first three are the Komikku-equivalent baseline; the last two are the Reikai personalization layer.

### 1. Source-native related-manga API

Sources that ship a "related manga" hook expose those entries directly. Most HTTP-backed extensions (~377 in the Keiyoushi catalog) are opted in by default; a small number opt out explicitly. Sources whose details page has no parseable listing simply contribute nothing.

### 2. Keyword-search fallback

The current manga's title is split into keywords and each keyword runs through the source's standard search endpoint. Results stream in as each keyword completes, so the carousel fills incrementally rather than waiting for all keywords to finish.

### 3. Tracker recommendations

Three public tracker endpoints, each running without a tracker sign-in:

- **AniList** — `Media.recommendations`
- **MyAnimeList** — Jikan's `/manga/{id}/recommendations`
- **MangaUpdates** — community recommendations on the series record

Per tracker: if you already have a track entry for the current manga, the existing remote id is used directly. Otherwise a title-search resolves the id first, then the recommendation fetch runs. So tracker-backed recommendations work even on untracked manga.

### 4. Tag search on current source *(Reikai)*

Your top three taste-profile tags (see [Taste profile](#taste-profile) below) run as searches against the current source. Stays on the current source — no source-switching while reading. Limitation: depends on the source supporting tag-style search; on sources that only do title search this contributes nothing.

Gated by Settings → Library → Recommendations → Candidate injection → **Tag search on current source** *(default on)*.

### 5. Cross-recommendation from favorites *(Reikai)*

Your top-rated tracked manga (capped at the top 5) are looked up on the current source by title. For each match, that manga's native related-mangas list is fetched and pushed into the pool. Heavier (more network calls) but higher precision: surfaces matches you've already implicitly endorsed.

Falls back gracefully when a favorite isn't on the current source — that favorite is just skipped. Also skipped silently when the taste profile is empty.

Gated by Settings → Library → Recommendations → Candidate injection → **Cross-recommendation from favorites** *(default on)*.

## Taste profile

A weighted set of tags derived from your tracked manga across the configured trackers. The tag-search and cross-recommendation streams (#4 and #5) consume it.

### Cache lifecycle

The tracker library is cached locally so the recommendations row doesn't refetch from each tracker on every screen open. Refresh paths:

- **Manual** — Settings → Library → Recommendations → **Refresh now** (60 s cooldown after each press).
- **Event-driven** — when you add or update a track entry from inside the app, the cache row updates in place.
- **Optional auto-refresh** — Settings dropdown: `Never` (default) / `7 days` / `30 days`. Opt-in for users who want a fail-safe.

The cache rides the regular app backup pipeline, so it survives a backup-and-restore.

### Trackers in scope

AniList, MyAnimeList, Kitsu. MangaUpdates, Shikimori, Bangumi, and self-hosted Komga / Kavita / Suwayomi are not used as taste-profile sources.

### Tag scoring

Each tag's score combines your rating of the manga tagged with it and the manga's tracker status (Completed and Reading weigh positive, Dropped weighs negative, Plan-to-read is signal-free). The three highest-scoring tags become the top-3 used by the tag-search stream.

## How clicks work

- **Source-origin card** (came from source-native or keyword-search) — opens the manga's detail page in the current source.
- **Tracker-origin card** (came from one of the three trackers) — opens Global Search pre-filled with the manga's title, so you can pick an installed source to read it on. Tracker links don't belong to any installed extension, so the Global Search detour avoids creating an orphan library row.

## Pool composition

A single deduplicated pool, capped at 30 entries in the carousel:

- **Title-level dedup** — the same manga arriving via source-native, a tracker, and a favorite's related list collapses to one card. Source-origin entries win ties since they route directly to the reader on tap.
- **Tracker slot reservation** — up to 12 of the 30 slots are reserved for tracker-origin entries and distributed round-robin across the three trackers, so a fast-returning tracker can't eat the whole reserve before the slower ones respond. Empty trackers cede their share to source-origin entries, and vice versa.

## Reranking *(Reikai)*

Once the merged pool is assembled, source-origin candidates are reordered against your taste profile and any manga already in your library is dropped. Tracker-origin entries keep their fair-share ordering — their recommendations are already personalized by construction.

### Anti-echo (always on, narrowed by status filters)

Candidates that match a library entry are dropped before scoring. Runs independently of the rerank toggle: turning *Rerank by taste* off keeps the filter active. Which library entries count as "hide" is governed by your [filter prefs](#filters-y2k) — see below.

### Scoring

Each source-origin candidate is scored on three axes that the Reranking sliders (see [Settings](#settings) below) blend:

- **Popularity** — where the candidate landed in the pool's arrival order. Sources surface their most-relevant matches first, so arrival order is itself a useful signal.
- **Taste** — the average taste-profile weight of the candidate's tags. Untagged candidates score neutral and land near the popularity-only ordering.
- **Novelty boost** — small bonus for tags you have few tracked entries for. Encourages discovery without overwhelming the row.

The **Recommendation style** slider controls how much the taste / novelty axes matter relative to popularity. **Serendipity** controls how big the novelty bonus is *and* reserves a fraction of slots that keep popularity order regardless of taste — a built-in guard against echo collapse at the *Personalized* end.

### Diversity cap

No more than two of the taste-ranked picks may share the same dominant tag. Offenders are pushed to the end of the slot allocation, so a single dominant taste tag can't carpet-bomb the row.

### Empty-profile path

When the taste profile has no entries or no scored tags, scoring is bypassed and the pool returns with only anti-echo applied — same effect as setting *Recommendation style* fully toward *Popular*.

## Full-screen browse (See all) *(Reikai)*

The 30-cap carousel only shows a fraction of what gets fetched — many sources combined with tracker fan-out routinely produce 100–200 candidates that never reach the visible row. A trailing "See all (N)" card is appended to the carousel when the underlying pool has more than 30 entries; tapping opens a dedicated full-screen grid showing the full ranked pool with no cap.

### What the browse view shows

Same ordering rules as the carousel — taste-aware rerank, anti-echo, exploration slots, diversity cap — applied to the full pool instead of the merged-30 slice. The grid scales column count to screen width: phones stay at 3 columns, foldables (unfolded) reach ~6, tablets land in between.

### Bulk selection

Long-press a card to enter selection mode. The action toolbar exposes:

- **Add to library** — applies a single category set to every selected card. Honors your *Default category* preference (the default category, or "Always ask", or "Last-used categories", as configured).
- **Select all** — selects every card in the grid.
- **Invert selection** — flips the selection state of every card.

Tracker-origin candidates are skipped on bulk-add (their links don't map to any installed extension); the completion toast reports how many were skipped: *"Added 5 to library — 2 skipped (tracker recommendations)"*.

Duplicate-library detection (the per-item "this is already in your library, want to migrate?" prompt the single-add path uses) is deliberately skipped for bulk. The workflow here is "browse and add quickly" rather than careful per-item curation.

## Settings

*Settings → Library → Recommendations.*

Five sections:

### Recommendation sources *(Komikku baseline)*

- **Tracker-backed recommendations** *(master, default on)* — turn the entire tracker-recommendation stream off without affecting source-native + keyword-search results.
- AniList / MyAnimeList / MangaUpdates *(per-tracker, default on)* — toggle each tracker independently. Sub-toggles disable when the master toggle is off.

### Taste profile *(Reikai)*

- **Pull library from these trackers** — per-tracker toggles for AniList / MyAnimeList / Kitsu. Default off; greyed out unless the tracker is logged in.
- **Auto-refresh tracker library** — `Never` (default) / `every 7 days` / `every 30 days`.
- **Refresh now** — manual refresh button. 60 s cooldown after each press.
- **Last refresh** — per-tracker timestamp summary line.

### Candidate injection *(Reikai)*

- **Tag search on current source** *(default on)* — runs your top-3 taste-profile tags as searches on the current source.
- **Cross-recommendation from favorites** *(default on)* — runs your top-5 favorites' related-mangas lists on the current source.

### Reranking *(Reikai)*

- **Rerank by taste** *(default on)* — gates the scoring pipeline. When off, the carousel keeps its arrival ordering (filters still apply).
- **Recommendation style** *(slider, default Mostly popular)* — `[Popular only, Mostly popular, Balanced, Mostly personalized, Pure personalized]`. Disabled when *Rerank by taste* is off.
- **Serendipity** *(slider, default Mostly familiar)* — `[Familiar, Mostly familiar, Balanced, Adventurous, Very adventurous]`. Controls exploration-slot count and novelty bonus magnitude. Disabled when *Rerank by taste* is off.

### Filters *(Reikai)*

- **Hide already-tracked** *(default on)* — drop library candidates whose tracker status is Reading, Completed, or Unknown.
- **Hide dropped** *(default on)* — drop library candidates marked Dropped on a tracker. Self-hosted Komga / Kavita / Suwayomi don't model "dropped" at all, so this toggle has no effect for those entries.

Library entries with no tracker info at all are hidden regardless of these toggles. Plan-to-read and on-hold entries are never hidden — those are "reminder" statuses. Turning both filters off plus *Rerank by taste* on with no taste profile means library entries appear in suggestions; the escape hatch is to flip *Rerank by taste* off, which restores the blanket-hide behavior.
