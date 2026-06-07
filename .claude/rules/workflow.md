---
alwaysApply: true
---

# Fork workflow

## After completing any code change

Update `CHANGELOG.md`:

- Add a bullet under `## [Unreleased]` using these category labels: `Additions`, `Changes`, `Fixes`, `Other`.
- If `## [Unreleased]` doesn't exist, create it immediately above the most recent version entry.
- **Do not add a new entry for mid-development churn** — if you're iterating on something already in `[Unreleased]`, edit the existing bullet or leave it. Don't accumulate "fix X in feature Y" bullets when Y was added in the same `[Unreleased]` block.
- **Write for release notes, not for yourself.** `[Unreleased]` becomes the GitHub release draft, so users skimming it want *what changed, where it lives, what it does* — not the implementation rationale. Avoid formulas, class names, internal phase numbering, network protocol detail, migration numbers, and extension-family taxonomy. Keep each bullet to 1–3 sentences; lead with the user-visible effect or in-app path. Implementation context belongs in commit messages, not the changelog.

Check `README.md` and `docs/*.md` for stale references when behavior, file paths called out in docs, or fork-specific mechanics change. Update in the same change.

Documentation rule: describe current behavior, not the journey. No "we tried X then switched to Y" paragraphs, no notes about temporary workarounds that have since been removed. Don't rewrite already-released CHANGELOG entries — those are historical.

## Cutting a release (user-initiated)

When the user asks to cut a release:

1. Rename `## [Unreleased]` to the version (e.g. `## [1.9.7.5.6]`).
2. Add a new empty `## [Unreleased]` section above it for the next cycle.

## Commits & PRs

After code changes, create a git commit (do not push). Conventional commits with optional scope:

- `feat:` / `feat(scope):` — new feature
- `fix:` — bug fix
- `docs:` — documentation only
- `chore:` — build / tooling

For day-to-day commit / push / PR work, run **`/ship`** (or **`/debug-fix --fast`** for hotfixes). Those skills walk the scan → stage → commit → push → PR flow with this project's conventions baked in — including no `Co-Authored-By` lines in commits, no `## Test plan` section or `🤖 Generated with [Claude Code]` footer in PR bodies, and the `--repo unseensnick/Reikai --base main` flags `gh` needs (the repo is a fork of `null2264/yokai`, and `gh` otherwise targets upstream).

## Versioning (only at release-cut)

Don't bump per-push. Alpha cycles ship by branch/tag; the version bump happens only when the user asks to cut a release. At that point, bump both in `app/build.gradle.kts`:

- `_versionName` — 5-segment `upstream.fork-patch` (e.g. `1.9.7.5.6`).
- `versionCode` — integer, always increment.

## Syncing with upstream

Upstream changes are **ported manually** from the local `refs/yokai/` clone — never `git merge upstream/master` into any branch (would replay rebrand conflicts and overwrite Reikai-only screens). Re-target ports to the Compose screen where Reikai has migrated ahead of upstream.

See `docs/dev/development.md` for the full walkthrough.

When porting, keep **Reikai identity** as-is (`applicationId`, app name, `.y2k` packaging suffix, workflow refs, README/CHANGELOG fork sections, `google-services.json`); take upstream for everything else.
