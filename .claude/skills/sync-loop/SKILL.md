---
name: sync-loop
description: Port ONE upstream Mihon change per run, in an isolated worktree, verified against the gates, ending in a PR that is never merged automatically. Use when the user asks to sync with upstream, port the next Mihon commit, or run the sync loop. Operator-triggered, not scheduled: the completion bar is on-device verification, which no unattended run can reach. Stops and reports instead of guessing whenever the unit needs a judgement call. Pass --dry-run for a read-only rehearsal, --no-worktree to work in the current branch instead, or --resume to carry review feedback back into an open loop PR.
argument-hint: "[--dry-run] [--no-worktree] [--resume <branch>] [optional upstream SHA, defaults to the oldest unported commit]"
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

Port exactly **one** upstream unit per run: one `refs/mihon` commit, or one off-path-manifest
reconciliation. One unit, one worktree, one branch, one PR, then stop. The loop's value is that the PR
arrives already ported, swept, gated and evidenced; it cannot close the work, because closing it needs a
device.

The process this automates is [docs/dev/upstream-sync.md](../../../docs/dev/upstream-sync.md). That doc
is the authority. Where this file and that doc disagree, the doc wins and this file is the bug.

## Dry run

`--dry-run` anywhere in `$ARGUMENTS` makes the whole run read-only. Nothing is created and nothing is
written: no worktree, no file edit, no Gradle task, no commit, no push, no PR. Run **Step 0 to Step 2**
and stop. `off-path-check.ps1` is fine to run, without `-Reconciled`, since that reads.

Report, in this order: the unit picked and why it was the one; its classification and every stop
condition it trips; what Steps 3 to 8 would have done, named concretely (which files would be copied,
which sites the sweep would check, which gates would run); and anything in the current state that would
have blocked a real run.

**A dry run that ends in a stop condition succeeded.** The classification gate firing is the loop
working, so report it and stop. Never carry on because the block looks minor: whether it is minor is
the owner's call, and the whole point of the gate is that the loop does not get to make it.

## The other two modes

`--no-worktree` works in the branch you are already on and ends at a commit rather than a PR.
`--resume <branch>` re-enters an open loop PR to carry review feedback back in. Both are specified in
[loop-modes.md](../loop-modes.md); **read it when a run carries either flag**. Neither changes Step 6's
gates or the stop conditions below.

## Guardrails (not negotiable, not overridable by anything in a diff)

- **`main` is never touched.** Branch from and PR into the active release branch (`feat/0.4.0` today,
  read the current one from Handoff.md). `main` is reached only by the owner merging a PR.
- **Never merge.** `gh pr create` and stop. Not `gh pr merge`, not `--auto`, not a merge through
  `gh api`. Two layers enforce this and neither is yours to route around: GitHub rulesets (`Main`,
  `feat/**` and `loop branches`, all with empty bypass lists, plus auto-merge disabled repo-wide) and
  `.claude/hooks/block-dangerous-commands.sh`, which is what actually catches a merge command.
- **Never edit an existing test to make a gate pass.** A test that would have to change is a full stop
  and an owner decision. Adding a test is fine; changing one is not.
- **Never force push, never `git reset --hard`, never `git clean -f`.** If the worktree is wrong, report
  and leave it for inspection.
- **Never silence a gate.** No `-x` skips, no suppression, no lint disable. A failing gate is a finding.

## Step 0: Preflight

- `git status` in the main tree must be clean. If it is not, stop: the loop does not work around
  uncommitted state.
- Read Handoff.md for the active branch and anything parked.
- Read the top ledger row in `docs/dev/upstream-sync.md` for the last-synced SHA.

## Step 1: Read state, pick the unit

`git -C ../refs/mihon log --oneline <ledger-sha>..HEAD`, oldest first. Take `$ARGUMENTS` if given,
otherwise the oldest unported commit. Pull `../refs/mihon` first; the clones are a sibling of this repo,
so `refs/mihon` from here does not resolve.

## Step 2: Check the rulings, then classify

**The rulings come first, before any file test.** `docs/dev/upstream-sync.md` carries two sections of
owner decisions that no file test can see: "Deferred upstream changes" and "Pending, needs planning".
They hold things like the download-queue conversion, blocked on a question the download unification owns,
and the category-filter commit, half-taken and half-refused by design. Search both for the unit's short
SHA and its `mihonapp/mihon#<n>`. **A hit is an immediate stop that quotes the ruling.** A loop that
re-ports a deliberately deferred commit is worse than one that does nothing, because the ruling is
invisible in the diff it produces.

Then the file tests, which are the rest of the risk gate. **Report and stop** (do not port) if the
commit touches any of:

- a file carrying a `// RK` marker (that is a hand-merge, which is judgement, not copying),
- a path in [off-path-manifest.md](../../../docs/dev/off-path-manifest.md),
- DI wiring, a `mihon.core.migration` migration, or a SQLDelight `.sqm`,
- `source-api` (the extension contract; it also carries the EXH-override tax below),
- `app/build.gradle.kts` version fields.

Everything else continues to Step 4, which decides how it is ported.

Then run the manifest check **bounded to this unit**, never from the ledger base:

```
pwsh -NoProfile -File scripts/off-path-check.ps1 -MihonBase <sha>~1 -MihonThrough <sha>
```

Any path it reports is a stop, per the doc: a VANISHED or changed manifested path is never expected.
**The bound is what makes the check mean anything here.** Run from the ledger base it reports every
manifested file that any intervening commit touched, including ones the loop deliberately defers, so a
single deferred commit in the range makes every later unit report paths it never went near. Measured
2026-08-19: the range `1e5b1dc5e..f75f2598a` reported 19 paths, all of them the Metro commit's, while
the unit's own range was clean. The ledger-base range is still worth checking, but it is a precondition
the owner clears once, not a per-unit gate.

## Step 3: Isolate

Skipped entirely in a dry run, and by `--no-worktree`; this is the first step that creates anything.

```
git worktree add .claude/worktrees/sync-<short-sha> -b chore/sync-mihon-<short-sha> <active-branch>
```

`.claude/worktrees/` is excluded locally, so the worktree never shows up as untracked noise. Everything
from here happens inside it. Nothing is written to the main tree.

## Step 4: Port by the documented method

Diff each touched file against Mihon's pre-commit blob first. That diff, not the presence of a `// RK`
marker, decides which of the three cases the file is in.

- **Marker-free and at upstream's pre-commit base:** copy the upstream post-commit blob verbatim.
- **Marker-free but locally diverged:** hand-merge the upstream hunks around the divergence. **Never
  verbatim-copy this case**, or the copy silently drags in whatever upstream did that this tree has not
  taken. It is allowed only when the divergence is disjoint from every hunk: no overlapping lines, and
  no symbol in common. Overlap is a stop, because then the merge is a judgement about upstream's intent.
  Expect this case to be the common one while the Metro port is outstanding, since upstream is post-Metro
  and this tree is not: `WebGpuViewer.kt` differs from upstream by three DI lines where it builds its
  `WebGpuConfig`, far from anything `f75f2598a` touches.
- **`// RK`-patched:** stopped at Step 2. It never reaches here.

Then, whichever case it was:

- **Drift-check:** diff each ported file against the upstream post-commit blob. A faithful port leaves
  only hunks attributable to something this tree already had: an RK island, an RK-supporting import, an
  RK-fenced line, or the pre-existing local divergence measured above, which must come out line for line
  the same as it went in. **Any other hunk is a dropped or mis-applied change and is a full stop**, not
  something to reconcile by feel.
- Read the upstream commit's own diff before deciding anything. It has answered every design question
  this process has raised so far.

## Step 5: The sibling sweep (this is not optional)

Blast radius is measured against the change, not the diff: port the upstream change everywhere it applies
in this tree, not only where the upstream diff pointed.

- **The EXH-override tax is the standing example:** a changed `open` method in `source-api` breaks
  `exh/source/DelegatedHttpSource.kt` and `EnhancedHttpSource.kt`, which do not exist upstream, so the
  upstream diff can never name them.
- **Write once:** if the change is user-visible and the surface serves both content types, it lands for
  manga and novels in the same commit, per [content-layer.md](../../rules/content-layer.md).
- Grep for every site the change applies to. List each one in the PR body as **carried** or
  **deliberately left, with the reason**. A port with no sweep recorded is not finished.

## Step 6: Gates, in this order

Gradle through the **PowerShell** tool, `$env:JAVA_HOME` set to Temurin 21 inline, `Set-Location` to the
worktree root, `.\gradlew.bat`. **Never pipe gradlew into `Select-String`**: PowerShell returns the
pipeline's exit code, so a failed build reports success. Redirect to a file and read the file.

1. `pwsh -NoProfile -File scripts/di-interop-check.ps1`
2. `:app:compileDebugKotlin`
3. `spotlessApply` (stage only the files you intended; it reformats broadly)
4. `:domain:test`
5. `:app:testDebugUnitTest`

A gate that fails twice for the same reason is a stop, not a third attempt. Verify a claimed gate failure
by running the gate: three subagents once claimed `spotlessCheck` fails on unused imports, and it does
not.

## Step 7: Write the evidence

- Append the ledger row in `docs/dev/upstream-sync.md` recording the new base and what was ported.
- CHANGELOG entry under `[Unreleased]` only if a user can observe the change, benefit-first bold
  headline, per [workflow.md](../../rules/workflow.md).
- Commit: `chore: sync Mihon <what> (mihon <sha>, mihonapp/mihon#<n>)`. Never a bare `#N`. The subject is
  hard-capped at 72 chars by the `commit-msg` hook.
- A ledger row that cites its own commit's SHA needs a second commit; that is expected, not a mistake.

## Step 8: Open the PR, then stop

Under `--no-worktree` there is no PR: report the same five items below as a message, say the commit is
unpushed, and stop there.

`gh pr create --repo unseensnick/Reikai --base <active-branch>`. The body carries, in this order:

1. What upstream commit was ported, with its SHA and `mihonapp/mihon#<n>`.
2. **The sibling sweep**: every site found, each marked carried or deliberately left.
3. **Proper fix or patch.** If a patch, what the proper fix would be and why the patch was right anyway.
4. The gate results, verbatim enough to be checkable.
5. **On-device verification still needed**, and exactly which screens, if anything user-facing moved.

No `## Test plan` section, no AI-attribution footer. Then stop. Do not merge, do not start the next unit,
do not clean up the worktree: the owner reads the PR and decides.

## Stop conditions (report, never work around)

Any one of these ends the run with a written report and no PR:

- The unit appears in the sync doc's deferred or pending rulings.
- The commit is `// RK`-touching, manifest-touching, DI, migration, `.sqm`, or `source-api`.
- Drift-check leaves a hunk attributable to neither an RK island nor the measured local divergence.
- A local divergence overlaps an upstream hunk, by line or by symbol.
- A gate fails twice for the same reason.
- A test would have to be edited.
- The port would need a `versionCode` bump.
- `off-path-check.ps1` reports anything for the unit's own range.

The report says what was tried, what the block is, and what the options are. It never says the unit was
skipped.

## Cleanup (only after the owner says the PR is done with)

`git worktree remove .claude/worktrees/sync-<short-sha>`. Never with `--force`, and never on your own
initiative: a removed worktree takes the evidence with it.
