# Documentation system

## Goal

Make the docs answer two questions fast: where does a new doc go, and which docs do I update for a given change. A contributor (or the owner guiding an agent) should reach the right file from the file tree or a one-line pointer, without opening ten docs to infer the system.

## Why

The docs grew into three tiers (user docs in `docs/`, dev process/reference in `docs/dev/`, per-feature records in `docs/dev/plans/`) but only the plans tier had an index. A single topic was split across tiers with one-way cross-links (plan docs linked up to the user doc, the user doc linked down to nothing), so "what do I update for categories" had no discoverable answer. The file-to-file workflow (finish a feature, then touch CHANGELOG + shipped + plan + ROADMAP; sync Mihon, then the ledger) lived only in `.claude/rules/`, which a human never opens. And two docs recorded the Mihon frontier, which had already drifted (`shipped.md` said `ef1c52967` while the ledger was four syncs newer).

## Approach

A routing map plus a few targeted collapses. The docs mostly own distinct questions, so the fix is to name the ownership and route to it.

- **Two front doors.** [`docs/README.md`](../../README.md) states the three tiers and carries the **topic map** (feature area to user doc + dev records). [`docs/dev/README.md`](../README.md) splits the dev docs into **process/records** and **architecture/reference**, gives each its one job, and holds the **file-to-file workflow** table plus a who-owns-which-fact section.
- **Back-links.** Each user doc gained a one-line `Dev records:` pointer, so navigation is two-way.
- **Single owner per fact.** The Mihon frontier lives only in the [upstream-sync.md](../upstream-sync.md) ledger; `shipped.md`'s frozen-SHA summary was removed and made a pointer. `development.md`'s porting section became a pointer to the two owners instead of a duplicate.
- **No mass rename.** Encoding tier in filenames (`X.plan.md`) was considered and rejected: ~38 renames breaking ~100 inbound links, for a glance benefit the folder plus the maps mostly already give.

## Key files

- `docs/README.md`, `docs/dev/README.md` (new front doors).
- `docs/dev/plans/README.md` (pre-existing, already good; the model the two new indexes follow).
- The seven user docs (back-link line each); `docs/dev/development.md` and `docs/dev/shipped.md` (de-duplicated).

## Status

Shipped. Phase 1 (front doors + topic map + back-links) and Phase 2 (slim `development.md`, de-drift `shipped.md`, this record). A per-doc content-freshness sweep across the remaining docs is the optional Phase 3.

## Decisions & tradeoffs

- **`ln-plugin-host.md` stays in `docs/dev/`.** It is deliberately an architecture reference (paired with the `novel-plugin-host.md` decision record), not a misplaced plan doc; the project's own rules already classify it that way. Only `tracker-aware-duplicate-detection.md` was a genuine orphan, resolved by indexing it.
- **Filename tier-encoding deferred.** The editor tab / bare-basename glance is the one spot folders do not reach; if it keeps biting, a rename is the fallback, kept out of scope here to avoid the link churn.
- **`shipped.md` stays a terse pointer to the plan docs.** The densest entries were trimmed to the "one line + short-SHA + plan link" contract; the full record is the plan doc it links to.
- **CHANGELOG and ROADMAP untouched**, by request.
