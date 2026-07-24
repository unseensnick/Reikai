---
alwaysApply: true
---

# Prose style

Sentence-level writing for every output: chat replies, plans and reports, commit messages, docs, code comments, PR bodies. [plan-output.md](plan-output.md) governs the structure of a report; this governs the sentences inside it.

The target is how a competent engineer writes when explaining something to a colleague. Most of the patterns below are drawn from [Wikipedia's field guide to AI writing](https://en.wikipedia.org/wiki/Wikipedia:Signs_of_AI_writing), which catalogues what machine-written text does that human-written text does not.

## The habits to drop

Ordered by how often they show up.

**Trailing significance clauses.** An "-ing" phrase tacked onto a sentence to assert that the fact matters: "..., highlighting the importance of X", "..., ensuring consistency", "..., reflecting a broader shift", "..., contributing to maintainability". State the mechanism or drop the clause. "The engine owns the selection, ensuring correctness across content types" becomes "The engine owns the selection, because neither model can see the other's rows." A trailing clause that names a concrete consequence is fine; one that only asserts significance is filler.

**Inflated significance.** "Stands as a testament to", "plays a crucial role in", "marks a pivotal moment", "underscores the importance of", "represents a shift toward". Technical work rarely needs this register. Say what changed and what it costs.

**Negative parallelism.** "Not just X, but Y", "It isn't X, it's Y", "X rather than Y". Genuinely useful once in a while for a real contrast, which is why overuse is so noticeable. At most once per reply, and only when the reader would otherwise land on the wrong reading.

**Padded triples.** "Filter, sort, and search" is fine when there are exactly three things. "Clean, maintainable, and robust" is padding shaped like content. Never invent a third item for cadence.

**Copulative avoidance.** "Serves as", "functions as", "stands as", "acts as", "represents" where "is" would do. Same for "features", "boasts", "offers", "maintains" where "has" would do. Prefer the plain verb.

**Elegant variation.** Cycling synonyms so a word is not repeated ("the selection", then "the chosen set", then "the marked entries"). Repeating the exact term is clearer in technical writing, where a synonym reads as a different thing.

**Bolded-header list reflex.** A vertical list where every item is "**Short header:** sentence". Sometimes right, but it becomes a tic. Prose carries reasoning better; reserve lists for genuine enumerations (options, steps, files).

**Boldface as emphasis spray.** Bolding several phrases per paragraph flattens the signal. Bold the claim a skimmer must not miss, then stop.

**Conversational filler.** "Certainly!", "Of course!", "Great question", "You're absolutely right", "I hope this helps", "Let me know if you'd like...". Answer instead. A closing offer is fine when there is a real fork; it is noise when appended by reflex.

**Didactic disclaimers.** "It's important to note that", "It's worth mentioning", "Keep in mind that". If it matters, state it. If it does not, cut it.

**Wrap-up restatement.** "In summary", "In conclusion", "Overall", or a closing paragraph that repeats what was just said. Stop when the content stops. Docs do not get a "Challenges and Future Directions" section unless there is specific, named future work.

**Title Case Headings.** Sentence case for every heading, in docs and in replies.

**Vague attribution.** "Best practice suggests", "it's generally considered", "the codebase implies". Name the file, the rule, or the person. Unattributable claims belong in open questions.

**Decorative emoji and curly quotes.** No emoji as bullets or heading ornaments. Straight quotes and apostrophes.

## Vocabulary

Overused enough to read as machine-written. The right column is a hint; use whatever word actually fits.

| Avoid | Prefer |
|---|---|
| delve into, explore | read, check, look at |
| crucial, vital, pivotal, key | important, or name the actual stakes |
| robust, comprehensive, seamless | say what it handles and what it does not |
| leverage, utilize | use |
| showcase, highlight, underscore, demonstrate | show, or just state the fact |
| foster, enhance, streamline, facilitate | improve, speed up, simplify |
| align with, resonate with | match, fit |
| landscape, ecosystem, tapestry (figurative) | name the actual thing |
| intricate, nuanced, meticulous | complicated, careful, or drop it |
| testament to, hallmark of | evidence of, or drop it |
| Additionally, Furthermore, Moreover (sentence-initial) | also, and, or nothing |
| ensure | make sure, guarantee, or name the mechanism |
| authored, relocated, attempted, purchase | wrote, moved, tried, buy |

## What to do more of

Human technical writing does these; machine writing avoids them.

Plain copulatives: "there is a", "it has two callers". Short declaratives next to long ones, so rhythm varies. Definite statements when the evidence supports one ("this is the only caller", "that never fires"), instead of hedging everything to the same middling confidence. Real hedges when confidence is genuinely low ("probably", "I think", "worth checking"); uniform confidence across every sentence is its own tell. Ordinary phrasing where it reads naturally, including "because of", "in order to", and "the fact that". Admitting a gap directly: "I don't know", "I didn't check that", "I got that wrong."

## Calibration

This is about register, not a banned-words filter. A word on the avoid list is fine when it is the precise term: `robust` in a citation of code that is named that, `key` for a map key, `landscape` for an actual one. The failure this prevents is uniform, inflated, evenly-hedged prose where every sentence carries the same weight. Direct and specific beats polished.

Fewer words is usually the fix. When a sentence feels wrong and the cause is unclear, cut it and see whether anything was lost.
