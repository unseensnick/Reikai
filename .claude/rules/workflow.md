---
alwaysApply: true
---

# Fork workflow

## After completing any code change

Update `CHANGELOG.md`:

- Add a bullet under `## [Unreleased]` using these category labels: `Additions`, `Changes`, `Fixes`, `Other`.
- If `## [Unreleased]` doesn't exist, create it immediately above the most recent version entry.
- **Do not add a new entry for mid-development churn.** If you're iterating on something already in `[Unreleased]`, edit the existing bullet or leave it. Don't accumulate "fix X in feature Y" bullets when Y was added in the same `[Unreleased]` block.
- **Write for the user reading the release notes: scannable, benefit-first.** The point of a changelog is to tell a user, at a glance, what changed that affects them and whether to update. So each entry leads with a **bold user-facing phrase** (the new capability, the change you'll notice, or the in-app path) plus at most one short sentence. Group a large release's `Additions` under short area sub-headers (e.g. **Light novels**, **Library**, **Reader**) so it stays navigable instead of becoming a wall. Lead with the effect, never the implementation. **Keep technical / under-the-hood detail out of the changelog (class names, mechanisms, provenance, refactor rationale): that belongs in the commit history.** Changes with no user-facing effect (refactors, dependency / tooling bumps, infra) still get a brief plain line under `Other`. No dense multi-sentence paragraphs; no em dashes.
- **`[Unreleased]` feeds preview builds; a stable release reads its own version section.** `[Unreleased]` is the running list of what's in preview but not yet in a stable release, and the preview workflow publishes the entries added since the previous preview. Cutting a stable release renames `[Unreleased]` to `[version]` (see "Cutting a release" below); the release workflow reads only that `[version]` section, never `[Unreleased]`.

Check `README.md` and `docs/*.md` for stale references when behavior or fork-specific mechanics change. Update in the same change. Describe current behavior, not the journey: no "we tried X then switched to Y" paragraphs. Don't rewrite already-released CHANGELOG entries.

## Roadmap & plans

Two artifacts hold the forward plan. Keep them separate: the roadmap is the terse what-and-when; the plan docs are the how-and-why.

### `ROADMAP.md` (tracked, the single backlog)

Forward-looking only. Structure, top to bottom:

1. **Status table** at the top: one row per phase / area, a one-line "what", and a status cell. Terse.
2. **Now** (in progress), **Next** (queued, in priority order), **Later** (backlog, unordered). Each item is **one line**: a bold title, a size tag (`[S]` / `[M]` / `[L]`), a one-sentence "what", and a link to its plan doc in `docs/dev/plans/` when one exists. No inline plans: the detail lives in the plan doc.
3. **Parked / not building**: one line per item with a one-line reason.
4. **Shipped**: a terse done-log, grouped by area, each line ending with the commit short-SHA(s). This stays in `ROADMAP.md` so the roadmap keeps a record of what landed without the detail; full per-feature detail lives in the plan doc.

Rules: never paste an implementation plan into the roadmap (that is the failure mode this format fixes); convert relative dates to absolute; `Roadmap N` not `#N` in any prose that GitHub might auto-link.

### `docs/dev/plans/` (tracked, implementation & decision records)

A **substantial** feature or initiative gets one markdown here: a developer-facing record of what was built and why. Distinct from the architecture references already in `docs/` and `docs/dev/` (`multi-source.md`, `related-mangas.md`, `tracker-sync.md`, `ln-plugin-host.md`, etc.): cross-link those, do not duplicate them. One doc per feature; fold superseded iterations of the same feature into its single doc.

**Template** (every plan doc follows it):

- **Goal**: one or two sentences, what this delivers for the user.
- **Why**: the motivation, the parity gap, or the constraint that made it worth building.
- **Approach**: how it works now, in plain English first, then the mechanism. Describe current behavior, never the journey ("we tried X then switched to Y").
- **Key files**: the entry points a developer would open first (`file:line` or path).
- **Status**: shipped / in progress / deferred, with commit short-SHA(s).
- **Decisions & tradeoffs**: the choices made and what was deliberately left out.

Naming: real descriptive names (`novel-reader.md`, `manga-details-parity.md`), never generated slugs. `docs/dev/plans/README.md` indexes every doc with a one-line hook.

**What does NOT go here:** bug-fix plans, polish batches, scouting / audit reports, doc-edit plans, and superseded drafts stay **local** (the session plan archive), out of the repo, so `docs/dev/plans/` holds only durable feature records.

## Cutting a release (user-initiated)

When the user asks to cut a release:

1. Rename `## [Unreleased]` to the version.
2. Add a new empty `## [Unreleased]` section above it for the next cycle.

### Deferred history cleanup (do at the release-cut that ships `design/mihon-rebase` as `main`)

The goal: **the whole `design/mihon-rebase` history reads in the commit message standard below before it becomes `main`.** Many commits already on the branch predate the standard, and the Mihon-sync ones use broken reference forms (`Mihon PR #<digit>`, bare `#<digit>`) that GitHub auto-links to the wrong (Reikai) repo. They are pushed, so this is a history rewrite + force-push, only worth doing at the natural rewrite point: the cut that turns this branch into `main` (it replaces the old Yōkai-based `main`, so this is cleaning the branch's own history, not a squash-merge). The branch is single-owner, so the force-push is acceptable with the user's explicit OK.

**Recommended method (condense to one clean commit per feature, not a single squash):**

1. **Tag a backup** first (e.g. `pre-cleanup-<date>`).
2. **Scripted reference fix across all commits** (`git filter-repo --message-callback`, or `git filter-branch --msg-filter`): `Mihon PR #<n>` / `Mihon Issue #<n>` / a bare upstream `#<n>` -> `mihonapp/mihon#<n>`; ROADMAP `(#8)` -> `Roadmap N`.
3. **`git rebase -i`** to bring **every** commit to the standard: `fixup` the incremental WIP and the `docs: mark #X done` churn into their feature commit so each meaningful feature is one commit, then `reword` every surviving commit to comply, leaving none in the old style. Non-trivial commits get the lead + bullets body; for a trivial commit a clean conventional subject is itself full compliance (the standard omits the body there). Result: a fully standard-compliant, ~one-commit-per-feature history (navigable + bisectable), not one giant squash (which loses bisect).
4. **Update the `upstream-sync` memory's ledger SHAs** afterward (the rewrite changes every SHA from the edit point forward), then `--force-with-lease` and point `main` at it.

### Yōkai -> Reikai debrand (do at the release-cut)

The fork was "Yōkai-Y2K" on the old Yōkai base; the Mihon-based release ships as **Reikai**. Sweep every lingering reference (`git grep -iE 'yōkai|yokai|y2k|null2264'`, ignoring `refs/`) and sort each into one of three buckets:

- **Keep, load-bearing identity (NEVER touch):** the `.y2k` / `.debugY2k` `applicationId` suffix in `app/build.gradle.kts` (it is what upgrades existing installs in place), and the `yokai` / `y2k` / `ln_*` **preference key strings** (renaming them orphans user data; the comments beside them say why they are preserved).
- **Keep as the historical record:** the old `Yōkai-Y2K v1.9.7.5.x` **releases on `unseensnick/Reikai`**, the CHANGELOG's `1.9.7.5.x` entries + its "earlier versions tracked Yōkai" header note, and the rebase/migration docs (`docs/dev/plans/rebase-overview.md`, `docs/backup-restore.md`). These are the record of where Reikai came from.
- **Update / debrand:** user-facing + brand docs (`README.md`, `PRODUCT.md`, `DESIGN.md`), `docs/dev/development.md` (still describes the Yōkai base), and the icon art (`art/icon/*.svg`, any launcher webp carrying "Yōkai-Y2K" text). Reframe "Yōkai-era" code comments only when already editing that file (low priority, they are accurate history).

**The old releases specifically:** do NOT delete the `v1.9.7.5.x` stable releases. They are the only migration path for existing `.yokai` users (back up -> install the new `.y2k` build -> restore, per `docs/backup-restore.md`). Edit the latest one's notes to point at that migration and the new Reikai release. The old `r6xxx` "Yōkai-Y2K Nightly" pre-releases were already removed (the nightly channel is now `unseensnick/Reikai-preview`); new stable releases are named `Reikai vX`.

## Commits & PRs

After code changes, create a git commit (do not push unless asked).

### Commit message standard

Write commits a user could skim and a contributor could read on. Scale the structure to the change: a typo is one line; a feature gets a body.

**Subject (always):** `type(scope): summary`

- Types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`. Scope optional (`novel`, `library`, `reader`, `track`, `icon`, ...).
- Imperative mood, lower-case, no trailing period, aim for <=72 chars.
- **ROADMAP references: write `Roadmap N` (no `#`), never `#N`.** GitHub auto-links any `#N` token in a commit to a Reikai issue/PR, and `Roadmap #8` still triggers it (the word before `#` doesn't matter). Put the ref in a footer line (`Roadmap item 8.`) or a short subject parenthetical (`(Roadmap 8)`) if it fits.

**Body (omit only for trivial commits; wrap ~72 cols):**

1. **Lead** with 1-2 plain-language sentences: what changed and why it matters, readable by a non-developer. Never open with implementation detail.
2. **Bullets** for the notable changes, benefit-first and scannable. For a large commit, group them under short headers (a user-facing one first, e.g. the feature area, then `Under the hood:` for internals) so a reader can stop early.
3. **Footer (optional):** tests, deferred items / tradeoffs, upstream refs (`mihonapp/mihon#N`, which links to the Mihon repo; a bare `#N` would auto-link to a Reikai issue).

**Rules:** blank line after the subject; no em dashes (see [code-quality.md](code-quality.md)); no AI watermarks (no `Co-Authored-By`, no generated-by footer). Lead with the user-facing effect; keep deep internals in a labeled section.

**Pre-commit checklist, run on EVERY commit (no exceptions: this includes `docs`, `chore`, and one-line fixes, not just feature commits).** A commit that fails any line gets reworded before it lands:

1. Subject is `type(scope): summary`: a real conventional type, imperative, lower-case, no trailing period, `<=72` chars.
2. **No bare `#N` anywhere in the message** (subject or body). Roadmap ref -> `Roadmap N`; upstream ref -> `mihonapp/mihon#N`. A bare `#N` (and `Roadmap #8`, and `Mihon PR #3403`) auto-links to the wrong (Reikai) repo. This is the single most common past slip, check the body too, not just the subject.
3. No em dashes; no AI watermark (`Co-Authored-By`, generated-by footer).
4. Non-trivial commit: body leads with 1-2 plain-language sentences, then benefit-first bullets. A trivial commit is just the compliant subject (no body needed).

Example (a large feature):

```
feat(novel): track novels on AniList, MyAnimeList, MangaUpdates & Kitsu

Bind a novel to a tracker from its details screen and keep reading
progress in sync, the same way manga tracking works.

Tracking:
- Bind to any tracker you're signed into; set status, chapters read,
  score and dates from the details Tracking sheet.
- Progress syncs automatically as you read and when marking chapters read.
- Works across a merged novel's sources; each source keeps its tracker
  if you later unmerge.

Under the hood:
- Persists to the existing novel_tracks table (model, repo, interactors);
  bind/update port AddTracks.bind / BaseTracker onto it.
- Reuses Mihon's tracker services; only search needed a // RK searchNovel
  path, since Mihon's manga search excludes light novels.
- Auto-sync from the reader and details mark-read, with an offline queue.

Roadmap item 8. Tests: conversions, updater transitions, propagation.
```

A small commit needs no headers, just the subject plus a sentence or two (see the `fix(icon)` / `feat(novel)` re-queue commits in the log).

**Conventional types:** `feat:` new feature, `fix:` bug fix, `docs:` documentation only, `chore:` build / tooling, `refactor:` / `test:` / `perf:` as named.

During the Mihon rebase, all work lands on the **`design/mihon-rebase`** branch (it becomes the new `main` when the rebase ships). PRs target `design/mihon-rebase`, not `main`. Patches to Mihon's own files are fenced with `// RK -->` / `// RK <--`.

For day-to-day commit / push / PR work, run **`/ship`** (or **`/debug-fix --fast`** for hotfixes). Those bake in the conventions: no `Co-Authored-By` lines in commits, no `## Test plan` section or `🤖 Generated with [Claude Code]` footer in PR bodies, and `--repo unseensnick/Reikai` (the repo otherwise targets the wrong remote).

## Versioning (only at release-cut)

Don't bump per-push; ship alpha cycles by branch/tag. At release-cut, bump both in `app/build.gradle.kts`:

- `versionName`: Reikai uses its own Semantic Versioning in Mihon's `0.x.y` style, independent of Mihon's own number and dropping the old 5-segment `upstream.fork-patch` scheme that tracked Yōkai. The first Mihon-based release is `0.1.0`. This is only the display string; `versionCode` is what governs in-place upgrades.
- `versionCode`: integer, always increment, and it must exceed the last shipped Yōkai-based code (168) for release installs to upgrade.

## Syncing with Mihon (the live base)

Mihon upstream changes are **ported manually** from the local `refs/mihon/` clone. Never `git merge` Mihon into `design/mihon-rebase` (it would clobber Reikai patches and identity). When porting an upstream change that touches a file Reikai has patched, re-apply inside the `// RK` island.

**Commit-message reference convention (required):** in a Mihon-sync commit, reference an upstream pull request or issue as **`mihonapp/mihon#<num>`** (GitHub renders this as a link to the Mihon repo). Never a bare `#<num>` (it auto-links to a *Reikai* issue/PR) nor a bare `<num>`. Also cite the upstream short-SHA (e.g. `mihon 80541831b`). The full commit-message template, the porting method (verbatim copy for marker-free files, hand-merge inside `// RK` islands for patched ones), and the running synced-base ledger live in the **`upstream-sync` memory**.

## Porting remaining Reikai features

Reikai's own features are ported from the **`design/library-compose`** branch (the old Yōkai-based fork) per the rebase plan, re-typed onto Mihon's models. `refs/yokai/` is historical reference only.

## Identity (preserve through the rebase)

Keep Reikai identity as-is when porting: `applicationId = "eu.kanade.tachiyomi"` + `.y2k` / `.debugY2k` suffix, app name `Reikai` (i18n), `google-services.json` (gitignored, never committed), README/CHANGELOG fork sections, the `// RK` marker convention. Take Mihon for everything else.
