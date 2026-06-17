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

## Cutting a release (user-initiated)

When the user asks to cut a release:

1. Rename `## [Unreleased]` to the version.
2. Add a new empty `## [Unreleased]` section above it for the next cycle.

## Commits & PRs

After code changes, create a git commit (do not push unless asked). Conventional commits with optional scope:

- `feat:` / `feat(scope):` — new feature
- `fix:` — bug fix
- `docs:` — documentation only
- `chore:` — build / tooling

During the Mihon rebase, all work lands on the **`design/mihon-rebase`** branch (it becomes the new `main` when the rebase ships). PRs target `design/mihon-rebase`, not `main`. Patches to Mihon's own files are fenced with `// RK -->` / `// RK <--`.

For day-to-day commit / push / PR work, run **`/ship`** (or **`/debug-fix --fast`** for hotfixes). Those bake in the conventions: no `Co-Authored-By` lines in commits, no `## Test plan` section or `🤖 Generated with [Claude Code]` footer in PR bodies, and `--repo unseensnick/Reikai` (the repo otherwise targets the wrong remote).

## Versioning (only at release-cut)

Don't bump per-push; ship alpha cycles by branch/tag. At release-cut, bump both in `app/build.gradle.kts`:

- `versionName` — the scheme for the Mihon era is a release-cut decision (the old `upstream.fork-patch` 5-segment scheme tracked Yōkai; the upstream segment now tracks Mihon). The rebase foundation carried `1.9.7.5.10` / code `169` for in-place-upgrade continuity.
- `versionCode` — integer, always increment, and it must exceed the last shipped Yōkai-based code (168) for release installs to upgrade.

## Syncing with Mihon (the live base)

Mihon upstream changes are **ported manually** from the local `refs/mihon/` clone. Never `git merge` Mihon into `design/mihon-rebase` (it would clobber Reikai patches and identity). When porting an upstream change that touches a file Reikai has patched, re-apply inside the `// RK` island.

## Porting remaining Reikai features

Reikai's own features are ported from the **`design/library-compose`** branch (the old Yōkai-based fork) per the rebase plan, re-typed onto Mihon's models. `refs/yokai/` is historical reference only.

## Identity (preserve through the rebase)

Keep Reikai identity as-is when porting: `applicationId = "eu.kanade.tachiyomi"` + `.y2k` / `.debugY2k` suffix, app name `Reikai` (i18n), `google-services.json` (gitignored, never committed), README/CHANGELOG fork sections, the `// RK` marker convention. Take Mihon for everything else.
