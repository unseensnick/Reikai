---
alwaysApply: true
---

# Fork workflow

## After completing any code change

Update `CHANGELOG.md`:

- Add a bullet under `## [Unreleased]` using these category labels: `Additions`, `Changes`, `Fixes`, `Other`.
- If `## [Unreleased]` doesn't exist, create it immediately above the most recent version entry.
- **Do not add a new entry for mid-development churn.** If you're iterating on something already in `[Unreleased]`, edit the existing bullet or leave it. Don't accumulate "fix X in feature Y" bullets when Y was added in the same `[Unreleased]` block.
- **Write for release notes, not for yourself.** `[Unreleased]` becomes the GitHub release draft, so users skimming it want what changed, where it lives, what it does, not the implementation rationale. Avoid formulas, class names, internal phase numbering, migration numbers. Keep each bullet to 1-3 sentences; lead with the user-visible effect or in-app path. Implementation context belongs in commit messages.

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
