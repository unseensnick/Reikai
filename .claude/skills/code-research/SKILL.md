---
name: code-research
description: Deep, fan-out research over the codebase to answer a big question (test gaps, how a subsystem works end to end, a risk/security/dead-code audit, "where does X happen everywhere"). The codebase analog of deep web research: parallel explorer agents gather file:line-cited findings, surprising or high-stakes claims are adversarially verified against current code, and the result is a single prioritized report. Use for broad/deep questions that span many files and modules, NOT a one-file lookup and NOT a single-task pre-plan (use /scout for that, /explain for one thing).
argument-hint: "<research question> (e.g. 'where are our unit-test gaps', 'how does backup/restore work end to end', 'audit the EXH subsystem for risk')"
disable-model-invocation: false
allowed-tools:
  - Bash(git *)
  - Glob
  - Grep
  - Read
  - Agent
---

# code-research

The codebase analog of the web `deep-research` harness. Where deep-research fans out web searches, fetches sources, fact-checks claims, and synthesizes a cited report, this fans out **code exploration**, reads **files**, verifies claims against **current code**, and synthesizes a report cited by **`file:line`** instead of URLs.

**This skill never edits code.** It produces a findings report. Acting on it (writing tests, fixing bugs, refactoring) happens after, as a separate step the user approves.

## When to use

- Broad/deep questions spanning many files or whole subsystems: "where are our test gaps", "how does the merge engine work end to end", "audit the adult-source subsystem", "where is untrusted input handled", "what's dead code".
- Audits where you want coverage AND confidence (adversarial verification), not just a quick pointer.

## When NOT to use

- A single fact or one-file lookup. Just read it.
- Pre-planning ONE concrete task (port this screen, add this feature). Use `/scout`, it is lighter and task-scoped.
- Explaining one already-located thing. Use `/explain`.

If invoked for something trivial, push back once and suggest the lighter tool.

## Method

### 1. Scope and clarify

Echo the research question in one sentence so the user can correct a misread before agents spend tokens. If it's vague (which subsystem? what aspect? what counts as a "gap"/"risk"?), use `AskUserQuestion` to narrow, do not fan out on a fuzzy question. Decide the **angle of decomposition** (by module, by data flow, by concern) and the **inclusion bar** (what counts as a finding vs noise).

### 2. Map the terrain (cheap reads, main thread)

Before spawning anything, inventory on the main thread so the subagents are briefed well and don't rediscover the obvious:

- The relevant files/modules (`Glob`, `Grep`), and for test questions, the existing test inventory (so agents don't re-flag covered ground).
- `git log --oneline -20` for recent shape; `Handoff.md` / `.claude/rules/*` / plan docs for known constraints and deliberate descopes (the most common audit failure is flagging deferred work as missing).
- Note what is already covered / out of scope, verbatim, to hand to every agent.

### 3. Fan out parallel explorers (the "searches")

Split the question into 3-6 independent areas/angles and spawn one `Agent` (`subagent_type: Explore`) per area, **all in one message so they run in parallel**. Brief each as a smart colleague who hasn't seen this conversation:

- State the goal and the inclusion bar (what's a finding, what's noise).
- Hand over exact paths/globs and the **already-covered / out-of-scope list** so they don't re-flag it.
- Demand a `file:line` for every concrete claim.
- Ask for prioritization (High/Med/Low) and, for each finding, the one-line "why it matters".
- Cap each response (~500-700 words) and require an "uncertain / would-need-to-confirm" section.
- Scale the fan-out to the question: a few agents for a focused audit, more (and a second round) for "be exhaustive".

Explore agents locate and summarize; they don't deeply verify. That's the next step.

### 4. Adversarially verify (the fact-check)

Do NOT trust agent summaries wholesale. On the main thread, **re-read the current code** for the highest-stakes, most surprising, or most "bug-like" claims and kill the false positives. Typical kills:

- "X is untested" when a test exists; "X doesn't exist" when it moved/renamed.
- A flagged "bug" that the surrounding code already handles, or that can't actually be reached.
- A finding that is **deliberately deferred** (check the descope list from step 2) rather than a real gap.
- Misjudged testability (e.g. "add a unit test" for logic that hardcodes a clock / does real I/O and would need a refactor first).

Trust current code over memory, Handoff, and agent text. Label each surviving finding by confidence: **verified** (you re-read it) vs **reported** (agent-cited, not re-read). For exhaustive audits, verification can itself fan out (one skeptic per top finding); for a normal pass, spot-check the top handful inline.

### 5. Synthesize the report

One report into the conversation (don't write a file unless asked). Structure:

```
# Code research: <question>

## Answer / headline
<2-3 sentences: the bottom line>

## Findings (prioritized, deduped)
### High
- [file.kt:42](path:42) — what it is, why it matters, (verified|reported). For test gaps: what a test would assert.
### Medium
...
### Low / needs-refactor-first
...

## Already covered / deliberately deferred (so it's not re-flagged later)
- ...

## Open questions / still unknown
- ...

## Recommended next step
<the first batch to act on, if any>
```

Rules for the report: every concrete claim cites `file:line` from code you or an agent actually read; dedupe overlapping findings from different agents; separate "real gap" from "already covered" from "deferred"; note testability honestly (pure/easy vs needs-mocking vs needs-refactor). No em dashes.

### 6. Hand off

End with "Ready to act" + the recommended first batch, or "Open questions block this". Do not start writing tests/fixes/refactors, the user decides whether and what to action.

## Scale knob (optional)

Default is a single fan-out + inline verification. For "be exhaustive" / large audits, do multiple rounds (loop until a round surfaces nothing new), add a per-finding adversarial verifier pass, and a final completeness critic ("what area/angle did we not cover?"). If the user has explicitly opted into multi-agent orchestration, the `Workflow` tool can run this find -> dedupe -> verify pipeline deterministically; otherwise use parallel `Agent` calls.

## Rules

- Read-only. Never edits, never writes code, never starts the fix.
- Every concrete claim cites `file:line` from current code. Memory/Handoff/agent-summary claims are hypotheses until cited.
- Surface stale memories/docs found along the way instead of acting on them.
- Cap the synthesized report (~1500 words). A bigger answer means the question needed splitting, not a longer report.
- No em dashes. Commas, parentheses, periods, colons.
