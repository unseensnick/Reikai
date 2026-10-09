---
paths:
  - "ROADMAP.md"
  - "docs/dev/plans/**"
  - "docs/dev/parked.md"
  - "docs/dev/subsystems/**"
---

# Roadmap & plan docs

Two artifacts hold the forward plan. Keep them separate: the roadmap is the terse what-and-when; the plan docs are the how-and-why.

## `ROADMAP.md` (tracked, the single forward backlog)

**Forward-looking only.** It holds what is *left* to build, never what already shipped. Structure, top to bottom:

**Keep an item to its two sentences, and put the rest in the plan doc** (owner, 2026-09-20). An item that has grown a paragraph of mechanism, measurements or caveats has become a plan; move that prose into the linked plan doc's Approach or Decisions, leave the what and the size tag, and link. The same applies to a parked entry, which belongs in `docs/dev/parked.md` rather than here at any length.

**The drift happens inside an item, not in the item list.** Nobody adds a shipped feature as a new bullet, so the list-level rule holds on its own. What slips is prose: an item legitimately explains why it is gated, a later edit appends what has since landed, and two edits on the item reads as a progress log with one forward sentence at the end. Test each sentence separately, not the item. A sentence earns its place only if it describes work not yet done, or a constraint that shapes that work. A sentence whose subject is Reikai's own completed work belongs in CHANGELOG.md or the subsystem doc, even when it is true and even when it explains the item; past tense about something else (upstream, a reference fork, a measurement) is a constraint and stays. **Parked / not building is exempt**, because "the cheaper thing shipped instead" is often the whole reason an item is parked, and cutting it leaves the entry unreadable. Rewrite `all eight Metro phases have landed, but the reader still resolves eighteen types` as `the reader still resolves eighteen types`, and let CHANGELOG.md and git carry what landed. This is convention, not linted: a word-list check was measured against the current file and flagged 6 of 79 items with half of them legitimate, too noisy to block a commit on.

1. **Intro**: two lines pointing to `CHANGELOG.md` (what shipped), `docs/dev/subsystems/` (how things work), `docs/dev/plans/` (design in flight), `Handoff.md` (session state), and this file (format).
2. **Now** (in progress), **Next** (queued, in priority order), **Later** (backlog). Each item: a bold title, a size tag (`[S]` / `[M]` / `[L]` / `[XL]`, one tag, never a range), and up to two sentences of "what", plus a link to its plan doc when one exists. No inline plans: the detail lives in the plan doc.
3. **Later is grouped by stable area** (Library, Reader, Novels, Recommendations, adult sources, ...), never by phase. Phases are a plan artifact and rot; areas are durable. Only include areas that have open items.
   - **One sanctioned exception to the item format: an "Opportunistic polish" list.** An area may end with a short list of one-line micro-items with no bold title and no size tag, each bundling several unrelated scraps too small to size (`Browse: Latest shortcut, hide-in-library, per-row language`). A size tag means nothing until such a line is split, and splitting it would triple the file for work nobody has committed to. Anything that grows a plan doc, a gate, or a dependency leaves the list and becomes a real item.
4. **Parked and not building**: a pointer to [docs/dev/parked.md](../../docs/dev/parked.md) and nothing else. Parked items are not forward work, and their entries are where the verbosity collected: what it is, why it is parked, the revive trigger and the evidence behind the ruling, three to six sentences each across about fifty items (owner, 2026-09-20, moving them out). An entry there may name sources, since it is a dev record. Reviving one brings a single roadmap-format line back and deletes the entry.

**No Status table, no Shipped section, no audit prose in this file.** What shipped is in CHANGELOG.md's version sections and the release tags; how it works is in `docs/dev/subsystems/`, decisions included. Audit reports live in `docs/dev/audits/` (local / gitignored; only their action items become roadmap lines).

**Naming (enforced):** `ROADMAP.md` is a semi-public surface, so it stays generic about content sources: use an approved shorthand (`EH` / `ExH` / `MD` / `CMK`) or collective phrasing ("the built-in adult sources"), never a full source name, adult (`nhentai`, `pururin`, ...) or mainstream (`mangadex`, `comick`). Trackers (MangaUpdates, Shikimori, AniList, ...) are not content sources and stay named. The dev-record files (`docs/dev/subsystems/`, `docs/dev/plans/`, local `docs/dev/audits/`) may name sources freely. This mirrors the CHANGELOG rule (see [workflow.md](workflow.md) "Public-facing naming"); the enforced deny-list lives in `scripts/lint-docs.sh`, which both the `pre-commit` hook and `docs-lint` CI call (extend it there when a new source is named).

**Other rules:** never paste an implementation plan into the roadmap; convert relative dates to absolute; no em dashes; `Roadmap N` (never a bare `#N`), a real issue/PR uses `owner/repo#N`. A `pre-commit` hook + the `docs-lint` CI enforce the three hard rules on `ROADMAP.md`: no content-source names, no em dash, no bare `#N`. Structural rules (item length, size tags, area grouping) are convention, not linted; review catches them.

## `docs/dev/plans/` (tracked, design in flight)

**Current behaviour lives in [docs/dev/subsystems/](../../docs/dev/subsystems/README.md)**, one reference doc per subsystem, rewritten in place. A plan is for in-flight design: once the work lands, its lasting content moves into the subsystem doc.

A **substantial** piece of work still being designed or built gets one markdown here. When it lands, its lasting content (how it works, invariants, decisions with their reasons) is folded into the matching subsystem doc and the plan is deleted; git keeps the history. A plan never outlives its work, so this folder stays small.

**Template** (every plan doc follows it):

- **Goal**: one or two sentences, what this delivers for the user.
- **Why**: the motivation, the parity gap, or the constraint that made it worth building.
- **Approach**: how it will work, in plain English first, then the mechanism.
- **Key files**: the entry points a developer would open first. **Cite a path plus the symbol a reader can grep for (`LibraryViewModel.kt`, `applyGrouping`), never a line number.** A `:NNN` is stale the next time anything above it moves, and a stale one is worse than none because it reads as precise. The same holds in the body of a plan doc. (Conversation is different: an inline `file:line` you just read is the evidence a claim is grounded, and CLAUDE.md's cite-before-you-claim rule asks for it there.)
- **Open questions**: what still needs deciding, each with its options.
- **Decisions & tradeoffs**: the choices made and what was deliberately left out.

Naming: real descriptive names (`novel-reader.md`), never generated slugs. `docs/dev/plans/README.md` indexes every open plan with a one-line hook.

**What does NOT go here:** bug-fix plans, polish batches, scouting / audit reports, doc-edit plans, and superseded drafts stay **local** (the session plan archive), out of the repo, so `docs/dev/plans/` holds only work in flight.
