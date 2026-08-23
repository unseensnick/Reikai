---
alwaysApply: true
---

# Code Quality

## Coding principles (project-wide)

- **DRY**: Before adding a helper, search the codebase (or run an Explore agent in plan mode) for an existing equivalent.
- **YAGNI**: Only add what the current task requires. No speculative APIs, optional parameters, or abstractions for hypothetical callers.
- **KISS**: Prefer the simplest correct solution. Complexity must be justified by concrete requirements, not elegance or anticipated scale.
- **Minimal blast radius, measured against the defect and not the diff**: a bug fix changes only what's broken, and it changes it everywhere it is broken, not only at the site that reproduced. Grep for sibling sites before calling a fix done, and name any you deliberately left. A feature adds only what's specified, with one standing exception: a user-visible change specified for one content type is specified for both, per the write-once rule in [content-layer.md](content-layer.md). Leave working surrounding code untouched.
- **No standalone refactor sprints**: Refactor incrementally alongside the feature or fix that motivated it. Never propose a separate "cleanup pass" unless the user asks. **Standing exemption:** the content-layer program (the manga/novel collapse) is an owner-approved refactor initiative, so work under it needs no motivating feature. Nothing else is exempt.

## Anti-defaults

- No premature abstractions. Three similar lines beat a helper used once. A rule that must hold for both content types is never "used once": it has two callers by definition, so it belongs in one kernel.
- Don't add features or improvements beyond what was asked. The second content type is not "beyond what was asked" (see write-once above).
- Don't refactor adjacent code while fixing a bug.
- No dead code or commented-out blocks. Git has history.
- Comments: see "Comments" below.
- No plan/roadmap codename markers in code comments (`Phase N`, the `P5 S5` phase/slice shorthand, `Y3` Yōkai-era feature refs, `R3` roadmap refs, `Active #N`, or a plan-style `Step 3` reference). They rot as the plan moves on and become noise that has to be stripped later. State the durable fact instead. A colon-led algorithm step (`Step 1:`) is fine. Enforced by the `pre-commit` hook and mirrored tree-wide by `docs-lint` CI, both of which spare `R8` (the code shrinker) and `M3` (Material 3). The step match is case-insensitive: a lower-case `step 2` reads exactly as a plan reference and used to slip through.
- No em dashes (—) in prose, comments, commit messages, or PR bodies. Use commas, parentheses, periods, or colons. Em dashes are a Claude stylistic tic that flags writing as AI-generated.
- No AI-generated watermarks. Don't add "Co-Authored-By: Claude", "Generated with Claude Code", robot emoji footers, or similar tags to commits, PRs, code, or docs.

## Comments (KDoc, docstrings, inline)

Short, concise, useful: as brief as possible **without losing vital info**. Vital info is whatever saves the next reader from a bug or a wrong "fix": an invariant the types can't express, a cross-file or cross-type coupling, a deliberate divergence from upstream, a trap that already bit once. Never cut a vital fact to make a comment shorter; never pad a trivial one.

- **The ban is restating the adjacent code.** A comment a reader with the code open learns nothing from is dead weight; rename the symbol instead if it needed explaining.
- **WHY is the highest-value content**: why this approach, why not the obvious alternative, what breaks otherwise.
- **WHAT is allowed, and often required, when the what is not visible in the code at hand**: an invariant, the behavior of an upstream or foreign dependency, what a magic value means, how this piece couples to a distant one. `EntryId.kt`'s id-space KDoc is the model, not a violation.
- **Never a wall of text.** An explanation that needs paragraphs belongs in a plan/dev doc; the comment states the rule and points there. The line is drawn below.
- Reserve KDoc for module boundaries (public APIs of `source-api`, repository interfaces) and genuinely non-obvious classes, not every internal function.

### The length cap

**8 lines. A 9th is rejected by the `pre-commit` hook.** The unit is a run of consecutive comment lines, so a KDoc header and an inline paragraph are each measured whole. Scoped to the Reikai-owned trees (`reikai/`, `exh/`); Mihon's own files keep upstream shape, and the `// RK` islands in them already sit far under this. The number is not arbitrary: 97% of Mihon's 1797 comment blocks already fit in 8 lines, so this codifies the surrounding style rather than departing from it.

A KDoc tag list (`@param`, `@return`, `@property`) stops the count for the rest of its block: tags are an enumeration keyed to the signature, not narrative, and a function with six documented parameters is not the wall this cap is aimed at. Tags come last in a KDoc, so the prose above them is still measured in full. This is not a loophole to route prose through: a `@param` whose text is three sentences of rationale is the same wall, and belongs in the plan doc like any other.

**The cap is a ceiling almost nothing should approach, not a budget to spend.** Most comments are one to three lines and belong that way. Never lengthen a comment because there is room left, never add a comment to a line that did not need one, and never split one long comment into two capped ones with a blank line between them: that is the same wall of text, hiding from the hook.

**A comment that wants more room is telling you something.** Usually the code needs the work, not the comment: a name that explains itself, a function split at the seam the comment was describing, a type that makes the invariant unstateable-wrong. Reach for that first.

**When the explanation is genuinely irreducible, it splits in two places.** The part that stops the next reader from writing a bug (the invariant, the coupling, the trap that already bit, the deliberate divergence from upstream) stays in the code as a sentence or two. The narrative (why this approach, what else was tried, what broke, how the design got here) moves into the feature's record in `docs/dev/plans/`, and the comment points at it by filename. If no such record exists, that is the signal to write one, not to keep the paragraph in the source.

Measure a tree with `pwsh scripts/comment-census.ps1 -Roots app/src/main/java/reikai`. A file above roughly 30% comments is a smell worth a look, not a hook failure.

### No calendar dates in code comments

**A comment never carries a date** (`2026-08-23`, "measured in August"). Git already records when a line was written, and a date in the source reads as an expiry the code does not have: the next reader cannot tell whether a two-year-old note is stale or simply still true. State the durable fact instead, and let the feature's record in `docs/dev/plans/` carry when something was measured and who ruled on it, which is what those docs are for. Enforced by the `pre-commit` hook on added comment lines in `.kt` / `.kts` / `.sq` / `.sqm`; only `20xx-MM-DD` is matched, so a format string (`yyyy-MM-dd`) and an epoch (`1970-01-01`) are spared. Attribution follows the same rule: `(owner, 2026-08-22)` belongs in the plan doc, not in the source.

## Naming (Kotlin)

- Files: PascalCase matching the primary class (`LibraryViewModel.kt`, `LibraryTab.kt`).
- Classes/objects: PascalCase. Functions/properties: camelCase. Constants: SCREAMING_SNAKE.
- Booleans: `is` / `has` / `should` / `can` prefix. Predicates: `is*` / `has*`. Factories: `create*`. Converters: `to*`.
- Composables: PascalCase verb-less noun (`MangaCard`, `SourceChipRow`).
- Acronyms as words: `userId`, `httpClient`, not `userID` / `HTTPClient`.

## Code Markers

`TODO(author): desc (#issue)` for planned work. `FIXME(author): desc (#issue)` for known bugs. `HACK(author): desc (#issue)` for ugly workarounds (explain the proper fix). `NOTE: desc` for non-obvious context. Owner + issue link required. Never `XXX`, `TEMP`, `REMOVEME`.

## File organization

- Imports: stdlib/jvm, kotlinx/androidx/material, third-party, project (`eu.kanade.*` / `tachiyomi.*` / `mihon.*`). Blank line between groups. No star imports (per `.editorconfig`).
- One top-level class per file when it's a UI/presenter/screen. Small data classes can co-locate.
- Function order in classes: public API first, then private helpers in call order.
- Compose `@OptIn` annotations: propagate at the call site, don't suppress globally.
