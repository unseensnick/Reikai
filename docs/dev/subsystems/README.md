# Subsystem docs

One doc per subsystem, describing how it works **now**. These are the reference a maintainer opens before changing an area: what the pieces are, how data moves, which rules a change must keep, and which traps have already bitten.

A subsystem doc is rewritten in place whenever the code changes, never appended to. It holds no history: how a design was reached lives in git (`git log -- <path>`, the commit bodies), and what shipped when lives in [CHANGELOG.md](../../../CHANGELOG.md) and [shipped.md](../shipped.md). In-flight design work (something not built yet, or being rebuilt) still goes in [plans/](../plans/README.md); once it lands, the plan's lasting content moves into the subsystem doc and the plan is retired.

When code and a subsystem doc disagree, the code is right and the doc is a bug: fix the doc in the same change.

## Rules

- **No dates, no commit SHAs, no step, phase, slice or round language, no owner attribution** ("owner ruling", "decided on"). A decision is stated as the current choice with its reason.
- **No Status section.** Status lives in CHANGELOG and git.
- **300 lines at most.** A doc that needs more is covering two subsystems; split it.
- **Every path in `## Key files` exists**, cited as a path plus a symbol a reader can grep for, never a line number.
- No em dashes, no bare `#N` (a real issue is `owner/repo#N`).

The `subsystem-docs` check in `scripts/lint-docs.sh` enforces these.

## Template

A doc is titled with the subsystem's name and has these second-level sections, in this order. Omit one only when it would be empty. The Key files heading is spelled exactly `Key files`, since the lint finds the list by it.

1. **Purpose**: two or three sentences, what the subsystem is for and what a user gets from it.
2. **How it works**: the current end-to-end flow, the main types, how data moves between them, and both content types where both exist (say where manga and novels differ, and why).
3. **Key files**: one line each, a path that exists plus the symbol to grep for, and what it owns.
4. **Invariants and traps**: rules a change must keep and traps that already bit, a bolded rule then one or two sentences.
5. **Decisions**: each the choice, why, and the premise that would void it, at most three sentences, with no account of how it was reached.
6. **Upstream divergences**: the Mihon files carrying `// RK` patches for this subsystem, linking [upstream-sync.md](../upstream-sync.md) for any recorded divergence.
7. **Extending**: how to add the common thing here (a new surface, a new rule, a new field).
8. **Tests**: what pins the subsystem, by test class, and how to run them.
9. **Related**: the user doc page, and an active plan if one exists.

## Index

| Area | Doc |
|---|---|
| Content layer (manga and novels over one `Entry` vocabulary) | to come |
| Library | to come |
| Details | to come |
| Reader | to come |
| Recents (History, Updates) | to come |
| Downloads | to come |
| Browse and sources | to come |
| Migrate | to come |
| Merged series | [merged-series.md](merged-series.md) |
| Tracking | to come |
| Recommendations | to come |
| LN plugin host | to come |
| Cloudflare | to come |
| Adult sources | to come |
| Backup | to come |
| Dependency injection | to come |
