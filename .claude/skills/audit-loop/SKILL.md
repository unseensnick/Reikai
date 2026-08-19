---
name: audit-loop
description: Audit ONE named surface per run, adversarially verify every finding before touching code, fix what survives at every site it exists, pin it with a mutation-checked test, and end in a PR that is never merged automatically. Use when the user asks to audit a surface, hunt bugs, or run the audit loop. Takes a scope like "recents engine" or "novel download queue". Operator-triggered, one surface per run; a vague scope is refused. Pass --dry-run to audit and verify without touching anything.
argument-hint: "<surface> [--dry-run] (e.g. 'recents engine', 'migrate flow config screen --dry-run')"
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

Audit one surface, fix only what survives verification, open one PR, stop. The difference from
[sync-loop](../sync-loop/SKILL.md) is where the risk sits: finding a bug is cheap and fixing the wrong
one is expensive, so **nothing gets touched until the finding has been attacked and held**.

[port-audit](../port-audit/SKILL.md) is the interactive sibling of this skill and its triage rules still
apply. The two differences: this one runs in a worktree and ends in a PR, and it verifies findings before
fixing rather than asking the owner to triage them.

## Dry run

`--dry-run` anywhere in `$ARGUMENTS` makes the whole run read-only. Nothing is created and nothing is
written: no worktree, no file edit, no Gradle task, no commit, no push, no PR. The scope argument is
whatever remains once the flag is stripped. Run **Step 0 to Step 4** and stop.

The audit and the adversarial verification are the read-only half of this loop, so a dry run still does
the expensive and useful part. Report: the surface and which bug class it fails by; confirmed findings
with `file:line`; dropped findings with why they did not survive; the fixes that would have been made
and the sibling sites each sweep would have covered; and which stop conditions the run trips.

**A dry run that ends in a stop condition succeeded**, including "more than about five confirmed
findings". Report and stop rather than deciding the block is minor.

## Guardrails (identical to sync-loop, and for the same reasons)

- **`main` is never touched.** Branch from and PR into the active release branch (read it from
  Handoff.md). `main` is reached only by the owner merging.
- **Never merge.** `gh pr create` and stop.
- **Never edit an existing test to make a gate pass.** A test that would have to change is a full stop.
- **Never force push, never `git reset --hard`, never `git clean -f`.**
- **Never silence a gate**, and never delete a failing test to clear one.

## Step 0: Take a real scope, or refuse

`$ARGUMENTS` names one surface. `Library`, `Settings`, or `the codebase` is not a scope: refuse it and
ask which surface, because a broad audit returns a noisy report and noise is what makes an audit loop
worse than no loop. Good scopes look like `recents engine`, `novel download queue`,
`migrate flow config screen`.

Then read, before looking at any code:

- Handoff.md's **Parked, do not raise unprompted** list. Parked items are not findings.
- The surface's record in `docs/dev/plans/`, including anything explicitly deferred or declined. A
  decline expires with its evidence, so a decline whose stated premise no longer holds IS a finding;
  a decline still standing is not.
- [content-layer.md](../../rules/content-layer.md)'s seam-depth table for this surface. It decides what
  to look for, which is the single most useful input this loop gets.

## Step 1: Know which bug class you are hunting

The table's depth for this surface tells you how it fails, and the two classes do not show up in each
other's review:

- **A taken-over surface** (details, migrate, recents, library orchestration) fails by **upstream drop**:
  behaviour the replaced Mihon code had that ours silently lost. Hunt it by walking the replaced code's
  behaviour end to end, not by reading ours and asking whether it looks right.
- **A UI-leaf or behavior-partial surface** (browse, reader chrome) fails by **duplicate
  implementation**: one rule restated at N call sites with some of them wrong. Hunt it by finding the
  rule and enumerating its sites.

## Step 2: Audit with read-only agents

Fan out `Explore` or read-only `general-purpose` agents over the surface. Brief each as a colleague who
has not seen this conversation: name the entry points, name the deferred and parked lists verbatim, and
require a `file:line` for every claim. Ask for findings, not prose.

Do not trust a subagent's count or claim without re-reading the cited line yourself.

## Step 3: Verify adversarially, before touching anything

Every finding gets independent skeptics prompted to **refute** it, defaulting to refuted when uncertain.
A finding survives only if it cannot be refuted against current code. Only surviving findings are fixed;
the rest go in the PR body as considered-and-dropped, so the next run does not re-raise them.

This step is why the loop is worth running. Skip it and it generates plausible-but-wrong fixes faster
than the owner can review them.

## Step 4: Triage what survived

- **More than about five confirmed findings on one surface: stop and report instead of fixing.** That is
  a design problem, not a bug list, and it needs an owner ruling.
- **Owner-only, report and do not fix:** anything touching DI wiring, a migration, a `.sqm`, or
  `source-api`; anything where "intentional redesign or missing feature" is a real question; anything
  that would need a `versionCode` bump.
- Everything else is fixable in this run.

## Step 5: Isolate

Skipped entirely in a dry run; this is the first step that creates anything.

```
git worktree add .claude/worktrees/audit-<surface-slug> -b fix/<surface-slug> <active-branch>
```

All edits happen inside the worktree.

## Step 6: Fix properly, and fix it everywhere

- **The proper fix, not the smallest one.** If the correct fix needs a helper extracted, a signature
  changed, or a call site moved, do that in the same change and say why in the commit body. If the patch
  really is the right call, take it and record what the proper fix would have been. Do not optimise for
  diff size.
- **The sibling sweep is part of the fix, not a follow-up.** Grep for the same defect everywhere it can
  exist and fix every site. List each site in the PR body as **fixed** or **deliberately left, with the
  reason**. "The reported case passes" is not "the bug is fixed".
- **Write once:** a user-visible fix on a surface serving both content types lands for manga and novels
  in the same commit. The only exit is a named mechanism the type cannot support, cited.
- **No drive-by cleanup.** The scope stays the confirmed findings and what fixing them genuinely
  requires. Pre-existing dead code found along the way is reported, not folded in.

## Step 7: Pin every fix, and prove the pin

Each confirmed bug gets a test that reproduces it. **Verify it by mutation**: delete the production
clause the test pins, watch the test go red, restore the clause. A test not mutation-checked is not
evidence; four vacuous tests have already got through here.

A rule that must hold for both content types is pinned once (a shared kernel, a typed capability, or one
conformance test over both adapters), never as a hand-maintained twin pair.

## Step 8: Gates

Same order as sync-loop, through PowerShell with `JAVA_HOME` set, never piped into `Select-String`:

1. `pwsh -NoProfile -File scripts/di-interop-check.ps1`
2. `:app:compileDebugKotlin`
3. `spotlessApply`
4. `:domain:test`
5. `:app:testDebugUnitTest`

Green gates prove nothing broke. They never prove nothing was missed: the sibling sweep is what carries
that, and it is checked by reading, not by a gate.

## Step 9: Commit, PR, stop

CHANGELOG entry under `[Unreleased]` if a user can observe the fix (extending a shipped feature to a
surface it missed is a **Fix**, not a Change). Commits follow the standard; one logical fix per commit.

`gh pr create --repo unseensnick/Reikai --base <active-branch>`, body carrying:

1. **The surface audited**, and what was walked.
2. **Confirmed findings**, each with its `file:line` and the fix.
3. **Dropped findings**, each with why it did not survive verification.
4. **The sibling sweep**: every site, fixed or deliberately left.
5. **Proper fix or patch**, per fix.
6. **The mutation check** for each new test: which clause was deleted, that it went red.
7. **On-device verification still needed**, and exactly which screens.

Then stop. No merge, no next surface, no worktree cleanup.

## Stop conditions

- The scope is vague.
- More than about five confirmed findings.
- A finding lands in DI, a migration, a `.sqm`, or `source-api`.
- A fix would need an existing test edited.
- A gate fails twice for the same reason.
- A finding needs an owner ruling to classify.

## Cleanup (only on the owner's word)

`git worktree remove .claude/worktrees/audit-<surface-slug>`, never `--force`.
