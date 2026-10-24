# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work is in [CHANGELOG.md](CHANGELOG.md); how each area works is in [docs/dev/subsystems/](docs/dev/subsystems/README.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); design still in flight in [docs/dev/plans/](docs/dev/plans/README.md); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## Later

Backlog, grouped by area. Unordered within an area.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Doc](docs/dev/subsystems/content-layer.md).

### Testing & CI

- **Startup smoke test of the minified nightly APK** `[M]` - a scheduled CI job that installs the R8-minified nightly build on an emulator, launches it and walks a few screens, since R8-only crashes never show in the unminified debug build. Weekly rather than per pull request, as emulator jobs are slow and can hang. [Doc](docs/dev/testing.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
