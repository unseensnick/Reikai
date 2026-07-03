---
alwaysApply: true
---

# Fork workflow

## After completing any code change

Update `CHANGELOG.md`:

- Add a bullet under `## [Unreleased]` using these category labels: `Additions`, `Changes`, `Fixes`, `Other`.
- If `## [Unreleased]` doesn't exist, create it immediately above the most recent version entry.
- **Do not add a new entry for mid-development churn.** If you're iterating on something already in `[Unreleased]`, edit the existing bullet or leave it. Don't accumulate "fix X in feature Y" bullets when Y was added in the same `[Unreleased]` block.
- **Write for the user reading the release notes: scannable, benefit-first.** The point of a changelog is to tell a user, at a glance, what changed that affects them and whether to update. So each entry (in `Additions` / `Changes` / `Fixes`) leads with a **self-contained bold headline**: a complete user-facing phrase ending in `.`, `!`, or `?`, followed by **at most one short sentence**. The release pipeline auto-extracts that bold headline as the one-line release note (the trailing sentence stays only in the file, linked as the full changelog), so the headline must read on its own, e.g. `**Merging a series' sources now takes one tap.**`, not `**New options:** resume position, ...`. Group a large release's `Additions` under short area sub-headers (e.g. **Light novels**, **Library**, **Reader**) so it stays navigable instead of becoming a wall. Lead with the effect, never the implementation. **Keep technical / under-the-hood detail out of the changelog (class names, mechanisms, provenance, refactor rationale): that belongs in the commit history.** Changes with no user-facing effect (refactors, dependency / tooling bumps, infra) get a brief plain line under `Other` (no bold headline needed there). No dense multi-sentence paragraphs; no em dashes; no content-source names (see "Public-facing naming"). A `pre-commit` hook (`.githooks/pre-commit`) enforces the bold headline, the no-source-names rule, and a length cap on new `[Unreleased]` entries.
- **`[Unreleased]` feeds preview builds; a stable release reads its own version section.** `[Unreleased]` is the running list of what's in preview but not yet in a stable release, and the preview workflow publishes the entries added since the previous preview. Cutting a stable release renames `[Unreleased]` to `[version]` (see "Cutting a release" below); the release workflow reads only that `[version]` section, never `[Unreleased]`.

Check `README.md` and `docs/*.md` for stale references when behavior or fork-specific mechanics change. Update in the same change. Describe current behavior, not the journey: no "we tried X then switched to Y" paragraphs. Don't rewrite already-released CHANGELOG entries.

## Roadmap & plans

Two artifacts hold the forward plan. Keep them separate: the roadmap is the terse what-and-when; the plan docs are the how-and-why.

### `ROADMAP.md` (tracked, the single forward backlog)

**Forward-looking only.** It holds what is *left* to build, never what already shipped. Structure, top to bottom:

1. **Intro**: two lines pointing to `docs/dev/shipped.md` (done-log), `docs/dev/plans/` (detail), `Handoff.md` (session state), and this file (format).
2. **Now** (in progress), **Next** (queued, in priority order), **Later** (backlog). Each item is **one line**: a bold title, a size tag (`[S]` / `[M]` / `[L]`), a one-sentence "what", and a link to its plan doc when one exists. No inline plans: the detail lives in the plan doc.
3. **Later is grouped by stable area** (Library, Reader, Novels, Recommendations, adult sources, ...), never by phase. Phases are a plan artifact and rot; areas are durable. Only include areas that have open items.
4. **Parked / not building**: one line per item, a one-line reason, a link if there's a plan/decision doc. Verbose rationale goes in the plan doc, not here.

**No Status table, no Shipped section, no audit prose in this file.** Shipped work moves to [docs/dev/shipped.md](../../docs/dev/shipped.md) (a terse done-log, grouped by area, each line ending in the commit short-SHA(s); a dev record, so it *may* name sources). Audit reports live in `docs/dev/audits/` (local / gitignored; only their action items become roadmap lines). Decisions and rationale live in `docs/dev/plans/`.

**Naming (enforced):** `ROADMAP.md` is a semi-public surface, so it stays generic about content sources, use `EH` / `ExH` shorthand and collective phrasing ("the built-in adult sources"), never a specific source name (`nhentai`, `pururin`, ...). The dev-record files (`docs/dev/shipped.md`, `docs/dev/plans/`, local `docs/dev/audits/`) may name sources freely. This mirrors the CHANGELOG rule (see "Public-facing naming").

**Other rules:** never paste an implementation plan into the roadmap; convert relative dates to absolute; no em dashes; `Roadmap N` (never a bare `#N`), a real issue/PR uses `owner/repo#N`. A `pre-commit` hook + the `docs-lint` CI enforce the three hard rules on `ROADMAP.md`: no content-source names, no em dash, no bare `#N`. Structural rules (one-line items, size tags, area grouping) are convention, not linted; review catches them.

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

## Commits & PRs

After code changes, create a git commit (do not push unless asked).

### Public-facing naming (keep content sources out of marketing surfaces)

Public, indexed, marketing-facing surfaces stay generic about content sources: no specific source names (especially adult: `ehentai`, `nhentai`, `pururin`, `8muses`, ...) and no content-aggregation vocabulary (`source`, `gallery`, `scanlator`) used as a label. This covers the **repo description + topics, the README, GitHub release notes (and the CHANGELOG version sections that feed them), and branch / PR names**. Describe the capability generically ("adult content sources", "a Cloudflare-blocked source") and link to the detailed docs instead of listing sites. Branch example: `fix/paging-crash`, not `fix/ehentai-popular-pagination` or `fix/source-browse-pagination`.

Exceptions that are fine: the **detailed feature docs** (e.g. `docs/adult-sources.md`) MAY name specific sources, they are reference, not marketing, and stay linked from a generic README bullet. **In-app strings and source code** name sources as the actual feature requires (don't break functionality). **Commit history** is left as-is (rewriting it is destructive).

Why: avoid the project reading as a piracy tool (the attention that hit Tachiyomi). Calibration: Komikku (same lineage, healthy) names adult sources openly in its README and release notes while keeping its repo description + topics generic. **Reikai stays a notch tighter:** generic in every marketing surface too, with specific names only in the linked docs.

### Commit message standard

Write commits a user could skim and a contributor could read on. Scale the structure to the change: a typo is one line; a feature gets a body.

**Subject (always):** `type(scope): summary`

- Types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`. Scope optional (`novel`, `library`, `reader`, `track`, `icon`, ...).
- Imperative mood, lower-case, no trailing period, aim for <=72 chars.
- **ROADMAP references: write `Roadmap N` (no `#`), never `#N`.** GitHub auto-links any `#N` token in a commit to a Reikai issue/PR, and `Roadmap #8` still triggers it (the word before `#` doesn't matter). Put the ref in a footer line (`Roadmap item 8.`) or a short subject parenthetical (`(Roadmap 8)`) if it fits. To link an actual GitHub issue or PR, use the explicit `owner/repo#N` form (`unseensnick/Reikai#N` for our own, `mihonapp/mihon#N` for upstream), never a bare `#N`.

**Body (omit only for trivial commits; wrap ~72 cols):**

1. **Lead** with 1-2 plain-language sentences: what changed and why it matters, readable by a non-developer. Never open with implementation detail.
2. **Bullets** for the notable changes, benefit-first and scannable. For a large commit, group them under short headers (a user-facing one first, e.g. the feature area, then `Under the hood:` for internals) so a reader can stop early.
3. **Footer (optional):** tests, deferred items / tradeoffs, upstream refs (`mihonapp/mihon#N`, which links to the Mihon repo; a bare `#N` would auto-link to a Reikai issue).

**Rules:** blank line after the subject; no em dashes (see [code-quality.md](code-quality.md)); no AI watermarks (no `Co-Authored-By`, no generated-by footer). Lead with the user-facing effect; keep deep internals in a labeled section.

**Pre-commit checklist, run on EVERY commit (no exceptions: this includes `docs`, `chore`, and one-line fixes, not just feature commits).** A commit that fails any line gets reworded before it lands:

1. Subject is `type(scope): summary`: a real conventional type, imperative, lower-case, no trailing period, `<=72` chars.
2. **No bare `#N` anywhere in the message** (subject or body): it is ambiguous (a misformatted roadmap or upstream ref vs a real issue, indistinguishable to GitHub and the hook). A roadmap item is `Roadmap N` (not a GitHub issue, so no `#`); a real issue/PR uses the explicit `owner/repo#N` form, `unseensnick/Reikai#N` for our own and `mihonapp/mihon#N` for upstream, which links correctly and is unambiguous. A bare `#N` (and `Roadmap #8`, `Mihon PR #3403`) is the single most common past slip, check the body too, not just the subject.
3. No em dashes; no AI watermark (`Co-Authored-By`, generated-by footer).
4. Non-trivial commit: body leads with 1-2 plain-language sentences, then benefit-first bullets. A trivial commit is just the compliant subject (no body needed).

A **`commit-msg` git hook enforces this** automatically: `.githooks/commit-msg` (tracked) is installed at `.git/hooks/commit-msg` and rejects a non-compliant message (bad subject, over-72 subject, bare `#<number>`, em dash, AI watermark). A companion **`pre-commit` hook** (`.githooks/pre-commit`) lints a staged `CHANGELOG.md` (no content-source names in added lines; a self-contained bold headline + length cap on new `[Unreleased]` entries); the same checks run in CI via `.github/workflows/changelog-lint.yml`. Reinstall both on a fresh clone with `cp .githooks/commit-msg .githooks/pre-commit .git/hooks/ && chmod +x .git/hooks/commit-msg .git/hooks/pre-commit`. They leave `.git/hooks/` as the hooks path so Kotlinter's pre-push hook still works.

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

Work lands on `main` (the Mihon-based main). PRs target `main`. Patches to Mihon's own files are fenced with `// RK -->` / `// RK <--`.

For day-to-day commit / push / PR work, run **`/ship`** (or **`/debug-fix --fast`** for hotfixes). Those bake in the conventions: no `Co-Authored-By` lines in commits, no `## Test plan` section or `🤖 Generated with [Claude Code]` footer in PR bodies, and `--repo unseensnick/Reikai` (kept explicit; the repo is standalone with `gh`'s default set to it, so this is optional now).

## Versioning (only at release-cut)

Don't bump per-push; ship alpha cycles by branch/tag. At release-cut, bump both in `app/build.gradle.kts`:

- `versionName`: Reikai uses its own Semantic Versioning in Mihon's `0.x.y` style, independent of Mihon's own number and dropping the old 5-segment `upstream.fork-patch` scheme that tracked Yōkai. The first Mihon-based release is `0.1.0`. This is only the display string; `versionCode` is what governs in-place upgrades.
- `versionCode`: integer, always increment, and it must exceed the last shipped Yōkai-based code (168) for release installs to upgrade.

## Syncing with Mihon (the live base)

Mihon upstream changes are **ported manually** from the local `refs/mihon/` clone. When porting an upstream change that touches a file Reikai has patched, re-apply inside the `// RK` island.

**Commit-message reference convention (required):** in a Mihon-sync commit, reference an upstream pull request or issue as **`mihonapp/mihon#<num>`** (GitHub renders this as a link to the Mihon repo). Never a bare `#<num>` (it auto-links to a *Reikai* issue/PR) nor a bare `<num>`. Also cite the upstream short-SHA (e.g. `mihon 80541831b`).

The full process, commit convention, porting method (verbatim copy for marker-free files, hand-merge inside `// RK` islands for patched ones), recurring gotchas, and the running synced-base ledger live in **[docs/dev/upstream-sync.md](../../docs/dev/upstream-sync.md)**. Append a ledger row on every sync. A `docs-lint` CI check plus the `pre-commit` hook enforce no em dash and no bare `#N` in that doc (content-source names are allowed there, it is a dev record).

## Porting remaining Reikai features

Any remaining Reikai-own feature (the parity backlog is mostly shipped) is ported from the **`design/library-compose`** branch (the old Yōkai-based fork) per the rebase plan, re-typed onto Mihon's models. `refs/yokai/` is historical reference only.

## Identity (preserve through the rebase)

Keep Reikai identity as-is when porting: `applicationId = "eu.kanade.tachiyomi"` + `.y2k` / `.debugY2k` suffix, app name `Reikai` (i18n), `google-services.json` (gitignored, never committed), README/CHANGELOG fork sections, the `// RK` marker convention. Take Mihon for everything else.
