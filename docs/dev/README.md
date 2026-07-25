# Reikai dev docs

Each doc here owns one question. This page is the map: which doc answers what, and which docs you update when you finish a piece of work. New here? Read [development.md](development.md) first (architecture, modules, build).

The machine-enforced conventions (commits, CHANGELOG, screen rules) live in [`.claude/rules/`](../../.claude/rules/) and are the canon; the docs below are the human-facing overview and the running records.

## The docs and what they own

**Process & records** (how work is tracked):

| Doc | Owns | Touch it when |
|---|---|---|
| [upstream-sync.md](upstream-sync.md) | the Mihon sync process and the frontier (sole owner of "synced through X") | you port a Mihon commit |
| [feature-ports.md](feature-ports.md) | what was borrowed from Komikku / Tsundoku / LNReader, per feature | you port from a non-Mihon ref |
| [off-path-manifest.md](off-path-manifest.md) | Mihon files deleted for a `reikai.*` twin, and the sync check that guards them | you delete a Mihon file for a twin |
| [shipped.md](shipped.md) | terse done-log of what landed, by area | a feature ships |
| [plans/](plans/README.md) | one per-feature record (how and why), indexed | you build or finish a substantial feature |

**Architecture & reference** (how the code works, cross-linked by the records above):

| Doc | Owns |
|---|---|
| [development.md](development.md) | architecture, module map, build, reference clones |
| [ln-plugin-host.md](ln-plugin-host.md) | the light-novel plugin host: navigation handbook, layer map, shim recipes |
| [on-device-testing.md](on-device-testing.md) | running and verifying builds on a device |
| [readme-showcase.md](readme-showcase.md) | how the README showcase animation is captured and rebuilt |
| [tracker-aware-duplicate-detection.md](tracker-aware-duplicate-detection.md) | the add-to-library duplicate-detection mechanism |

## What to update when you finish something

The file-to-file workflow. Do these in order; skip a row's steps that don't apply.

| I just... | Update, in order |
|---|---|
| Shipped a feature | its [plans/](plans/README.md) doc `Status` → [CHANGELOG](../../CHANGELOG.md) (user-facing headline) → [shipped.md](shipped.md) (one line + short-SHA + plan link) → remove its line from [ROADMAP](../../ROADMAP.md) |
| Synced a Mihon commit | add a [upstream-sync.md](upstream-sync.md) ledger row → [CHANGELOG](../../CHANGELOG.md) credit (`synced from Mihon, mihonapp/mihon#N`). Do **not** record the frontier anywhere else |
| Ported from Komikku / Tsundoku / LNReader | a [feature-ports.md](feature-ports.md) row → credit in the commit body, [README](../../README.md), and the [CHANGELOG](../../CHANGELOG.md) headline |
| Deleted a Mihon file for a `reikai.*` twin | add a [off-path-manifest.md](off-path-manifest.md) row (or the next sync silently misses upstream's change to it) |
| Started a substantial feature | a new [plans/](plans/README.md) doc (template in its README) + a [ROADMAP](../../ROADMAP.md) line |

## Who owns which fact

So two docs never record the same thing and drift apart:

- [ROADMAP](../../ROADMAP.md) is what's **left** (forward only, no shipped log). [CHANGELOG](../../CHANGELOG.md) is what changed **for users**. [shipped.md](shipped.md) is what **shipped** (a dev log, may name sources).
- [upstream-sync.md](upstream-sync.md) is the **only** place the Mihon frontier is recorded. [feature-ports.md](feature-ports.md) is the only place borrow provenance is recorded.
- [plans/](plans/README.md) owns per-feature detail; [shipped.md](shipped.md) and the user docs point to it, never restate it.
