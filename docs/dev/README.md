# Reikai dev docs

Each doc here owns one question. This page is the map: which doc answers what, and which docs you update when you finish a piece of work. New here? Read [development.md](development.md) first (architecture, modules, build).

The machine-enforced conventions (commits, CHANGELOG, screen rules) live in [`.claude/rules/`](../../.claude/rules/) and are the canon; the docs below are the human-facing overview and the running records.

## The docs and what they own

**How the code works** (read the area's doc before changing it):

| Doc | Owns |
|---|---|
| [development.md](development.md) | architecture overview, module and package map, build |
| [subsystems/](subsystems/README.md) | how each area works now, one reference doc per area: purpose, flow, key files, invariants and traps, decisions, extending, tests |
| [recipes.md](recipes.md) | how to make the changes that recur: migrations, trackers, backup fields, settings, ViewModels, RK islands, upstream ports |
| [testing.md](testing.md) | running tests per module, what CI runs, the test harness, and driving a build on a device |
| [tracker-aware-duplicate-detection.md](tracker-aware-duplicate-detection.md) | the add-to-library duplicate check by tracker id |
| [readme-showcase.md](readme-showcase.md) | how the README showcase animation is captured and rebuilt |

**Records** (read by people and by scripts):

| Doc | Owns | Touch it when |
|---|---|---|
| [upstream-sync.md](upstream-sync.md) | the Mihon sync process, the deliberate divergences and the frontier (sole owner of "synced through X") | you port a Mihon commit |
| [feature-ports.md](feature-ports.md) | what was borrowed from Komikku / Tsundoku / IReader / LNReader, per feature | you port from a non-Mihon ref |
| [off-path-manifest.md](off-path-manifest.md) | Mihon files deleted for a `reikai.*` twin, and the sync check that guards them | you delete a Mihon file for a twin |
| [parked.md](parked.md) | items set aside or declined, with the reason and what would revive them | you park, decline or revive an item |
| [plans/](plans/README.md) | design still in flight; a plan is folded into its subsystem doc and deleted once the work lands | you start or finish a substantial piece of work |

## What to update when you finish something

| I just... | Update, in order |
|---|---|
| Shipped a feature | its [subsystem doc](subsystems/README.md) (rewrite the parts that changed; fold in and delete the plan, if there was one) → [CHANGELOG](../../CHANGELOG.md) (user-facing headline) → remove its line from [ROADMAP](../../ROADMAP.md) |
| Changed how an area behaves | its [subsystem doc](subsystems/README.md), in place: the flow, invariants or decisions that changed, never an appended note |
| Synced a Mihon commit | a [upstream-sync.md](upstream-sync.md) ledger row → [CHANGELOG](../../CHANGELOG.md) credit (`synced from Mihon, mihonapp/mihon#N`). Do **not** record the frontier anywhere else |
| Ported from Komikku / Tsundoku / IReader / LNReader | a [feature-ports.md](feature-ports.md) row → credit in the commit body, [README](../../README.md), and the [CHANGELOG](../../CHANGELOG.md) headline |
| Deleted a Mihon file for a `reikai.*` twin | a [off-path-manifest.md](off-path-manifest.md) row (or the next sync silently misses upstream's change to it) |
| Started a substantial piece of work | a [plans/](plans/README.md) doc + a [ROADMAP](../../ROADMAP.md) line |

## Who owns which fact

So two docs never record the same thing and drift apart:

- [ROADMAP](../../ROADMAP.md) is what's **left**. [CHANGELOG](../../CHANGELOG.md) and the release tags are what **shipped**, for users. [parked.md](parked.md) is what was **set aside**, with why and what would revive it.
- A [subsystem doc](subsystems/README.md) is the one place an area's current behaviour, invariants and decisions are written; other docs link to it, never restate it. History lives in git, not in any doc.
- [upstream-sync.md](upstream-sync.md) is the **only** place the Mihon frontier is recorded. [feature-ports.md](feature-ports.md) is the only place borrow provenance is recorded.
