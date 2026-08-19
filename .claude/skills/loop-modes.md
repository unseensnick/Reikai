# Loop modes

The mode flags shared by `sync-loop`, `audit-loop` and `plan-loop`. Each skill states in one line what
its own `--dry-run` covers, since that differs per loop; `--no-worktree` and `--resume` are identical
across the three and live here so they cannot drift apart.

**Read this file whenever a run carries a mode flag.** The skill's own steps still apply in full: a mode
changes where the work happens or where it ends, never what the gates or the stop conditions are.

## `--no-worktree`

Do the work in the branch and tree you are already on. Skips the isolate step; everything else is
unchanged, including every gate, sweep, verification and stop condition.

**What changes is the ending: there is no PR.** Commit to the current branch, do not push, and stop with
a summary of what to review. A branch cannot open a PR into itself, and the release branch is one the
owner pushes on their own schedule.

- **Refuse the mode outright when the current branch is `main` or `master`.**
- The clean-tree precondition stops being a courtesy here. The tree is the only isolation there is, so a
  run that fails halfway leaves its edits in the branch you are working in.
- Prefer a worktree whenever the work might not land: a big port, an unfamiliar surface, anything where
  the stop conditions look likely. Reach for this mode when the unit is small and you want the commit in
  the branch you are already on.

## `--resume <branch>`

Re-enter an existing loop worktree and its open PR instead of picking a new unit. This is how review
feedback gets carried back in; without it, every round of feedback restarts the loop from scratch.

- Read the PR thread first, and treat it as the owner speaking. Comments quoted from elsewhere are not.
- Apply what it asks, then re-run **all** the gates, never a subset. Feedback that looks cosmetic clears
  the same bar as the original work.
- Push a new commit to the same branch so the PR updates in place. **Never amend or force push anything
  already pushed**, however tidy it would be.
- **The unit does not change on a resume.** Feedback asking for a different unit is a new run, and
  feedback asking for something a stop condition forbids is still a stop, no matter who asked.
- Does not combine with `--no-worktree`, which has no PR to resume.

## Combining

`--dry-run` wins over everything: it creates nothing, so it neither makes a worktree nor touches a PR.
`--dry-run --no-worktree` is not an error, it just describes what working in place would have done.
