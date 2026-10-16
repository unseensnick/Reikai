# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work is in [CHANGELOG.md](CHANGELOG.md); how each area works is in [docs/dev/subsystems/](docs/dev/subsystems/README.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); design still in flight in [docs/dev/plans/](docs/dev/plans/README.md); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## Later

Backlog, grouped by area. Unordered within an area.

### Reader

- **Paged novel reading for both renderers** `[L]` - turn a whole screen per tap, swipe or volume key, with an instant turn and a flash for e-ink and a right-to-left page direction, asked for in unseensnick/Reikai#76. Each renderer keeps its column and one shared rule cuts it into whole-line pages, proven by a prototype on the phone and the tablet. [Plan](docs/dev/plans/paged-novel-reading.md).

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Doc](docs/dev/subsystems/content-layer.md).

### Backup & restore

- **Build backup entries in batches** `[M]` - read chapters, categories, tracks and history for a hundred series per query instead of one series at a time, for manga and novels in the shared `backupEntries` driver, as Mihon `771ba33a3` does for manga. Cuts the queries a large backup makes. [Doc](docs/dev/subsystems/data-and-backup.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
