---
alwaysApply: true
---

# Plan and findings output format

How a research report or implementation plan is written, whether it comes from `/scout`, `/code-research`, or a plan given directly in conversation. The goal is density: keep every technical claim and every `file:line`, cut the words around them.

This file governs the structure. [prose-style.md](prose-style.md) governs the sentences inside it.

## Structure

Use these sections in this order. **Omit any section with nothing to report** rather than writing a placeholder.

### 1. Headline

The single binding constraint in two or three sentences: what actually drives the design, stated before any detail. Not a summary of what follows, and not a list. If several things look binding, name the one that decides the others.

### 2. Findings

Grouped **High / Medium / Low**. Each finding is a **bolded one-line claim**, then the shortest prose that carries the evidence, with inline `file:line` references.

Detail is tiered by grade. **High** findings get the evidence prose: a few tight sentences, not a paragraph; the claim line states the conclusion and the prose exists only to make it checkable. **Medium and Low** findings are one line each: the claim plus its citation, no supporting prose. If a Medium finding cannot be stated in one line with a citation, it is either High or it is two findings. Mark a finding **verified** when re-read directly, **reported** when it came from a subagent and was not re-read.

### 3. Stale docs

Comments, KDoc, or plan-doc lines that contradict current code. One line each: what it says, and what is actually true. Surface for pruning, do not silently fix.

### 4. The plan

Steps as **bolded named items** (`Step 1a, the neutral identity for grouping`), each two or three sentences covering what it does and why it is safe at that point in the sequence. Ordering is the content: say what a step depends on and what it unblocks.

Omit this section for a pure audit with no implementation to propose.

### 5. Open questions

Last section, numbered, each marked **blocking** or **non-blocking**. At most three sentences per question: the question, the options as a short phrase each, the recommendation with a one-clause reason. The full tradeoff discussion happens in conversation only if the owner asks for it. A question with no options attached is not finished.

## Rules

- **Density over length.** Every sentence carries a fact the reader does not already have. Cut restatement, throat-clearing, and transitions that only announce what is coming.
- **Never drop a `file:line` to save space.** References are the payload, prose is the wrapper. Trim the wrapper.
- **No progress narration, in the artifact or around it.** "Now let me check", "Terrain mapped", "Six good returns" are working-log material. In the report they never appear; in the conversation, one sentence when the fan-out starts, then silence until the report.
- **Bullets enumerate options; prose carries findings.** Do not fragment a finding into bullets to look shorter.
- **Cite it or drop it.** A claim without a `file:line` from code actually read belongs in Open questions, not Findings.
- **No em dashes.** Commas, parentheses, periods, colons.
- Cap the artifact around 700 words, and treat that as a ceiling, not a target. Longer means the question needed splitting, not that the report needed more room.
