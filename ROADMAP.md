# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work is in [CHANGELOG.md](CHANGELOG.md); how each area works is in [docs/dev/subsystems/](docs/dev/subsystems/README.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); design still in flight in [docs/dev/plans/](docs/dev/plans/README.md); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## Later

Backlog, grouped by area. Unordered within an area.

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Doc](docs/dev/subsystems/content-layer.md).

### Performance on slow phones

- **Return from a series' details without a hitch** `[M]` - on a Samsung A22 release build each return still has one frame near 200 ms, because Voyager disposes the home screen on push and the library grid composes from scratch, the cover badge row an eighth of it. Needs a ruling between keeping the home screen alive and rewriting the badge row. [Doc](docs/dev/subsystems/details.md).
- **Serve the WebView mode's script and styles by address** `[S]` - each chapter page still inlines the reader script and styles, as it did the font; worth doing only if cold opens on slow phones still lag. [Doc](docs/dev/subsystems/novel-reader-rendering.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
