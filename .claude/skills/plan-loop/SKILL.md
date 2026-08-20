---
name: plan-loop
description: Execute ONE step of a plan doc per run, after verifying the plan still matches the code, in an isolated worktree, ending in a PR that is never merged automatically and a status line written back into the plan. Use when the user asks to work through a plan, do the next phase of a port or migration, or run the plan loop. Takes a plan doc slug like "metro-di-migration". Two phases that never run back to back: --scout grounds the next step with /scout or /code-research and stops at the owner, a plain run executes a step that is already specified. Pass --dry-run for a read-only rehearsal, --no-worktree to work in the current branch, or --resume to carry review feedback back into an open loop PR.
argument-hint: "<plan-doc slug> [step] [--scout] [--dry-run] [--no-worktree] [--resume <branch>]"
disable-model-invocation: true
allowed-tools:
  - Bash(git status)
  - Bash(git log *)
  - Bash(git diff *)
  - Bash(git worktree *)
  - Bash(git add *)
  - Bash(git commit *)
  - Bash(git push *)
  - Bash(gh pr create *)
  - Bash(gh pr view *)
  - PowerShell(git *)
  - PowerShell(Set-Location *)
---

Execute exactly **one** step of one plan doc per run, then stop. The plan is the unit and the plan doc is
the state surface: the step comes out of it and the result goes back into it, so the next run and the next
session read the same record.

**Scouting a step is normal here, and it is a separate run.** Plan docs carry the program: the goal, the
approach, the rulings, the phase table. A step's ground-level detail is often deliberately left to a
`/scout` (or a `/code-research` when the question spans many files) at the moment the step comes up, and
several steps in these docs were built exactly that way. So this loop has two phases, and they never run
back to back: `--scout` investigates the next step and stops at the owner, and a plain run executes a step
that is already specified. The repo's standing rule is that a plan is presented and approved before
anything is edited, and one run doing both would route around it.

The sibling loops: [sync-loop](../sync-loop/SKILL.md) ports upstream commits, [audit-loop](../audit-loop/SKILL.md)
hunts bugs on a surface. Their risk gate stops on DI, migrations and schema; **this loop's does not**,
because on a port plan those are frequently the job. What stops this one is a step whose specification is
missing, stale, or contradicted by the code, not a dangerous-looking file.

## Dry run

`--dry-run` makes the whole run read-only: no worktree, no edit, no Gradle task, no commit, no push, no
PR. Run **Step 0 to Step 3** and stop, which covers the part worth rehearsing: which step is next,
whether the plan still matches the code, and what the step would touch.

**A dry run that ends in a stop condition succeeded.** Report it and stop rather than deciding the block
is minor; whether it is minor is the owner's call.

## Scout mode (`--scout`)

Ground the next step instead of executing it. Read-only against the source: run `/scout` for a single
step, or `/code-research` when the step's open question spans many files and modules rather than one
change. Then **stop at the owner**, with the findings and the proposed step in the message.

Nothing is edited in this mode except, on the owner's word, the plan doc itself: findings worth keeping
go into the plan as the step's specification, so the executing run has something to execute. That write
is the one thing that makes the next run possible, and it is also why it needs approval first.

**Never chain `--scout` into an executing run.** The point of stopping is that the owner sees the
specification before code is written against it.

## The other two modes

`--no-worktree` works in the branch you are already on and ends at a commit rather than a PR.
`--resume <branch>` re-enters an open loop PR to carry review feedback back in. Both are specified in
[loop-modes.md](../loop-modes.md); **read it when a run carries either flag**.

## Guardrails (identical to the sibling loops)

- **`main` is never touched.** Branch from and PR into the active release branch (read it from
  Handoff.md). `main` is reached only by the owner merging.
- **Never merge.** `gh pr create` and stop.
- **Never edit an existing test to make a gate pass.** A test that would have to change is a full stop
  and an owner decision, and on a migration plan it is the most likely stop of all: a step that cannot
  keep the existing tests green has changed behaviour the plan did not say it would.
- **Never force push, never `git reset --hard`, never `git clean -f`.**
- **Never silence a gate.**

## Step 0: Preflight

- `git status` must be clean.
- Read Handoff.md for the active branch, the current state and the parked list.
- Resolve `$ARGUMENTS` to one doc in `docs/dev/plans/`. An ambiguous or missing slug is a stop, not a
  guess: list the near matches and ask.

## Step 1: Pick the step

Plan docs follow Goal / Why / Approach / Key files / Status / Decisions, so **Status is where the next
step lives**. Take the step named in `$ARGUMENTS` if given, otherwise the first unfinished one.

Stop and report if the plan does not actually yield a step: no Status section, a Status that disagrees
with itself, or a "next" that is a question rather than an instruction. Handoff.md is a cross-check here,
not an authority: where the two disagree, say so and stop, because one of them is stale and guessing
which is exactly the failure this loop exists to avoid.

## Step 2: Verify the plan against the code

**A plan is a hypothesis, like a memory or a handoff.** Before executing anything, audit the step's
citations: every file, symbol and behaviour claim it makes either still holds in current code or it does
not. Start with the cheap check, minutes of greps, because most of the time it passes and the run
continues.

- **Named file or symbol no longer exists: stop, and say a `--scout` run is what unblocks it.** Never
  silently re-derive the specification and carry on inside the same run; a step the loop respecified for
  itself is not one anyone approved. Re-grounding a stale step is real work with its own output, which
  is why it gets its own run.
- **A behaviour claim no longer holds:** say which, and stop unless the step's instruction is unaffected.
  Being unaffected is a judgement you show your work for, not one you assert.
- **The step is already done**, wholly or partly: stop and report that instead of redoing it. A plan doc
  whose Status lags the code is a common and cheap thing to fix, and it is the owner's call.

## Step 3: The readiness gate

Stop and report if any of these hold, because each one means the step needs a decision this loop cannot
make:

- The step says what to achieve but not how. That is not a defect in the plan, it is a step that has not
  been grounded yet: stop and say `--scout` is the run that grounds it.
- It needs a ruling the plan does not contain, or contradicts one in
  [content-layer.md](../../rules/content-layer.md) or the surface's own doc.
- It is user-visible and specified for one content type only. Write-once makes that a plan bug: report it
  rather than shipping half.
- Executing it would need a SQLDelight `.sqm` or a version-gated preference migration that the plan does
  not already specify. Discovering you need a schema change mid-step means the step was mis-scoped.
- It cannot be done without editing an existing test.

## Step 4: Isolate

Skipped entirely in a dry run, and by `--no-worktree`; this is the first step that creates anything.

```
git worktree add .claude/worktrees/plan-<slug> -b chore/<slug>-<step-slug> <active-branch>
```

Branch under `chore/` or `fix/` so the `loop branches` ruleset covers it.

## Step 5: Do the step, and only the step

- **The proper fix, not the smallest one.** If the step genuinely needs a helper extracted, a signature
  changed or a call site moved, that is in scope and belongs in the same commit, with the reasoning in the
  commit body. Do not optimise for diff size.
- **The sibling sweep is part of the step.** Whatever the step changes, grep for every other site the
  same change applies to and list each as **carried** or **deliberately left, with the reason**. On a
  migration plan this is where the real risk sits: the pattern almost always exists at more sites than
  the plan enumerated, and the plan's list is a starting point, not a boundary.
- **Write once:** a user-visible change lands for both content types in the same commit.
- **Nothing beyond the step.** Adjacent work the plan schedules for a later step stays for that step,
  even when it is right there. Report it instead.

## Step 6: Gates, in this order

Gradle through the **PowerShell** tool, `$env:JAVA_HOME` set to Temurin 21 inline, `.\gradlew.bat`.
**Never pipe gradlew into `Select-String`**: PowerShell returns the pipeline's exit code, so a failed
build reports success. Redirect to a file and read the file.

1. `pwsh -NoProfile -File scripts/di-interop-check.ps1`
2. `:app:compileDebugKotlin`
3. `spotlessApply` (stage only the files you intended; it reformats broadly)
4. `:domain:test`
5. `:app:testDebugUnitTest`

A gate failing twice for the same reason is a stop, not a third attempt. **A test file that had to change
is a stop even when every gate is green**, so check `git diff --name-only` against the test paths before
believing a green run.

Add a minified `:app:assemblePreview` when the step touches the DI graph, the reader, or anything
resolving through the surviving Injekt calls, since R8-only breakage is invisible in the debug build.

## Step 7: Write the evidence back into the plan

This is the part that makes it a loop rather than a one-off:

- **Update the plan doc's Status** with what this step landed and what is now next. A step marker citing
  its own commit SHA goes in a follow-up docs commit, since it cannot be amended in.
- CHANGELOG entry under `[Unreleased]` only if a user can observe the change, benefit-first bold
  headline.
- Commit per the standard, subject capped at 72 chars, never a bare `#N`.

## Step 8: Open the PR, then stop

Under `--no-worktree` there is no PR: report the same items as a message, say the commit is unpushed, and
stop there.

`gh pr create --repo unseensnick/Reikai --base <active-branch>`, body carrying:

1. **Which plan and which step**, linked.
2. **The plan verification**: what was checked, and anything found stale.
3. **The sibling sweep**: every site, carried or deliberately left.
4. **Proper fix or patch**, and if a patch, what the proper fix would be.
5. The gate results, including that no test file changed.
6. **On-device verification still needed**, and exactly which screens.

Then stop. Do not merge, do not start the next step, do not clean up the worktree.

## Stop conditions (report, never work around)

- The plan yields no unambiguous next step, or Handoff.md and the plan disagree.
- A cited file or symbol no longer exists, or the step is not grounded yet. Both point at a `--scout`
  run, which is a normal next move here rather than a failure.
- The step needs an unwritten ruling, is single-type on a user-visible change, or needs an unplanned
  schema or preference migration.
- A gate fails twice for the same reason, or a test would have to be edited.
- The step turns out to be already done.

## Cleanup (only on the owner's word)

`git worktree remove .claude/worktrees/plan-<slug>`, never `--force`.
