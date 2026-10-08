# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## The 0.4.0 cut

All six items that gated the 0.4.0 cut (owner, 2026-10-01) are done; when to cut is the owner's call.

## Later

Backlog, grouped by area. Unordered within an area.

### Browse & sources

- **One "not installed" signal for a missing source, manga and novels alike** `[S]` - on Clear database and the Migrate list a gone novel source keeps its remembered icon while a manga stub shows the red warning; give both types the same sign that the source is missing.

### Code health

- **Close the duplicate-hunt leftovers** `[S]` - the unchecked Lows of the 2026-10-08 duplicate hunt, the novel reader working out download targets twice, and one name for the member whose chapter settings a merged group uses (today the first by source priority, while the library card picks its lead by ranking).

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
