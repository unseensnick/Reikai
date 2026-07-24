---
alwaysApply: true
---

# Plan and findings output format

How a research report or implementation plan is written, whether it comes from `/scout`, `/code-research`, or a plan given directly in conversation. The goal is density: keep every technical claim and every `file:line`, cut the words around them.

## Structure

Use these sections in this order. **Omit any section with nothing to report** rather than writing a placeholder.

### 1. Headline

The single binding constraint in two or three sentences: what actually drives the design, stated before any detail. Not a summary of what follows, and not a list. If several things look binding, name the one that decides the others.

### 2. Findings

Grouped **High / Medium / Low**. Each finding is a **bolded one-line claim**, then the shortest prose that carries the evidence, with inline `file:line` references.

Prose, not bullet fragments, and a few tight sentences rather than a paragraph. The claim line states the conclusion; the prose exists only to make it checkable. Mark a finding **verified** when re-read directly, **reported** when it came from a subagent and was not re-read.

### 3. Stale docs

Comments, KDoc, or plan-doc lines that contradict current code. One line each: what it says, and what is actually true. Surface for pruning, do not silently fix.

### 4. The plan

Steps as **bolded named items** (`Step 1a, the neutral identity for grouping`), each two or three sentences covering what it does and why it is safe at that point in the sequence. Ordering is the content: say what a step depends on and what it unblocks.

Omit this section for a pure audit with no implementation to propose.

### 5. Open questions

Last section, numbered, each marked **blocking** or **non-blocking**. Give the concrete options, a recommendation, and the reasoning behind it. These get answered before implementation starts, so a question with no options attached is not finished.

## Rules

- **Density over length.** Every sentence carries a fact the reader does not already have. Cut restatement, throat-clearing, and transitions that only announce what is coming.
- **Never drop a `file:line` to save space.** References are the payload, prose is the wrapper. Trim the wrapper.
- **No progress narration in the artifact.** "Now let me check", "Terrain mapped", "Six good returns" are working-log material and never appear in the report.
- **Bullets enumerate options; prose carries findings.** Do not fragment a finding into bullets to look shorter.
- **Cite it or drop it.** A claim without a `file:line` from code actually read belongs in Open questions, not Findings.
- **No em dashes.** Commas, parentheses, periods, colons.
- Cap the artifact around 1500 words. Longer means the question needed splitting, not that the report needed more room.
