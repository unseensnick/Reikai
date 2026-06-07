---
name: scout
description: Investigate a non-trivial task before planning. Produces a findings report with file:line citations covering current behavior, upstream/reference equivalents, framework bridges, helpers to reuse, and open questions. Use before porting an upstream change, migrating a screen to Compose, touching unfamiliar modules, or any cross-cutting change. The point is to ground the plan in evidence, not memory.
argument-hint: "<task description> (e.g., 'port General settings to Compose', 'add per-category sort')"
disable-model-invocation: true
allowed-tools:
  - Bash(git *)
  - Bash(JAVA_HOME=* ./gradlew *)
---

Investigate the task described by `$ARGUMENTS` deeply enough that a plan grounded in the findings is unlikely to contain hallucinated functions, stale memories, or assumed framework behavior. **This skill never proposes changes and never writes code.** It produces a findings report. The plan happens after.

## When to use this

Use `/scout` for non-trivial work where shallow context is expensive:

- Porting upstream changes from `refs/yokai/` (legacy file may not exist in Reikai, or may have been migrated to Compose).
- Migrating a legacy Conductor screen to Compose + Voyager.
- Touching cross-cutting infrastructure (DI modules, preferences, theme bridges, coroutine helpers).
- Working in modules the recent session hasn't touched.
- Acting on a claim from memory or a Handoff that mentions specific files / functions / flags by name.

Do NOT use `/scout` for:

- One-line tweaks, label swaps, copy edits.
- Work entirely inside a file you just edited this session.
- The "happy path" continuation of an active task (you already have the context).

If the user invokes it for trivial work, push back once and ask if they really need it. The skill costs tokens.

## Step 1: Parse and scope

Echo the task you parsed from `$ARGUMENTS` in one sentence so the user can correct misreadings before you spend agent tokens.

If the task is vague (`"library stuff"`, `"fix settings"`), use `AskUserQuestion` to narrow:

- Which screen / module / surface?
- Port from upstream, migrate to Compose, fix a behavior, or add a new feature?
- Is there a Handoff.md, plan file, or recent commit range that frames this?

Do not proceed with a vague scope. Vague scout reports are noise.

## Step 2: Cheap reads before delegating

These run on the main thread (cheap) so the subagents you spawn next get briefed properly:

1. Check `Handoff.md` in the project root if present (untracked is fine). Note the branch, what shipped, what was deferred, which legacy files are off-limits.
2. `git log --oneline -20` for recent shape of work. If the task references a phase, `git log --oneline -50 --grep="<phase keyword>"` to find prior commits in the same series.
3. Look for a plan file in `C:\Users\<user>\.claude\plans\` matching the task (Reikai stores plans there; latest plan often has a "what really shipped" rewrite).
4. Skim `.claude/rules/architecture.md` for the legacy / Compose split rule. For a flipped section, the Compose screen is the target. For not-yet-flipped, legacy is.

Write down for Step 3 (briefing the subagents):

- The task as scoped.
- The current branch and recent commit shape (one line).
- The migration state of the affected section per `architecture.md` (flipped / compose-only / not-flipped).
- The deferred list from Handoff / plan, verbatim. **The most common audit failure is flagging deferred work as missing.**

## Step 3: Identify investigation areas

Decide which of these areas the task touches. Skip the ones that don't apply, brief one Explore agent per area that does.

| Area | When relevant | What to brief |
|---|---|---|
| Current Reikai code | Always | Target files, callers, callees, related screen-model / presenter, tests. |
| Upstream reference (`refs/yokai/`) | Ports, parity checks | The matching upstream file(s), the change history (`git -C refs/yokai log -- <path>`), API differences from Reikai's version. |
| Framework bridges | Theme, Compose, Voyager, Koin | The bridge layer (e.g., `createMdc3Theme`, `Voyager` screen lifecycle, `PreferencesHelper`). Specifically: what's mapped, what isn't, what falls through. |
| Existing helpers (DRY) | New widget / utility tempting | Search for the project's existing equivalent before letting the plan invent one. |
| Test coverage | Behavior changes, refactors | What's tested, what isn't, which test class to extend. |

For each area, spawn one `Agent` call with `subagent_type: Explore` (read-only is enough). Run them in parallel in a single message. Brief each as a smart colleague who hasn't seen this conversation:

- State the goal (what the user is going to do next).
- Hand over the exact files / paths / git commands.
- Demand `file:line` citations for every concrete claim.
- Cap the response (~500 words is usually enough per area).
- Ask for a "things I'm uncertain about" section.

## Step 4: Synthesize the findings report

The report goes into the conversation. Do not write it to a file unless the user asks. Structure:

```
# Scout: <task>

## Goal
<one sentence>

## Current state
- [file.kt:42](path/to/file.kt:42): what it does, briefly
- [other.kt:88](path/to/other.kt:88): ...

## Upstream / reference equivalent
- [refs/yokai/.../file.kt:N](refs/yokai/.../file.kt:N): what differs from Reikai's version

## Framework / bridge constraints
- e.g., `createMdc3Theme` does NOT surface `?attr/background`; reading the legacy attr directly is required.

## Existing helpers to reuse
- [helper.kt:N](path/to/helper.kt:N): what it provides

## Tests
- existing: [foo_test.kt:N](path/to/foo_test.kt:N)
- gaps: <what isn't covered>

## Open questions for the user
1. <ambiguity worth resolving before the plan>
2. ...

## Still unknown (would need to verify before committing to an approach)
- <thing you couldn't pin down with confidence>
- <thing the upstream change does that may or may not apply>
```

Rules for the report:

- **Every claim about behavior, function name, or file path must cite a `file:line`.** No exceptions. If you can't cite it, move it into the "Still unknown" section.
- Do not synthesize implementation guidance. The report describes evidence; the plan decides what to do with it.
- If two sources contradict (memory vs current code, Handoff vs git), trust the code and note the contradiction. Update or remove the stale memory after the user confirms.
- If a memory in `MEMORY.md` named a function, file, or flag that no longer exists, flag it explicitly so the user can prune.
- If the deferred list from Step 2 covers something that would otherwise look like a defect, mark it `🟡 deferred` and move on.

## Step 5: Hand off to planning

End the report with one of:

1. **"Ready to plan."** Findings are complete and unambiguous.
2. **"Open questions block planning."** List the questions; ask the user before proceeding.
3. **"Investigation incomplete."** Name what still needs to be checked; ask the user whether to spawn more agents or proceed with gaps documented.

Then stop. Do not call `EnterPlanMode` yourself. The user reads the findings and decides whether to invoke planning, ask a follow-up, or redirect.

## Rules

- This skill never edits files, never proposes changes, never writes code, never calls `EnterPlanMode`.
- Every claim cites `file:line` from current code. Memory recall and Handoff claims are hypotheses until cited from current code.
- If a memory or Handoff is stale, surface it; let the user decide whether to prune.
- Cap each subagent at ~500 words; cap the synthesized report at ~1500 words. If the task is bigger than that, it needs decomposition, not a longer report.
- No em dashes (—) in the report. Commas, parentheses, periods, colons.
- No assumptions about framework defaults. If the report relies on a framework behavior, the citation points to the source of that behavior (Compose library, Material3, Voyager) or to a project-side test that verifies it.
- If an Explore agent comes back vague or generic, the brief was too loose. Re-spawn with a sharper scope before synthesizing.
