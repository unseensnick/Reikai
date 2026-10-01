# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## The 0.4.0 cut

Four items gate the 0.4.0 cut (owner, 2026-10-01); when to cut after them is the owner's call.

- **Run the targeted duplicate, performance and security check** `[M]` - read-only: a loop-until-dry search for code written twice in Reikai's own code, plus dedicated performance and security reviews of the hot-path and untrusted-input code.
- **Remove the remaining duplicated code before the cut** `[L]` - the 95 open findings from the 2026-10-01 re-check (15 already behave differently for a user) plus whatever the check above confirms, fixed with one shared rule each for manga and novels.
- **Audit the user docs against the app** `[M]` - check every page the website publishes (the guides under `docs/` and the site's own pages) against current behaviour, since this cycle moved settings, merged lists and replaced the novel reader; fix what is stale.
- **Ground every 0.4.0 changelog entry** `[M]` - check each bullet's claim, setting path and wording against the code and the shipped strings after the duplicate fixes land, the same mechanical pass the 0.3.x audit used.

## Later

Backlog, grouped by area. Unordered within an area.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
