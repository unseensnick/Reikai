---
name: port-audit
description: Audit a slice of the legacy to Compose port for behavioral parity to legacy. Use when the user asks to audit, verify, or sanity-check a migrated screen, when they want to know whether a Compose port "really matches" the legacy version, or when they want a defect-fix pass on a recently-ported area. Takes a scope argument like "Library Phase 1-3" or "Settings → Security". Compares features and dispatch behavior only, NOT pixels or layout (the user often redesigns visuals on the way over).
argument-hint: "<scope> (e.g., 'Library Phase 1-3', 'Settings → Security', 'Reader')"
disable-model-invocation: true
allowed-tools:
  - Bash(git status)
  - Bash(git log *)
  - Bash(git diff *)
  - Bash(JAVA_HOME=* ./gradlew *)
---

Audit the migrated screen described by `$ARGUMENTS` for behavioral parity to the legacy Conductor + presenter implementation, surface real defects (not visual ones), plan and implement the fixes, but stop short of committing. The user runs `/ship` when they're happy.

## Mode

The scope argument tells you what surface to audit. Examples:

- `Library Phase 1-3`: phases of an ongoing port (read the matching plan file).
- `Settings → Security`: a single Compose screen.
- `Reader`: a whole feature area.

If the scope is vague (`Library`, `Settings`), ask the user to narrow it before spawning the audit subagent. A broad audit will return a noisy report.

## Step 1: Ground the context

Cheap reads before delegating, so the audit subagent gets briefed properly:

- Look for a `Handoff.md` in the project root (untracked is fine). It usually names the branch, what shipped, what was deferred, and which legacy files are off-limits per the parallel-implementation rule.
- Check `git log --oneline -20` for the recent shape of work in the scope.
- If the scope mentions a phase, look in `C:\Users\<user>\.claude\plans\` for a matching plan file. Reikai stores plans there; the latest one often has the "what really shipped" rewrite after Phase 2/3.
- Skim `.claude/rules/architecture.md` for the legacy / Compose split rules. Never propose touching legacy files when the architecture is parallel-implementation.

If a Handoff or plan explicitly defers features to a later phase, **write that deferred list down** for the audit subagent. The biggest failure mode is false-flagging deferred work as a defect.

## Step 2: Spawn the audit subagent

One `Agent` call with `subagent_type: general-purpose` (or `Explore` if pure read). Brief it as a smart colleague who has not seen this conversation:

- State the goal: behavioral parity, NOT visual parity. Visual / layout differences are EXPLICITLY out of scope.
- Name the legacy entry point(s): `LibraryController.kt`, `LibraryPresenter.kt`, the sub-views (`FilterBottomSheet.kt`, etc.), the layout XMLs.
- Name the Compose entry point(s): the Voyager screen, its content composable, the components folder, the screen model, any pure helpers.
- Name the deferred list (verbatim, from Step 1).
- Give it a per-surface checklist of legacy behaviors to mark off. Group by surface (data pipeline, each sheet, in-grid affordances, overflow menu, search, preferences observed). For each item, ask for one of:
  - ✅ ported faithfully
  - ⚠️ partial / divergent (note the divergence)
  - ❌ missing
  - 🟡 deferred (and to which phase, per Step 1's list)
- Ask for citations (`file:line`) for both the legacy site and the place in the Compose port where the behavior should appear.
- Cap output at ~1200 words. Ask for: 1-line verdict, per-surface checklists, defects list, "items worth double-checking" list.

Why the checklist: the model will not produce a thorough audit from a prompt like "find missing features." It needs the surfaces enumerated so it knows what to look for.

## Step 3: Triage with the user

Present the subagent's report verbatim or summarized. For each defect, classify it as one of three:

1. **Clear defect**: proceed to fix.
2. **Deferred (subagent missed the note)**: discard. Update the audit prompt for next time.
3. **Ambiguous: intentional redesign or missing feature?**: ask the user.

For category 3, use `AskUserQuestion` BEFORE writing the plan. Typical examples:

- A preference that "writes but does nothing" might be deliberate (the user redesigned that surface and no longer needs the toggle's old behavior).
- A removed affordance might be intentional simplification.
- A reordered flow might be intentional.

Frame each ambiguous question with the legacy behavior, the current Compose behavior, and 2-3 options including "restore legacy" and "keep current and repurpose / drop the pref." Do not assume.

## Step 4: Ground each fix before planning

For non-trivial defects, spawn Explore agents in parallel to gather the surrounding code so the plan can cite exact paths and patterns. Bundle related defects per agent (one agent per ~3-5 related defects is usually enough). Ask each agent:

- The legacy code that performs the behavior, verbatim where useful.
- The Compose hooks where the fix should land (composable signatures as they currently exist).
- Any existing helper in the project that the fix should reuse (e.g., `EmptyScreen`, `openInBrowser`, `seriesType`, `isLocal`, `getResourceColor`). The DRY rule from CLAUDE.md applies.

Use the existing project helpers. Reikai already has Compose equivalents for most legacy widgets; the audit subagent's job is to find them, not invent new ones.

## Step 5: Plan

Use `EnterPlanMode`. The plan file structure that worked in this skill's home session:

1. **Context**: why this audit / fix is happening, decisions captured in Step 3.
2. **TL;DR table**: `| # | Defect | User-visible effect |`. One row per fix, plain-English effect. This is the section the user actually reads first.
3. **Per-defect implementation detail**: for each, cite legacy file:line + Compose file:line, name the param / signature changes, name the call sites.
4. **Files touched**: flat list.
5. **Commit cadence**: one logical change per commit, conventional-commits messages drafted, NO `Co-Authored-By`, NO em dashes.
6. **Verification**: concrete on-device steps using user-facing setting names (look these up in `i18n/src/commonMain/moko-resources/base/strings.xml`, since internal pref keys do not always match the displayed label). Include the `JAVA_HOME=~/scoop/apps/temurin17-jdk/current ./gradlew :app:testStandardDebugUnitTest --tests "<class>"` command lines for any test runs.

Call `ExitPlanMode` when the plan is complete. Wait for approval.

## Step 6: Implement

After approval:

- Create a TaskCreate task per defect. Mark `in_progress` when starting, `completed` immediately when done. Don't batch.
- Order: trivial fixes first (label swap, single-line change) to verify the build pipeline, then larger ones.
- Keep edits minimal per CLAUDE.md anti-defaults. No adjacent refactors, no speculative parameters, no defensive checks for impossible states. Comments only when the WHY is non-obvious.
- For widget-level parameters you need to add (e.g., `enabled: Boolean = true` on a preference widget), add them in the same commit as the consumer; do not make a standalone "add param" commit.

After all defects are landed:

- Run the relevant unit tests with `JAVA_HOME=~/scoop/apps/temurin17-jdk/current ./gradlew :app:testStandardDebugUnitTest --tests "<class>"` (one class at a time per the testing rules).
- Run `JAVA_HOME=~/scoop/apps/temurin17-jdk/current ./gradlew :app:compileStandardDebugKotlin` to verify the main source set compiles. Warnings from deprecated APIs the project already uses (Manga interface, LocalRouter, Java statusBarColor) are fine; new errors are not.

## Step 7: CHANGELOG

Per `.claude/rules/workflow.md`:

- The feature this audit covers is almost certainly already mentioned in `## [Unreleased]`. **Edit the existing bullet** to fold in the new behavior. Do not add "Fixes: X in feature Y" entries when Y is already in the same `[Unreleased]` block.
- Phrase additions as user-facing release notes (what changed, where it lives, what it does). No implementation rationale, no internal phase numbering, no class names.
- For a genuine bug fix that's tangential to the feature's main bullet (e.g., a chip label swap), a one-line entry under `### Fixes` is fine.

## Step 8: Stop

Do NOT commit. CLAUDE.md is explicit: "NEVER commit changes unless the user explicitly asks." The plan listed a commit cadence as a guide, but plan approval is not commit authorization.

Summarize for the user:

- Code changes, one bullet per file.
- Verification: which tests passed, which compile target succeeded.
- On-device verification still needed (you cannot drive their emulator / Fold 6 from here).
- Pointer: "When ready, run `/ship` to slice into the planned commits and open the PR."

## Rules

- Never propose touching legacy files when the architecture is parallel-implementation. The legacy Conductor controllers, presenters, sheet views, and XML layouts stay alive as fallback during the soak period.
- Never false-flag deferred features. Re-read the deferred list before each defect classification.
- Never `git merge upstream/master`. Reikai ports upstream manually from `refs/yokai/`.
- Never commit without an explicit user ask. The user runs `/ship` separately.
- No em dashes (—) in prose, plan files, comments, or commits. Commas, parentheses, periods, colons.
- No `Co-Authored-By: Claude` or any AI-attribution footer in commits or PRs.
- If a fix would change behavior outside the audited scope, stop and ask the user before pulling it in. Bug fixes have minimal blast radius.
- If the audit subagent's report is sparse or generic, the prompt was probably too vague. Re-spawn with a more specific surface checklist before triaging.
