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

## Standing defaults (no need to ask for these)

These are the behavior on every invocation. The owner should never have to prepend them.

- **Depth.** Real investigation: open the files and verify each claim against current code. Never pattern matching, never inference from a file or symbol name, never "this is probably how it works". A plausible mechanism that was not read is not a finding.
- **Completeness.** Keep researching until every unknown is resolved. Do not stop at the first coherent story, and do not fill a gap with an assumption to finish the report. If an area is unresolved, it is either researched further or surfaced as an open question, never quietly smoothed over.
- **Open questions get asked, not guessed.** Anything that genuinely cannot be settled from the code becomes an explicit numbered question and the owner answers it before implementation starts.
- **Adversarial verification is mandatory**, not a scale knob. Re-read the claims that would most distort the plan if wrong. That explicitly includes claims from prior plan docs, `Handoff.md`, memories, and subagent findings, all of which have been wrong in this repo before.
- **Output format** follows [.claude/rules/plan-output.md](../../rules/plan-output.md).

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

Trust current code over memory, `Handoff.md`, plan docs, and agent text, all of which have asserted things in this repo that current code contradicted. A claim carried forward from a plan doc is a hypothesis exactly like a subagent's, and gets the same re-read when it is load-bearing.

Label each surviving finding by confidence: **verified** (you re-read it) vs **reported** (agent-cited, not re-read). Every finding that would change the plan if wrong must reach **verified** before it ships in the report. For exhaustive audits, verification can itself fan out, one skeptic per top finding.

### 5. Synthesize the report

One report into the conversation (don't write a file unless asked), in the structure defined by [.claude/rules/plan-output.md](../../rules/plan-output.md): headline, findings graded High / Medium / Low, stale docs, the plan, open questions.

Additions specific to this skill:

- **Dedupe across agents.** Two agents finding the same thing is one finding, not two.
- **Separate a real gap from deferred work.** Something on the descope list from step 2 is not a finding; say so once and move on rather than re-flagging it.
- **Note testability honestly** when the question is about tests: pure and easy, needs mocking, or needs a refactor first.
- **Report the plan section as a plan**, not as "recommended next step" prose, whenever the research is feeding implementation.

### 6. Hand off

End with "Ready to act" + the recommended first batch, or "Open questions block this". Do not start writing tests/fixes/refactors, the user decides whether and what to action.

## Scale knob

Verification is never the knob (it is mandatory, see Standing defaults). What scales is breadth: a focused audit gets one fan-out, "be exhaustive" gets multiple rounds looping until a round surfaces nothing new, plus a completeness critic ("what area or angle did we not cover?"). If the user has explicitly opted into multi-agent orchestration, the `Workflow` tool can run the find -> dedupe -> verify pipeline deterministically; otherwise use parallel `Agent` calls.

## Rules

- Read-only. Never edits, never writes code, never starts the fix.
- Every concrete claim cites `file:line` from current code. Memory, Handoff, plan-doc and agent-summary claims are hypotheses until cited.
- Surface stale memories/docs found along the way instead of acting on them.
- Never fill an unresolved gap with an assumption. Research it or surface it as an open question.
- Output follows [.claude/rules/plan-output.md](../../rules/plan-output.md), including its ~1500 word cap. A bigger answer means the question needed splitting.
- No em dashes. Commas, parentheses, periods, colons.
