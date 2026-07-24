---
name: session-handoff
description: Write Reikai's Handoff.md so a fresh session resumes cleanly, and update the durable backlog and dependent docs alongside it. Use when the session is wrapping up ("I'm stepping away", "continue tomorrow", "wrap up"), before a `/clear`, when a long session has accumulated state that would be lost, or when the session is looping on a broken approach and a fresh agent would do better. Offer it proactively on those signals; do not wait to be asked.
argument-hint: "[optional: a narrower scope, e.g. 'handoff only' or 'just the roadmap']"
disable-model-invocation: false
allowed-tools:
  - Bash(git *)
  - Read
  - Glob
  - Grep
---

Write the handoff and bring the durable records in line with it, so the next session starts from an accurate picture instead of re-deriving one. The repo-specific twin of the global `session-handoff` skill: same spine, Reikai's file set and conventions.

## Default behavior (no arguments)

**Invoked with no arguments, do the full sweep.** No further prompting, no asking which parts to do. That means all four:

1. **Rewrite `Handoff.md`** to the structure below.
2. **Update `ROADMAP.md`**: close out what shipped, add what this session surfaced, re-scope what changed.
3. **Update dependent docs** that this session's work contradicts or completes (the set is listed under "The doc set").
4. **Sync the memory store and the handoff copy**, then commit and push the memories repo.

`$ARGUMENTS`, when present, narrows that scope (for example "handoff only", "skip the roadmap"). It never widens it. An unrecognized argument is a scope hint, not a topic: still do the full sweep, biased toward what it names.

## Step 1: Establish the real state before writing

Never write the handoff from conversation memory alone. Confirm each of these first, since they are the facts the next session acts on:

- `git log origin/<branch>..HEAD --oneline` for what is unpushed, and `git status --short` for a dirty tree. State both explicitly in the header, including "pushed and clean" when that is the case.
- `git log --oneline -20` for the session's actual commit range and subjects.
- Read the existing `Handoff.md`. If it still describes a previous session as "this session", **rewrite it rather than appending**. Layered patches are how a handoff goes stale.
- Skim `ROADMAP.md` for items this session shipped, invalidated, or re-scoped.

## Step 2: The handoff structure

Keep these sections. Omit one only when it genuinely has nothing, rather than writing a placeholder.

- **Header**: branch, HEAD short-SHA, pushed/unpushed count, tree state, and any standing instruction (the "no PR until 0.4.0 is done" rule belongs here). Platform notes belong here too when they affect tooling.
- **Read first**: the two or three docs that frame the work, most authoritative first.
- **Goal**: the program-level outcome, not the next tactical step. Pull it from the initiative's plan doc, not from whatever sub-task the session ended on.
- **Current state**: what works, what is half-done, what is deliberately not done. Name the measured effect where there is one (line counts, branch counts, verified surfaces). Be explicit about what is NOT done and why, or the next session rebuilds it.
- **Files**: only files central to the in-progress work, each with its role and status (new / modified / defined-but-unconsumed). A handoff listing forty files is noise.
- **Changes made**: the commit range with a line per commit, grouped when a group tells the story better. Mark which commits are device-verified and which are not.
- **What failed / do not repeat**: the highest-value section. Include the approach, what was expected, what happened. Group variations of one idea so the next session does not try variation four. Include process failures (a wrong tool, a bad assumption about the harness), not just code ones.
- **Next steps**: ordered and concrete. Say which step gates which. Branch the ones that depend on an unknown ("if X, do Y; if Z, do W").
- **Parked**: items blocked on the owner. Say plainly not to raise them unprompted, and where the detail lives.
- **Durable gotchas**: facts that outlive this session (a `versionCode` gate, a deliberate exception, a module placement rule, device ids).
- **Build / test / verify**: the gate commands, the test module split, the commit-message constraints, the upstream synced base, and the memory sync note.

## Step 3: The doc set

Reikai keeps its durable record in several places, each with a different job. Update the ones this session touched:

| File | Holds | Convention |
|---|---|---|
| `Handoff.md` (root) | Session state | **Gitignored. Edit on disk, never `git add` it.** |
| `../reikai-claude-memories/handoff/Handoff.md` | The synced copy | Must be written too, then committed and pushed in that repo |
| `ROADMAP.md` | Forward backlog only | One-line items, size tag, area grouping, never a log of what shipped |
| `docs/dev/plans/*.md` | Per-feature record | Goal / Why / Approach / Key files / Status / Decisions; index it in that folder's `README.md` |
| `docs/dev/shipped.md` | Done-log | At release-cut, not per session |
| `CHANGELOG.md` | `[Unreleased]` | Benefit-first bold headline, user-facing effect only |
| `docs/dev/upstream-sync.md` | Sync ledger | Append a row per Mihon sync |
| `docs/dev/off-path-manifest.md` | Deleted Mihon paths | One row per delete plus its replacement |
| The memory store | Durable cross-session facts | Junctioned into the memories repo, so writes need a commit and push there |

**The split that matters most:** `ROADMAP.md` stays terse and forward-looking, plan docs carry the detail, and observations blocked on the owner go to memory rather than the roadmap. When a roadmap line starts growing a research narrative, move that narrative into a plan doc and leave a one-line item with a link.

## Step 4: Sync and verify

1. Copy `Handoff.md` to `../reikai-claude-memories/handoff/Handoff.md`.
2. Commit and push the memories repo (its own git repo, `main`). Memory writes do not sync without this.
3. Commit tracked app-repo doc changes (`ROADMAP.md`, plan docs, CHANGELOG) with a `docs(...)` subject. `Handoff.md` is never in that commit.
4. Report the final branch state so the owner knows whether anything is left to push.

## Reikai conventions the global skill cannot know

- **No em dashes** anywhere in any file written here. Commas, parentheses, periods, colons.
- **No AI watermarks** in commits, docs or PRs.
- **Commit subjects `<= 72` chars**, `type(scope): summary`, enforced by a `commit-msg` hook that aborts a chained `git add && git commit` on rejection. Keep `set -e`.
- **Never a bare `#N`.** A roadmap item is `Roadmap N`; a real issue or PR is `owner/repo#N`.
- **`ROADMAP.md` is a semi-public surface**: no content-source names, use the approved shorthand (EH / ExH / MD / CMK) or collective phrasing.
- **Module sources live under `app/app/src/...`** from the repo root.
- A plan-doc step marker citing its own commit SHA goes in a **follow-up** docs commit, since it cannot be amended in.

## Handing back

After writing, give the owner the next-session prompt verbatim:

> Read `Handoff.md` and continue from where the previous session left off. Before you start working, summarize back to me your understanding of the goal, current state, and what you plan to do next so I can confirm before you proceed.

The summarize-back step catches a misread before the fresh session commits to an action.

## Rules

- Verify state with git before writing it down. A handoff asserting "pushed and clean" that is neither is worse than none.
- Do not hide failures. A handoff that omits the dead ends walks the next session straight into them.
- Label hypotheses as hypotheses. "Suspect the bug is in the retry wrapper", not "the bug is in the retry wrapper".
- Do not pad. Short and honest beats long and hedged.
- No progress narration in the artifact. It is a briefing, not a journal.
