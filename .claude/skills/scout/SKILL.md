---
name: scout
description: Investigate a non-trivial task, then produce the plan for it. Emits a file:line-cited findings report covering current behavior, upstream/reference equivalents, framework bridges, helpers to reuse and stale docs, followed by a sequenced plan and any blocking open questions. Use before porting an upstream change, migrating a screen to Compose, touching unfamiliar modules, or any cross-cutting change. The point is to ground the plan in evidence, not memory. Investigates deeply and verifies adversarially by default; never edits files.
argument-hint: "<task description> (e.g., 'port General settings to Compose', 'add per-category sort')"
disable-model-invocation: false
allowed-tools:
  - Bash(git *)
  - Bash(JAVA_HOME=* ./gradlew *)
---

Investigate the task described by `$ARGUMENTS` deeply enough that the resulting plan cannot contain hallucinated functions, stale memories, or assumed framework behavior. Output is a findings report **and the plan that follows from it**. **This skill never edits files and never writes code**: it proposes, the owner approves, implementation is a separate step.

## Standing defaults (no need to ask for these)

These are the behavior on every invocation. The owner should never have to prepend them.

- **Depth.** Real investigation: open the files and verify each claim against current code. Never pattern matching, never inference from a file or symbol name. A plausible mechanism that was not read is not a finding.
- **Completeness.** Keep investigating until every unknown is resolved. Do not stop at the first coherent story, and do not fill a gap with an assumption to finish the report.
- **Open questions get asked, not guessed.** Anything that cannot be settled from the code becomes an explicit numbered question, answered before implementation starts.
- **Adversarial verification is mandatory.** Re-read the claims that would most distort the plan if wrong, including claims from `Handoff.md`, plan docs, memories, and subagent findings.
- **Output format** follows [.claude/rules/plan-output.md](../../rules/plan-output.md).

## When to use this

Use `/scout` for non-trivial work where shallow context is expensive:

- Porting an upstream change from Mihon (`refs/mihon/`), or a Reikai feature from the `design/library-compose` branch (the old Yōkai base) re-typed onto Mihon's models.
- Adding or reworking a Compose + Voyager screen in an unfamiliar area.
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
| Current Reikai code | Always | Target files, callers, callees, related ScreenModel, tests. |
| Reference source | Ports, parity checks | Upstream port: the matching Mihon file(s) + history (`git -C refs/mihon log -- <path>`). Reikai-feature port: the Yōkai-era source on `design/library-compose`. API differences from current code. |
| Framework bridges | Theme, Compose, Voyager, Injekt | The relevant layer (e.g., Voyager screen lifecycle, `PreferenceStore`, Injekt DI). Specifically: what's mapped, what isn't, what falls through. |
| Existing helpers (DRY) | New widget / utility tempting | Search for the project's existing equivalent before letting the plan invent one. |
| Test coverage | Behavior changes, refactors | What's tested, what isn't, which test class to extend. |

For each area, spawn one `Agent` call with `subagent_type: Explore` (read-only is enough). Run them in parallel in a single message. Brief each as a smart colleague who hasn't seen this conversation:

- State the goal (what the user is going to do next).
- Hand over the exact files / paths / git commands.
- Demand `file:line` citations for every concrete claim.
- Cap the response (~500 words is usually enough per area).
- Ask for a "things I'm uncertain about" section.

## Step 4: Adversarially verify

Explore agents locate and summarize; they do not verify. Before anything reaches the report, re-read the current code yourself for the claims that would most distort the plan if wrong. Typical kills:

- A claim that something "doesn't exist" when it moved or was renamed.
- A flagged problem the surrounding code already handles, or that cannot actually be reached.
- A finding that is on the deferred list from Step 2 rather than a real gap.
- A severity rating that does not survive reading the code around the cited line.

Verify claims inherited from `Handoff.md`, plan docs and memories on the same footing as subagent claims. All of them have been wrong in this repo. Label each surviving finding **verified** (re-read) or **reported** (cited but not re-read); anything load-bearing for the plan must be verified.

## Step 5: Synthesize the report and the plan

Into the conversation, not a file unless asked, in the structure defined by [.claude/rules/plan-output.md](../../rules/plan-output.md): headline, findings graded High / Medium / Low, stale docs, the plan, open questions.

The findings still carry the areas from Step 3, now graded by importance rather than grouped by area: what current code does, how the reference source differs, which framework bridge constrains the approach, which existing helper the plan should reuse instead of inventing one, and what the tests cover.

Additions specific to this skill:

- **A claim that cannot cite a `file:line` from code actually read** goes in Open questions, never in Findings.
- **A source contradiction is itself a finding.** When memory, `Handoff.md` or a plan doc disagrees with current code, trust the code and record the contradiction under Stale docs so it can be pruned.
- **Deferred work is not a defect.** If the Step 2 descope list already covers something, say so once and move on.
- **The plan names the helper it reuses.** A step that invents a utility the repo already has is a failed scout, so cite the existing one.

## Step 6: Hand off

End with one of:

1. **"Ready to implement."** Findings verified, plan complete, no blocking questions.
2. **"Open questions block implementation."** The blocking ones from the last section, asked plainly.
3. **"Investigation incomplete."** Name what is still unchecked and ask whether to spawn more agents or proceed with the gap documented.

Then stop. Do not start editing and do not call `EnterPlanMode`. The owner answers the open questions, approves the plan, and implementation follows as a separate step.

## Rules

- This skill proposes a plan but never edits files, never writes code, and never calls `EnterPlanMode`.
- Every claim cites `file:line` from current code. Memory, Handoff and plan-doc claims are hypotheses until cited from current code.
- If a memory or Handoff is stale, surface it; let the user decide whether to prune.
- Never fill an unresolved gap with an assumption. Investigate it or surface it as an open question.
- Cap each subagent at ~500 words. Output follows [.claude/rules/plan-output.md](../../rules/plan-output.md), including its ~1500 word cap; a bigger task needs decomposition, not a longer report.
- No em dashes in the report. Commas, parentheses, periods, colons.
- No assumptions about framework defaults. If the report relies on a framework behavior, the citation points to the source of that behavior (Compose library, Material3, Voyager) or to a project-side test that verifies it.
- If an Explore agent comes back vague or generic, the brief was too loose. Re-spawn with a sharper scope before synthesizing.
