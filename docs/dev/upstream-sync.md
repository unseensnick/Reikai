# Syncing with Mihon

Reikai is built on [Mihon](https://github.com/mihonapp/mihon) but is a standalone repository, not a GitHub fork, so there is no upstream remote to pull from. Upstream changes are ported **by hand** from the local `refs/mihon/` reference clone. This doc is the process, the commit convention, and the running ledger of where Reikai sits relative to upstream.

## Why by hand

`refs/mihon/` is a read-only reference clone, not a remote, so there is nothing to pull. And Reikai has diverged: `// RK` islands patch Mihon's own files, large areas are re-typed onto Reikai's models (Compose/Voyager, immutable domain), and the novel and adult code is net-new. Each upstream change is re-applied by hand and re-targeted to Reikai's shape. Model the cost as a per-change re-targeting cost.

## How to sync

1. **Find the new commits:** `git -C refs/mihon log --oneline <last-synced>..HEAD` (the last-synced SHA is the top ledger row below). Pull `refs/mihon` first; the clones live in the parent dir `refs/`, not inside `app/`.
2. **Port each commit** by the [method](#porting-method): verbatim-copy marker-free files, hand-merge `// RK` files.
3. **Drift-check** the hand-merges.
4. **Compile** (`:app:compileReleaseKotlin`) and on-device verify anything user-facing.
5. **Commit** with the [convention](#commit-convention).
6. **Append a ledger row** recording the new base and what was ported or skipped.

Port every new commit by default. Only skip one for a concrete, defensible reason (it re-implements something Reikai deliberately rewrote and would contradict live behaviour, or it is N/A like a Mihon version-code bump). Surface a skip as a choice, never decide it silently.

## Commit convention

Reference upstream PRs/issues as **`mihonapp/mihon#<n>`** (a cross-repo link). A bare `#<n>` auto-links to a *Reikai* issue and is rejected by the docs lint. Cite the upstream short-SHA too, and use `chore:`, never `feat`/`fix`.

- **Subject:** `chore: sync Mihon <what> (mihon <sha>, mihonapp/mihon#<n>)` for one commit; `chore: sync Mihon <theme>` for a batch.
- **Body:** lead with what changed and why it matters, one bullet per ported commit, note any skip with its reason. Keep it concise; the deep porting mechanics live in this ledger, not the commit.
- No em dashes, no AI watermarks (see [code-quality.md](../../.claude/rules/code-quality.md)).

## Porting method

- **Marker-free file (no `// RK`):** copy the upstream post-commit blob verbatim. Confirm Reikai sits at Mihon's pre-commit base first (`diff` the file); a clean match means the copy is safe.
- **`// RK`-patched file:** re-apply the upstream hunks by hand around the RK islands. Never let an upstream change land inside or clobber an island.
- **Drift-check:** `diff` each hand-merged file against the upstream post-commit blob. A faithful port leaves only RK-attributable hunks (an RK island, an RK-supporting import, an RK-fenced line). Any other hunk is drift, a dropped or mis-applied change.

## Recurring gotchas

- **The EXH-override tax:** changing a Reikai-`open`ed `source-api` method breaks Reikai's two EXH overrides (`exh/source/DelegatedHttpSource.kt`, `EnhancedHttpSource.kt`, absent from Mihon). Re-type both overrides too.
- **Migration numbers diverge:** never copy Mihon's `.sqm` number; Reikai's sequence is ahead. Add a new Reikai-numbered `.sqm` with the same schema change.
- **Do not `spotlessApply` a whole module** to format a sync; the repo is not spotless-clean (~56 unrelated files reformat). Format only the touched files.
- **Version and release commits are N/A:** Reikai keeps its `.y2k` identity, so skip Mihon's version-code bumps and release-artifact commits.
- **CI-action bumps go to Reikai's own workflows:** Reikai owns `build_check.yml` / `preview.yml` / `release.yml`; apply an action bump there, not to a Mihon `build.yml` Reikai lacks.
- **Coupled bumps land together:** a major dependency bump and its deprecation-fix commit must ride one commit (e.g. xmlutil 1.0.0 with its `XML.v1` migration), or the first commit will not compile.

## Synced-base ledger

Newest first. "Base" is the `refs/mihon` SHA Reikai is synced through; "Reikai" is the sync commit. For syncs older than the table, run `git log --oneline --grep="sync Mihon" -i`.

| Base (mihon) | Reikai | Date | Ported | Skipped / N/A |
|---|---|---|---|---|
| `27284a40a` | `f538e8d34` | 2026-07-04 | 4: notes background-crash serializable args (mihonapp/mihon#3515), notes text-select crash / composeRichEditor rc13 (mihonapp/mihon#3516), Shikimori GraphQL search+lookup+currentUser with a `// RK` not-in-list bind fix (mihonapp/mihon#3499), download-cache invalidation after restore (mihonapp/mihon#3096) | `mihonapp/mihon#3514` download wrong-file-check: Reikai already ships the correct logic (`3b1d34759`) |
| `0772f7202` | `d0cb409f7` | 2026-07-03 | 9: 7 dependency bumps, Shikimori `.io` (mihonapp/mihon#3497), xmlutil v1 + Compose ListItem deprecations (mihonapp/mihon#3507) | `mihonapp/mihon#3504` duplicate-images-on-resume: already fixed by Reikai `3b1d34759`, and upstream's helper logic is inverted |
| `d8c3440d3` | `77c4b0842` | 2026-07-01 | 1: resumable image downloads (mihonapp/mihon#3167) | None |
| `a82ccea6f` | `a5ebecd5b` | 2026-06-27 | 2: Gradle 9.6.1 (mihonapp/mihon#3475), Voyager 2.x (mihonapp/mihon#3466) | `19f1d00fc` Mihon v0.20.0 release |
| `6c6a07c0c` | `3484f2800` | 2026-06-26 | 3: extension-store content warning (mihonapp/mihon#3472), shortcut colors to app module (mihonapp/mihon#3473), setup-java bump | None |
| `735cea35f` | `30f53e211` | 2026-06-24 | 10: backup batching (mihonapp/mihon#3267), locale files, ChapterCache guard, deps | `1cb38a1c3` version-code bump |
| `b3e190c62` | `e9d06bced` | 2026-06-21 | 1: baseline-profile module refactor (mihonapp/mihon#3434) | None |
