---
alwaysApply: true
---

# The content layer

Reikai serves two content types, manga and light novels, from one Reikai-owned layer over a neutral
`Entry` vocabulary, so a change written once reaches both. This file is the law. The rationale, the
measurements behind each ruling and the per-surface history live in
[content-layer-architecture.md](../../docs/dev/plans/content-layer-architecture.md) and the four
per-surface plan docs; read those before designing, read this before touching anything.

The goal is **parity and anti-divergence**, not deduplication. Collapsing two composables into one
is the mechanism, because it makes future divergence structurally impossible. The point is that a
change to one content type cannot silently miss the other.

## How deep the seam actually goes, per surface

Every surface is at a different depth. Assuming a surface is deeper than it is, is the single most
common way to mis-plan work here.

| Surface | Depth | What is actually shared | Record |
|---|---|---|---|
| Details | Deep | Neutral state and behavior contract, two adapters; Mihon composables deleted and manifested | content-layer-details-surface |
| Library | Takeover of orchestration | Shared engine owns assembly, selection and the action verbs. `LibraryScreenModel` is **live**, 930 lines, still the manga provider: the amendment's plan to delete and manifest it has not shipped, and the surface doc says it stays live as an engine file. The two docs disagree; unresolved | content-layer-library-surface |
| Migrate | Full takeover | The whole flow, screens and orchestration; seventeen Mihon files deleted and manifested | content-layer-migrate-surface |
| Browse | Behavior-partial | Shared bulk-favorite generic, shared dialogs and the default-category kernel. Verbs stay per-type, no takeover, and one neutral adder contract was assessed and declined | content-layer-browse-surface |
| History, Updates | UI leaf only | Shared row composables over per-type feeds; behavior never unified | unified-content-ui |
| Downloads | Not started | Nothing. Road B; `DownloadQueueScreenModel` is still `// RK: inert` | download-queue-unification |
| Reader | Chrome only | Top and bottom bars; the two engines are separate by design | unified-reader |

Everything below the behavior seam is scheduled to be redone at it. Sequencing is in the record, not
here.

**The two depths fail differently, so look for different things.** A taken-over surface produces
**upstream-drop** bugs: behaviour the replaced code had that ours silently lost. A UI-leaf or
behavior-partial surface produces **duplicate-implementation** bugs: one rule restated at N call
sites, with some of them wrong. Neither class shows up in the other's review.

## Ownership

- **The two engines are never merged.** Mihon's manga engine (the `Manga` model, its repositories,
  source, library and download machinery) stays upstream-tracked and minimally patched. Reikai's
  novel engine stays fully Reikai-owned. Merging them would re-type Mihon's whole stack and sever
  upstream flow.
- **Adapters are the only seam.** The shared layer talks to each engine through an adapter, so a
  renamed upstream field breaks the build at one file instead of hiding until a pixel hunt.
- **Never reimplement Mihon's spine** in the shared layer: read, download, filter, sort, selection.
  Interactors and repositories stay Mihon's and stay synced. A step that starts reimplementing what
  `setReadStatus` or `DownloadManager` does has gone too far. Three surfaces have ruled amendments
  widening this (library twice, migrate once); they are scoped to those surfaces and are not a
  general licence.
- **Identity is the sealed `EntryId`** (`reikai/domain/entry/EntryId.kt`), never a raw `Long` and
  never the retired negative-id disguise. Novels keep their own tables; novels-as-manga is ruled out.
- Placement and `// RK` fencing follow [architecture.md](architecture.md); screen shape follows
  [screen-conventions.md](screen-conventions.md). Not restated here.

## The rules that bind every change

- **Divergent bits are typed capability slots.** Never a nullable field, never a boolean-flag
  combination, never a per-type fork inside shared code. A capability one type genuinely cannot
  support is hidden for that type, never shown disabled and never a silent no-op.
- **A shared component either derives a piece of state or does not own it.** Sharing the storage
  while each type interprets it its own way is a fork wearing shared-code clothing, and nobody rules
  on it because it looks unified.
- **A behaviour test written for one engine gets its twin.** The engines stay split by design, and
  unpinned twins are where they drift. If the other type genuinely cannot do the thing, say so in a
  one-line comment rather than leaving the gap unexplained.
- **Flag every parity gap you notice on a surface you are touching**, and let the owner rule on each:
  level the lagging type up, or gate it deliberately. Never fake a feature a type cannot support.
- **Verify by mutation.** A new test is not done until the production clause it names has been
  deleted, the test seen red, and the clause restored.

## Replaced Mihon files: delete and manifest

- A pure-UI Mihon file fully replaced by a shared component is **deleted** and given a row in
  [off-path-manifest.md](../../docs/dev/off-path-manifest.md). The keep-inert rule is retired: a dead
  copy buys a diff base `refs/mihon` already provides, at the cost of a file an edit can land in.
- **Engine files are never deleted.** They stay live and minimally patched on the render path. The
  only exceptions are ruled orchestration takeovers, recorded in the manifest's own carve-out note.
- A **partially collapsed** file keeps its live remainder in place, marked `// RK` with what moved
  out, and is manifested only once nothing live remains.
- The manifest is enforced by `pre-commit` and `commit-msg` hooks plus `docs-lint`, and read by
  `scripts/off-path-check.ps1` during a sync. Treat a VANISHED report as unresolved, never expected.

## A takeover is not complete until its behaviour is inventoried

Cutting a surface over and verifying it on device is **not** the completion bar. Device verification
finds what you thought to test. The migrate surface passed that bar and then spent five audit rounds
with two upstream behaviours sitting silently dropped, both found by accident rather than by any
check: the additional search query never reached a search, and a manually picked target was accepted
without the refresh upstream refuses one on.

So a takeover is done when the replaced code's behaviour has been walked end to end and every item
marked **present**, **deliberately dropped** with the reason, or **missing**. The manifest catches
upstream changing a file after a takeover; nothing else catches what the takeover failed to carry
across in the first place. That inventory is the thing that does.
