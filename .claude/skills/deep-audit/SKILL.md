---
name: deep-audit
description: Read-only, many-agent audit of a whole range of work (default the current branch against main) for bugs, misconfiguration, missing wiring, dead code, rule violations and manga/novel drift. Runs a Workflow that slices the range, points thirteen lenses at it (including two-ends tracing, where independent agents start at the write side and the read side of one value and a third compares them), refutes high and medium findings before they count, and reports. A --quick mode reads the same range with far fewer agents and verifies only high findings. Never edits code; fixes go to /audit-loop one surface at a time. Use when the user asks for a broad audit, a pre-release audit, or "check that all the changes work together". NOT for one surface (/audit-loop), one diff (/pr-review) or parity with the Yōkai branch (/port-audit).
argument-hint: "[<rev>..<rev> | <path> | <surface>] [--quick] [--lenses a,b] [--mutate] [--yes]"
disable-model-invocation: false
allowed-tools:
  - Bash(git status*)
  - Bash(git log *)
  - Bash(git diff *)
  - Bash(git merge-base *)
  - Bash(git rev-parse *)
---

Audit a whole range of Reikai work with many agents, verify every finding adversarially, and report.
**This skill writes one file, the ledger, and nothing else.** It never edits code, never fixes, never
commits. Fixing is `/audit-loop`'s job, one surface per run, because that loop's rule (attack the
finding before touching anything) is what keeps fixes honest.

How it differs from its siblings: `/pr-review` is a fast diff check with no verification,
`/audit-loop` takes one surface and ends in a PR, `/port-audit` compares against the Yōkai branch.
This one is broad on purpose, and the map step shows what it will cost before anything expensive runs.
A full run on a large range is a large share of a week's quota (one run over 674 files used more than
80M tokens before the cost fixes), so reach for `--quick` unless the owner asks for full depth.

The engine is [deep-audit.workflow.js](deep-audit.workflow.js), run through the `Workflow` tool with
`scriptPath`. Invoking this skill is the opt-in to multi-agent orchestration. A default run spawns far
more agents than the session's workflow-size guideline (about 15); if the owner wants that guideline
raised to match, it is "Dynamic workflow size" in the app's config.

## Arguments

- **No scope**: the current branch against `main` (`git merge-base main HEAD` to `HEAD`).
- **`<rev>..<rev>`**: that commit range.
- **A path**: the default range, limited to files under that path.
- **A surface name** (`novel reader`, `recents engine`): resolve it to paths through the surface's
  plan doc in `docs/dev/plans/` and the seam-depth table in `.claude/rules/content-layer.md`, then
  treat it as a path scope. If it resolves to nothing, ask.
- **`--quick`**: every agent on Opus. One map agent at medium effort; one sweep per code slice covering correctness,
  async, security, dead code and the slice's own wiring; one agent each for wiring, all rule files,
  parity with sibling sites, tests and replaced behaviour; one agent per traced value, at most five.
  Only high findings are verified, by one Opus skeptic each; medium findings are listed unverified. No
  critic round. It still reads the whole range.
- **`--lenses a,b`**: run only these lenses (names below). In quick mode correctness, async, security
  and dead share the sweep, which runs if any of them is selected.
- **`--mutate`**: after verification, one agent in a throwaway worktree deletes the production clause
  of up to five new tests, runs each test class through Gradle and reports tests that stay green.
  Off by default: it needs CLI Gradle, which is intermittent on this machine, and it runs one test at a
  time.
- **`--yes`**: skip the approval stop after the map.
- **A `+Nk` token target** in the owner's message caps the run: once it is spent, remaining agents fail
  and are reported as uncovered. It counts output tokens only, so it is a backstop, not a meter.

## The lenses

| Lens | Target | What it hunts |
|---|---|---|
| `correctness` | each code slice, one agent with `async` (`code-reviewer`) | Logic, state, coroutine and Compose defects, and whether changed functions still fit their callers |
| `security` | code slices the map tags `untrusted` (`security-reviewer`) | Untrusted input, WebView bridges, leaks into logs and backups |
| `performance` | code slices the map tags `hotpath` (`performance-reviewer`) | Main-thread I/O, recomposition, per-entry work at library scale |
| `async` | each code slice, in the `correctness` agent | Sent-before-ready, dismiss-before-confirm, detached `postDelayed`, windows that never close, init order |
| `dead` | each code slice | Unused code, prefs, strings and islands, with a mandatory whole-tree re-search behind every "unused" |
| `rules` | `code-quality`, `content-layer` and `architecture` over the range; the other three only over slices tagged `screens`, `tests` or `sql` | `code-quality`, `content-layer`, `screen-conventions`, `architecture`, `testing`, `database`, checked line by line |
| `docs` | each docs slice (`doc-reviewer`) | Claims in docs, rules and plan docs that current code contradicts, with counts re-derived |
| `tests` | each tests slice | Tests that cannot fail, over-claiming names, twin tests that should be one conformance test, a reasoned mutation of up to five |
| `wiring` | the range, twice | Build and platform wiring (manifest, ProGuard keeps, catalog, DI scoping); data and settings wiring (`versionCode` gates, `.sqm` numbering, backup fields, settings with no effect) |
| `parity` | user-visible changes, batched | Write-once gaps between manga and novels, and `twin of` markers that name no pin |
| `sibling` | fix commits, batched | The same defect still sitting at another site: the other type, mode or viewer |
| `upstream` | each taken-over surface, and Reikai code the range deleted | Behaviour the replaced or deleted code had that ours lost, walked from theirs |
| `twoends` | the riskiest write/read pairs, at most six | Two blind tracers (write side, read side) and a reconciler that reports every mismatch |

Quick mode folds these into fewer agents; the `--quick` argument above lists them.

Finders report medium and high findings, at most eight per agent plus three lows (quick: highs, and at
most eight mediums). Every finding carries an evidence label: **executed** when a command's output decided it, **traced**
otherwise. The report keeps the two apart.

## Step 0: A clean tree and a real range

Agents read the working tree, while the range is committed history, so uncommitted edits make
them report against code that is not in the range. **If `git status` is dirty, stop and ask** the owner
to commit or stash first.

Resolve `base` and `head` to short SHAs with `git rev-parse`, and state the range and its size
(`git log --oneline <base>..<head> | wc -l`, `git diff --shortstat <base>...<head>`) in one line.

## Step 1: Ground

Read these, and pass them to the workflow verbatim as `ground`:

- **`parked`**: the `## Parked` section of `Handoff.md` (gitignored, on disk). Parked items are not
  findings.
- **`ledger`**: the table rows in [ledger.md](ledger.md). A finding refuted before is not raised again
  while its reason still holds. Only verifiers see it, filtered to the files a finding cites and each
  reason cut to its first sentence; finders never carry it.

Deferred and declined items live in `docs/dev/plans/`. The agents are told to read the record for
their surface themselves; don't inline those docs.

## Step 2: Map, then stop for approval

```
Workflow({ scriptPath: "<repo root>/.claude/skills/deep-audit/deep-audit.workflow.js",
           args: { mode: "map", quick, base, head, pathFilter, lenses, ground } })
```

Four agents build the map (quick: one agent doing all four parts): slices (code, docs, tests) tagged by
what they touch, up to six write/read pairs ranked by risk (quick: five), the fix commits plus the
user-visible changes (from `CHANGELOG.md` `[Unreleased]` when the range ends at the release branch),
and the taken-over surfaces plus Reikai code the range deleted. Translation files outside `base/` and
files only `chore: sync Mihon` commits changed are excluded. The result includes an estimate of finder
agents per lens and how verification scales.

Show the owner: the slice list with sizes, the pairs, the surfaces, what was excluded and why, and the
estimate (finders, plus the verification line the map returns). Then
**stop and wait**, unless `--yes` was passed. The owner may drop slices or pairs, add a pair, or narrow
the range; edit the map object to match. The map decides everything downstream, and this is the last
cheap point to fix it.

## Step 3: Audit

```
Workflow({ scriptPath: "<same>", args: { mode: "audit", quick, base, head, pathFilter, lenses, ground, mutate, map } })
```

It runs in the background; wait for the completion notification. What the script does, so the report
can explain it:

1. **Find.** Every lens over its targets, in parallel, at medium effort.
2. **Verify.** Findings on the same file within three lines merge (lenses overlap), then skeptics told to
   refute them, defaulting to refuted, attack them. A high finding gets two at high effort: one re-reads
   the code and settles what it can by running something read-only, the other checks whether it is
   deferred, ruled, parked, in the ledger or contradicted by a passing gate. It survives only if both
   fail to refute it. Medium findings go in batches of up to five per surface to one skeptic doing both
   checks. Low findings are listed unverified. Quick mode: one Opus skeptic doing both checks per high
   finding; medium and low findings are listed unverified.
3. **Critic** (full mode only). One agent reads the coverage table and names up to five gaps on high-risk
   ground. Those gaps get one round of finders and verification; there is no second round, and the report
   says so.
4. **Mutate**, only with `--mutate`.

If the run dies part-way, resume it with `resumeFromRunId` and the same args. Finished agents replay
from cache.

## Step 4: Report

In the conversation, in the structure of [plan-output.md](../../rules/plan-output.md): headline,
findings graded High / Medium / Low, stale docs, open questions. There is no plan section, since this is
an audit. The 700-word cap is waived for the findings list only, because a range audit's length scales
with its range. Everything around the list stays dense.

- **Each finding**: bolded one-line claim, `file:line`, the lenses that raised it, the evidence label.
  High findings get the evidence prose; medium and low findings are one line.
- **Unverified**: the medium and low findings no skeptic checked (quick mode: all mediums), one line
  each, labelled unverified. They are leads, not results.
- **High findings that are still traced** go in their own short list, each with the probe that would
  settle it (a test, a log line, a device step). They are not confirmed until someone runs that.
- **Stale docs** come from the `docs` lens and from any finding whose "defect" is really a doc that
  never caught up.
- **Coverage**: a compact table of lens by target with its status. Name every failed task and every
  gap the critic raised that was not re-run. Without this, "no findings" can't be told apart from
  "nobody looked".
- **Hand-offs**: group confirmed findings by surface, with one line each: `/audit-loop <surface>`. A
  surface with more than about five confirmed findings is flagged as a design problem for the owner,
  which is `/audit-loop`'s own stop condition.
- **Refuted**: a count, plus any refutation the owner might want to overturn.

Say plainly that the run was read-only, and which findings still need a device.

## Step 5: The ledger

Append each refuted finding to [ledger.md](ledger.md) as one row: the range, `file:line`, the claim in
a short phrase, why it was refuted. **Leave the file uncommitted**; committing it is the owner's call.
A row stops holding once its reason stops being true, so a later run may re-raise it with the new
evidence.

## Rules

- Read-only. No code edits, no fixes, no commits, no pushes. The ledger is the only write.
- Never skip the approval stop after the map unless `--yes` was passed.
- Never report a finding the verifiers refuted, and never promote a traced high finding to confirmed.
- Never let a zero stand for coverage: a lens that returned nothing is reported with what it searched.
- No em dashes, no AI watermarks, in the report or the ledger.
