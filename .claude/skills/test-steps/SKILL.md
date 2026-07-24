---
name: test-steps
description: Turn a change into a checkable manual test script the owner runs on-device, not prose about testing. Reachability-verified steps grouped by precondition, one observable outcome each, with unreachable actions listed up top and known regressions or type-divergences flagged. Use when the user asks for test steps, a test plan, or how to verify a change by hand. This is the manual counterpart to test-writer (which writes automated JUnit tests).
argument-hint: "<the change or feature to test> (e.g. 'the library selection move', 'novel search')"
disable-model-invocation: false
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git *)
---

Produce a manual test script the owner can run on-device and tick through, for the change named in `$ARGUMENTS` (or the change just made, if none is named). The output is a checklist, not an essay about testing. The owner runs it on the A57 and the Fold; this skill never claims a step passed, since it does not drive the device.

## Step 0: Verify reachability first, before writing any step

This is the part that fails when skipped. Twice recently, proposed steps were physically impossible to perform, because the action was written from intent rather than from the code. So read the code first and confirm each candidate action can actually be triggered in the build the owner will run.

For each action you are about to write a step for, check:

- **Is the control present in this view at all?** The library has two view modes (the tabbed pager and the single-list "hopper"), and a control can exist in one and not the other. Select-all-in-category, for example, is single-list only.
- **Does the action need selection mode, which swaps the toolbar?** In selection mode filter, sort and search are locked out, so a step that says "select some rows, then filter" cannot run. A rebuild during selection can only be triggered by background work (a library update writing in, a download finishing), never by the user.
- **Does selection start on long-press, not tap?** A tap with nothing selected opens the entry.
- **Is there a code guard with no reachable path?** A range-select whose anchor has scrolled off screen, for instance, may be unreachable because the list cannot change while selection mode holds the toolbar.
- **Is the device state present?** Some paths need a local source, a logged-in tracker, or an adult source. If the test device does not have one, that path is not testable there, regardless of the code.
- **Is it manga-only or novel-only?** A type-divergent behaviour is only testable on the chip that has it.

Anything that fails this check gets no step. It goes in the `Not testable` block instead.

## The Not testable block

Put it at the very top, so the owner sees what was deliberately left out before reading the steps. Bullets, subject bolded, one clause of reason each:

```markdown
**Not testable**
- **Tap-to-select**: selection starts on long-press; a tap with nothing selected opens the entry.
- **Scrolled-out anchor guard**: selection mode locks filter and search, so the list can't shift underneath.
- **Select-all-in-category**: exists only in single-list view.
- **Download hides when all-local**: no local source installed on the test device.
```

The reason distinguishes the three cases: not reachable in code, absent from the current view mode, or not present on the test device. Omit the block entirely when everything is reachable.

## Step format

**One step, one outcome.** Every step ends in something observable. Setup with no outcome of its own folds into the step it precedes.

Two shapes, chosen by how many actions the step takes.

**Fewer than three actions:** actions on the first line, the expectation indented on the next line behind an arrow.

```
1.3 Long-press A, then long-press B further down the same category
    → range between them selects
```

**Three or more actions:** a short title line, lettered sub-actions, then the indented arrow.

```
1.4 No range across categories
    a. Long-press an entry in category 1
    b. Swipe to category 2
    c. Long-press an entry there
    → only that entry selects, no bogus range
```

A blank line between steps in both shapes. The arrow line is always indented under its step and states only what the owner should see, never what they do.

## Group by precondition, not by feature

The header names the state the owner must be in, and every step under it shares that setup. This is where the two view modes and the two content-type chips live: "Novels chip, single-list view" is a precondition group, and its steps run in that state without repeating it. Sub-number as `N.M` so a group boundary is visible in the numbers.

When a change must behave the same in both view modes or on both chips, say so once as a group, rather than duplicating every step. A short "Regression sanity" group at the end for the untouched side (for example, re-check manga after a shared binding changed underneath it) is worth including when the change reached shared code.

## Flags

Put `⚑` on a step title when it covers a known regression, a fix made this session, or a manga/novel divergence. Give the reason in one clause on the arrow line, nothing more.

```
2.1 ⚑ Switch the chip while entries are selected
    → selection clears (regression: the shared selection used to carry across)
```

## Rules

- **No narration.** No "good instinct", no "let me verify", no commit hashes, no account of what was fixed. A fix earns one line in the `Not testable` reasoning or a flagged step, never a paragraph.
- **No progress log.** Do the reachability reads silently; the output is the script, not the investigation.
- **The owner runs it, not you.** Never report a step as passed or failed. Hand over the script and stop.
- Cite nothing in the output. The `file:line` reads happen in Step 0 to decide what is reachable; they do not belong in a checklist the owner ticks on a phone.
- **Write the step and outcome lines like a human**, per [.claude/rules/prose-style.md](../../rules/prose-style.md): plain verbs, no inflated stakes, no trailing "-ing" significance clauses, no em dashes. An outcome reads "range between them selects", not "the range is selected, ensuring correct behaviour".
