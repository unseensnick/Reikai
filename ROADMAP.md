# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## The 0.4.0 cut

Nothing gates the 0.4.0 cut; when to cut is the owner's call.

## Later

Backlog, grouped by area. Unordered within an area.

### Quality

- **Fix the 2026-09-24 whole-repo audit findings** `[L]` - 491 confirmed defects across every surface (8 high), fixed in eleven owner-ruled batches with proper fixes at the owning layer; the work list and rulings live locally in `docs/dev/audits/2026-09-24-fix-plan.md`.

### Browse

- **Open a shared web link as a novel** `[M]` - a link shared into the app resolves to a manga through the extension that owns its site, but novel sources have no rule for turning a web address back into a novel, so a novel link falls through to a text search. Needs a typed link-resolution capability across the three novel source kinds.

### Reader

- **Auto-scroll for manga** `[M]` - an open parity gap: novels have auto-scroll, Mihon has none for manga, and TachiyomiSY and Komikku add it as a timed page flip in the reader. A timed flip through the shared reader engine needs no viewer change, but smooth long-strip scrolling would need a new viewer method, which the reader rules forbid without an owner ruling.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
