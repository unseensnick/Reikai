---
name: sync-loop
description: Port ONE upstream Mihon change per run, in an isolated worktree, verified against the gates, ending in a PR that is never merged automatically. Use when the user asks to sync with upstream, port the next Mihon commit, or run the sync loop. Operator-triggered, not scheduled: the completion bar is on-device verification, which no unattended run can reach. Stops and reports instead of guessing whenever the unit needs a judgement call.
argument-hint: "[optional upstream SHA, defaults to the oldest unported commit]"
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

## Step 2: Classify it, and stop if it is not mechanical

This is the whole risk gate, so run it before creating anything. **Report and stop** (do not port) if the
commit touches any of:

- a file carrying a `// RK` marker (that is a hand-merge, which is judgement, not copying),
- a path in [off-path-manifest.md](../../../docs/dev/off-path-manifest.md),
- DI wiring, a `mihon.core.migration` migration, or a SQLDelight `.sqm`,
- `source-api` (the extension contract; it also carries the EXH-override tax below),
- `app/build.gradle.kts` version fields.

Everything else is a **verbatim-copy port** and continues. Also run
`pwsh -NoProfile -File scripts/off-path-check.ps1 -MihonBase <ledger-sha>` and treat any reported path as
a stop, per the doc: a VANISHED or changed manifested path is never expected.

## Step 3: Isolate

```
git worktree add .claude/worktrees/sync-<short-sha> -b chore/sync-mihon-<short-sha> <active-branch>
```

`.claude/worktrees/` is excluded locally, so the worktree never shows up as untracked noise. Everything
from here happens inside it. Nothing is written to the main tree.

## Step 4: Port by the documented method

- **Marker-free file:** confirm this tree sits at Mihon's pre-commit base by diffing the file first, then
  copy the upstream post-commit blob verbatim. A base that does not match clean is a stop.
- **Drift-check:** diff each ported file against the upstream post-commit blob. A faithful port leaves
  only RK-attributable hunks (an island, an RK-supporting import, an RK-fenced line). **Any other hunk is
  a dropped or mis-applied change and is a full stop**, not something to reconcile by feel.
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

- The commit is `// RK`-touching, manifest-touching, DI, migration, `.sqm`, or `source-api`.
- Drift-check leaves a hunk that is not RK-attributable.
- A pre-commit base does not match clean before a verbatim copy.
- A gate fails twice for the same reason.
- A test would have to be edited.
- The port would need a `versionCode` bump.
- `off-path-check.ps1` reports anything.

The report says what was tried, what the block is, and what the options are. It never says the unit was
skipped.

## Cleanup (only after the owner says the PR is done with)

`git worktree remove .claude/worktrees/sync-<short-sha>`. Never with `--force`, and never on your own
initiative: a removed worktree takes the evidence with it.
