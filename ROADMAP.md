# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work lives in [docs/dev/shipped.md](docs/dev/shipped.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); per-feature detail and decisions in [docs/dev/plans/](docs/dev/plans/); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## The 0.4.0 cut

One item gates the 0.4.0 cut; once it is done, when to cut is the owner's call.

- **Compiled-APK novel extensions** `[XL]` - the item under Novel sources & LN plugins below.

The light-novel trackers do not gate it; MyNovelList is in [parked.md](docs/dev/parked.md), and the rulings behind both are in [novel-specific-trackers.md](docs/dev/plans/novel-specific-trackers.md).

## Later

Backlog, grouped by area. Unordered within an area.

### Novels (manga <-> novel parity)

Remaining manga/novel parity work, smaller enhancements and polish. The write-once rule (`.claude/rules/content-layer.md`) is forward-only, so this pre-existing backlog is labelled rather than blocking: **open gap** is parity Reikai owes with nothing preventing it, **gated** names the mechanism the content type cannot support, and a gate holds only until that mechanism changes.

Opportunistic polish:
- Browse: map a tapped genre onto a novel plugin's filters; the shared catalogue passes the genre-search hook on the manga branch only, so a novel source falls back to a plain text query.
- Global search: opening on Pinned-only with nothing pinned shows a bare empty screen, on both content types since the shared screen took over. Default to All, or say the list is empty because nothing is pinned.
- Details: the scanlator filter on a merged manga lists and excludes only the anchor entry's scanlators, while the unified list shows siblings' chapters, so the dialog and the query disagree about what can be hidden.
- Novel reader: a tall picture keeps the shrunken stand-in copy it was drawn from once its slices cover it, about 4MB a picture; dropping it is the obvious memory saving. [Record](docs/dev/plans/content-layer-reader-surface.md).

### Novel sources & LN plugins

- **Compiled-APK novel extensions (tsundoku / IReader repos)** `[XL]` - load both APK novel-extension ecosystems beside the LN plugins, with source-side tracking and tracker auto-binding for both content types. Requested in `unseensnick/Reikai#31`; holds the 0.4.0 cut. [Plan](docs/dev/plans/content-layer-sources-surface.md).

### Data & backup

- **Share the backup entry loop behind one neutral driver** `[M]` - one adapter answering chapters, categories, tracks and history per `EntryId`, so the option gates and flush cadence are written once rather than per content type. It collapses the gates, never the fields, which the frozen wire format rules out. [Plan](docs/dev/plans/content-layer-architecture.md).

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Plan](docs/dev/plans/unified-content-ui.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.