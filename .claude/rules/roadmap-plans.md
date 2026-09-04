---
paths:
  - "ROADMAP.md"
  - "docs/dev/plans/**"
  - "docs/dev/shipped.md"
---

# Roadmap & plan docs

Two artifacts hold the forward plan. Keep them separate: the roadmap is the terse what-and-when; the plan docs are the how-and-why.

## `ROADMAP.md` (tracked, the single forward backlog)

**Forward-looking only.** It holds what is *left* to build, never what already shipped. Structure, top to bottom:

**The drift happens inside an item, not in the item list.** Nobody adds a shipped feature as a new bullet, so the list-level rule holds on its own. What slips is prose: an item legitimately explains why it is gated, a later edit appends what has since landed, and two edits on the item reads as a progress log with one forward sentence at the end. Test each sentence separately, not the item. A sentence earns its place only if it describes work not yet done, or a constraint that shapes that work. A sentence whose subject is Reikai's own completed work belongs in the plan doc's Status or in `shipped.md`, even when it is true and even when it explains the item; past tense about something else (upstream, a reference fork, a measurement) is a constraint and stays. **Parked / not building is exempt**, because "the cheaper thing shipped instead" is often the whole reason an item is parked, and cutting it leaves the entry unreadable. Rewrite `all eight Metro phases have landed, but the reader still resolves eighteen types` as `the reader still resolves eighteen types`, and let the linked plan doc carry what landed. This is convention, not linted: a word-list check was measured against the current file and flagged 6 of 79 items with half of them legitimate, too noisy to block a commit on.

1. **Intro**: two lines pointing to `docs/dev/shipped.md` (done-log), `docs/dev/plans/` (detail), `Handoff.md` (session state), and this file (format).
2. **Now** (in progress), **Next** (queued, in priority order), **Later** (backlog). Each item: a bold title, a size tag (`[S]` / `[M]` / `[L]` / `[XL]`, one tag, never a range), and up to two sentences of "what", plus a link to its plan doc when one exists. No inline plans: the detail lives in the plan doc.
3. **Later is grouped by stable area** (Library, Reader, Novels, Recommendations, adult sources, ...), never by phase. Phases are a plan artifact and rot; areas are durable. Only include areas that have open items.
   - **One sanctioned exception to the item format: an "Opportunistic polish" list.** An area may end with a short list of one-line micro-items with no bold title and no size tag, each bundling several unrelated scraps too small to size (`Browse: Latest shortcut, hide-in-library, per-row language`). A size tag means nothing until such a line is split, and splitting it would triple the file for work nobody has committed to. Anything that grows a plan doc, a gate, or a dependency leaves the list and becomes a real item.
4. **Parked / not building**: up to three sentences per item: what it is, why it's parked, and the revive trigger, with a link if there's a plan/decision doc. Longer rationale goes in the plan doc, not here.

**No Status table, no Shipped section, no audit prose in this file.** Shipped work moves to [docs/dev/shipped.md](../../docs/dev/shipped.md): a terse done-log grouped by durable area (never by phase), each area free to carry sub-sections, plus a releases table mapping each version to what it carried. A line cites whatever identifies the work best: a commit short-SHA(s), a `(version)` parenthetical for the release it shipped in, and/or a link to its plan doc for anything with a full record. It is a dev record, so it *may* name sources. Audit reports live in `docs/dev/audits/` (local / gitignored; only their action items become roadmap lines). Decisions and rationale live in `docs/dev/plans/`.

**Naming (enforced):** `ROADMAP.md` is a semi-public surface, so it stays generic about content sources: use an approved shorthand (`EH` / `ExH` / `MD` / `CMK`) or collective phrasing ("the built-in adult sources"), never a full source name, adult (`nhentai`, `pururin`, ...) or mainstream (`mangadex`, `comick`). Trackers (MangaUpdates, Shikimori, AniList, ...) are not content sources and stay named. The dev-record files (`docs/dev/shipped.md`, `docs/dev/plans/`, local `docs/dev/audits/`) may name sources freely. This mirrors the CHANGELOG rule (see [workflow.md](workflow.md) "Public-facing naming"); the enforced deny-list lives in `scripts/lint-docs.sh`, which both the `pre-commit` hook and `docs-lint` CI call (extend it there when a new source is named).

**Other rules:** never paste an implementation plan into the roadmap; convert relative dates to absolute; no em dashes; `Roadmap N` (never a bare `#N`), a real issue/PR uses `owner/repo#N`. A `pre-commit` hook + the `docs-lint` CI enforce the three hard rules on `ROADMAP.md`: no content-source names, no em dash, no bare `#N`. Structural rules (item length, size tags, area grouping) are convention, not linted; review catches them.

## `docs/dev/plans/` (tracked, implementation & decision records)

A **substantial** feature or initiative gets one markdown here: a developer-facing record of what was built and why. Distinct from the architecture references already in `docs/` and `docs/dev/` (`multi-source.md`, `related-mangas.md`, `guides/tracking.md`, `ln-plugin-host.md`, etc.): cross-link those, do not duplicate them. One doc per feature; fold superseded iterations of the same feature into its single doc.

**Template** (every plan doc follows it):

- **Goal**: one or two sentences, what this delivers for the user.
- **Why**: the motivation, the parity gap, or the constraint that made it worth building.
- **Approach**: how it works now, in plain English first, then the mechanism. Describe current behavior, never the journey ("we tried X then switched to Y").
- **Key files**: the entry points a developer would open first. **Cite a path plus the symbol a reader can grep for (`LibraryViewModel.kt`, `applyGrouping`), never a line number.** A `:NNN` is stale the next time anything above it moves, and a stale one is worse than none because it reads as precise. The same holds in the body of a plan doc. (Conversation is different: an inline `file:line` you just read is the evidence a claim is grounded, and CLAUDE.md's cite-before-you-claim rule asks for it there.)
- **Status**: shipped / in progress / deferred, with commit short-SHA(s).
- **Decisions & tradeoffs**: the choices made and what was deliberately left out.

Naming: real descriptive names (`novel-reader.md`, `manga-details-parity.md`), never generated slugs. `docs/dev/plans/README.md` indexes every doc with a one-line hook.

**What does NOT go here:** bug-fix plans, polish batches, scouting / audit reports, doc-edit plans, and superseded drafts stay **local** (the session plan archive), out of the repo, so `docs/dev/plans/` holds only durable feature records.
