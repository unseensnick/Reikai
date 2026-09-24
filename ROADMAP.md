# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## The 0.4.0 cut

Nothing gates the 0.4.0 cut; when to cut is the owner's call.

## Later

Backlog, grouped by area. Unordered within an area.

### Novels (manga <-> novel parity)

Remaining manga/novel parity work, smaller enhancements and polish. The write-once rule (`.claude/rules/content-layer.md`) is forward-only, so this pre-existing backlog does not block other work. Gated parity items, each naming the mechanism the content type cannot support, live in [docs/dev/parked.md](docs/dev/parked.md), and a gate holds only until that mechanism changes.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
