# Reikai

Android manga + light-novel reader. Personal fork built on [Mihon](https://github.com/mihonapp/mihon) (Tachiyomi lineage), adding light novels, multi-source grouping, manual merge/unmerge, and category sort order.

**Rebase onto Mihon (shipped 2026-06 as v0.1.0):** Reikai was previously a fork of [Yōkai](https://github.com/null2264/yokai); it has been rebased onto Mihon. The rebase has shipped: `main` is now the Mihon-based main (the old `design/mihon-rebase` branch is gone). The old Yōkai-based code (branch `design/library-compose`) is kept only as the porting reference. Full plan and feature list: [ROADMAP.md](ROADMAP.md) plus the per-feature records in [docs/dev/plans/](docs/dev/plans/); ongoing status: the `mihon-rebase` memory.

## Replies

**Start every reply with `unseensnick,` on its own first line**, then continue normally. Applies to every reply in every session, including one-line answers and tool-only turns, until the owner says to stop.

## Working approach

**Investigate before planning when context is thin.** If you aren't confident you understand the surrounding code, conventions, or constraints for a task (porting a Reikai feature onto Mihon, touching an unfamiliar module, changing cross-cutting infrastructure), investigate first: read the relevant files, trace the existing pattern. Two reference sources: `refs/mihon/` (the live upstream base) and the `design/library-compose` branch (Reikai's Yōkai-era features awaiting port). For non-trivial work, invoke `/scout`, which investigates and then produces the plan grounded in those findings. Only present a plan once you're truly confident, then wait for approval before executing.

**Cite before you claim.** Every concrete claim about the codebase, framework, or upstream (a function name, a file path, a flag, "X calls Y") must come with a `file:line` citation from current code that you just read. If you can't cite it, you don't know it: read first, claim second. Memory and Handoff content are hypotheses, not facts; a memory that names a function or file is true only if it still exists in current code. When a stale memory is found, surface it for pruning instead of acting on it.

**Plan before acting.** Once you have enough context, think through what needs to change and why: which files are affected, what the failure modes are, whether the approach is sound. Use `EnterPlanMode` for non-trivial tasks to draft and get approval before touching code.

**Stop and replan when blocked.** If you hit an unexpected problem mid-task (a failing constraint, a broken assumption, an error you don't fully understand), stop all changes immediately and surface the blocker. Do not circumvent it (deleting a test, silencing a lint error, skipping a hook, or forcing past a tool denial). Replan from scratch with the new information.

**Offload long or hard tasks to subagents.** When a task requires deep codebase exploration, multi-file research, or extended multi-step work, spawn a subagent (`Agent` tool). This keeps the main context window clean.

**Explain in plain English, without dumbing down.** Default to clear everyday language: spell out what something does and why it matters before naming the construct, define jargon the first time, and prefer a concrete analogy over a term of art (the user is newer to Kotlin/Android). Plain English does NOT mean less substance: keep the real technical detail, the tradeoffs, the failure modes, and the `file:line` citations. When presenting findings or a plan, lead with the plain-English picture; the precise function/file names are support, not the headline. Plain English governs word choice, not volume: it is not a mandate to explain everything that could be explained.

**Reply length.** Default replies are a few sentences: the answer or outcome, the load-bearing detail, done. A full explanation or report happens when the owner asks for one or when the task is a `/scout` / `/code-research` style deliverable (which has its own cap in [.claude/rules/plan-output.md](.claude/rules/plan-output.md)). When in doubt, give the short version and offer the long one.

## Architecture in brief

Mihon is **Compose + Voyager throughout**: there is no Conductor `*Controller` / RxJava `*Presenter` legacy layer to migrate from. Screens are Voyager `Screen` / `Tab` classes backed by an AndroidX `ViewModel` (Voyager routes, AndroidX holds the state). DI is **Injekt**. Domain models are immutable (`tachiyomi.domain.*.model`). Preferences go through `PreferenceStore` and typed `*Preferences` classes. Persistence is SQLDelight. Full detail: [.claude/rules/architecture.md](.claude/rules/architecture.md).

## Screen conventions (match Mihon)

Every Reikai screen ported onto or added to Mihon follows Mihon's conventions: a Voyager `Screen` / `Tab` backed by an AndroidX `ViewModel`, DI and preference reads out of `@Composable` bodies, `StateFlow` state, `viewModelScope` coroutines, `// RK` fencing on edits to Mihon's own files. The full list with rationale and a reference screen is [.claude/rules/screen-conventions.md](.claude/rules/screen-conventions.md).

**Watch item:** a model resolved by a bare `viewModel<T>()` must not be `private` (crashes on open, every build type, debug included). **The one screen still on `ScreenModel` is the novel reader**, held there on purpose because the tsundoku reader migration deletes it; `voyager-screenModel` stays in the build until then. Both: [docs/dev/plans/viewmodel-migration.md](docs/dev/plans/viewmodel-migration.md).

## Unified content UI (active initiative)

Reikai is collapsing the near-duplicate manga and novel stacks into one Reikai-owned layer over a neutral `Entry` vocabulary, one surface at a time. **The rules that bind are [.claude/rules/content-layer.md](.claude/rules/content-layer.md)**, which loads every session: the per-surface seam-depth table (every surface is at a different depth, and assuming one is deeper than it is, is the usual way this work gets mis-planned), the ownership rules, the parity rule, the delete-and-manifest policy, and the bar a takeover has to clear to count as finished. The program design, the measurements behind each ruling and the amendment history live in [docs/dev/plans/content-layer-architecture.md](docs/dev/plans/content-layer-architecture.md); read it before designing anything forward-looking.

## Code change defaults

DRY / YAGNI / KISS, minimal blast radius, no standalone refactor sprints, no dead code, comments explain WHY not WHAT, no em dashes, no AI watermarks. The full defaults and anti-defaults are [.claude/rules/code-quality.md](.claude/rules/code-quality.md).

## Commit messages (every commit, no exceptions)

EVERY commit (including `docs` / `chore` / one-line fixes) follows the "Commit message standard" in [.claude/rules/workflow.md](.claude/rules/workflow.md), enforced by the `commit-msg` hook. Run its pre-commit checklist before each commit; the most common past slip is a bare `#N` anywhere in the message (a roadmap item is `Roadmap N`; a real issue/PR is `owner/repo#N`).

**Public-facing surfaces stay generic about content sources** (repo description / topics, README, release notes, branch / PR names): generic wording ("adult content sources", "a Cloudflare-blocked source") plus a link to the detailed docs, which may name them. Full rule: workflow.md "Public-facing naming".

## Identity (load-bearing, preserve through the rebase)

`applicationId = "eu.kanade.tachiyomi"` with release suffix `.y2k` (and debug `.debugY2k`), so existing installs upgrade in place. Mihon's own `applicationId` is `app.mihon`; the namespace `eu.kanade.tachiyomi` is shared by both, so source classes resolve either way. App name string `Reikai` lives in `i18n/src/commonMain/moko-resources/base/strings.xml`. Keep the `.y2k` suffix and app name; take Mihon for everything else.

**Reikai patches on Mihon files** are fenced with `// RK -->` / `// RK <--` comment islands (grep `// RK` to find every active patch), mirroring how Komikku marks its `// SY` / `// KMK` patches. Everything that can live in its own file/module should, rather than editing Mihon's files.

## Build

Build in Android Studio. Gradle: JDK 21 (Temurin 21.0.11; matches `.github/.java-version`), formatting via Spotless (`./gradlew spotlessApply`), version catalogs `libs` and `mihonx`, build-logic via `gradle/build-logic` (`includeBuild`). Domain tests: `./gradlew :domain:test`. No `lintKotlin` / `formatKotlin` tasks exist (Kotlinter only installs a pre-push hook); use `spotlessApply` to format and `compileDebugKotlin` to check. (CLI Gradle is intermittent on this machine; build/test on-device in Android Studio when CLI fails.)

**Release-type builds are minified, the `debugY2k` dev build is not**, so R8-only bugs are invisible in the normal dev loop. The recurring one: a net-new top-level package that uses Injekt generics (`Injekt.get<T>()`) needs its own proguard `-keep`, or the minified build crashes (Injekt `FullTypeReference`). When adding such a package or code, add the keep and verify a minified `:app:assemblePreview` build. Full rule: [.claude/rules/architecture.md](.claude/rules/architecture.md) "Minification (R8) and net-new packages".

## Current release target (next cycle, on `feat/0.4.0`)

**0.3.0 shipped**: tagged `v0.3.0` -> `4b9f0e0c6`, released, and moved to [docs/dev/shipped.md](docs/dev/shipped.md). `app/build.gradle.kts` reads `versionName 0.3.0` / `versionCode 188` (the `versionCode` climbs mid-cycle whenever a migration needs it, see below; `versionName` moves at the 0.4.0 cut). Notes for continuing sessions:

- New work lands its CHANGELOG entries under `[Unreleased]` and normally bumps nothing: `versionCode` / `versionName` move only at release-cut (see the `feedback_version_bumps` memory). **Standing exception:** a version-gated data migration is a no-op until the shipped `versionCode` reaches its gate, so adding one bumps `versionCode` mid-cycle to make it fire in dev / preview builds and be testable. That is why this cycle already climbed `183 -> 188`. `versionName` stays `0.3.0` until the cut.
- **Each migration gates on its own `versionCode`, never a reused one.** The current top is `CategoryPreferencesContentTypeCleanupMigration` (`version = 188f`) on `versionCode 188`; below it sit `187f` (novel categories onto the shared table), `186f` (novel custom-cover keys), `185f` (chapter match-key backfill), `184f` (merge prefs to groups), `183f` (category sort overrides), `182f` (novel download re-key) and `181f` (vertical navigator). A new migration gates on 189+ and bumps `versionCode` to match.

## Design context

- [PRODUCT.md](PRODUCT.md) — register (product), users, brand personality (quiet, dense, deliberate), anti-references, design principles, accessibility. Read before any UI / visual work. Maintained via the `impeccable` skill.
- [DESIGN.md](DESIGN.md) — once seeded, holds visual tokens (color, typography, motion, components).

## Where things live

- [.claude/rules/architecture.md](.claude/rules/architecture.md) — Compose + Voyager, Injekt DI, PreferenceStore, coroutines, domain models, module layout, `// RK` markers, R8/minification keeps for net-new packages.
- [.claude/rules/content-layer.md](.claude/rules/content-layer.md) — the manga/novel content layer: how deep the seam goes per surface, engine ownership, capability slots, the parity and twin-test rules, delete-and-manifest, and when a takeover counts as finished.
- [.claude/rules/screen-conventions.md](.claude/rules/screen-conventions.md) — Reikai screen conventions on Mihon, with rationale and a reference screen.
- [.claude/rules/workflow.md](.claude/rules/workflow.md) — CHANGELOG rule, the commit message standard (hook-enforced), public-facing naming, release-cut, versioning, Mihon-upstream + Reikai-feature porting (the sync method and ledger in full: [docs/dev/upstream-sync.md](docs/dev/upstream-sync.md)).
- [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md) — ROADMAP.md, docs/dev/plans/ and shipped.md structure and naming (path-scoped; loads when editing those files).
- [.claude/rules/code-quality.md](.claude/rules/code-quality.md) — DRY/YAGNI/KISS, naming, code markers, file organization.
- [.claude/rules/testing.md](.claude/rules/testing.md) — behavior over implementation, mock at boundaries, coroutine test patterns.
- [.claude/rules/database.md](.claude/rules/database.md) — SQLDelight migrations.
- [.claude/rules/security.md](.claude/rules/security.md) — secrets, input validation.
- [.claude/rules/plan-output.md](.claude/rules/plan-output.md): how a findings report or plan is written (headline, graded findings, stale docs, named steps, open questions), and the density rules that keep it readable. Applies to `/scout`, `/code-research`, and any plan given in conversation.
- [.claude/rules/prose-style.md](.claude/rules/prose-style.md): sentence-level writing for every output (replies, plans, commits, docs, comments). The machine-writing habits to drop (trailing significance clauses, inflated stakes, negative parallelism, padded triples, filler openers), the overused vocabulary, and the plainer constructions to use instead.
- [docs/dev/development.md](docs/dev/development.md) — architecture and module overview (Mihon-based: Compose + Voyager, Injekt, SQLDelight).
- [docs/dev/plans/](docs/dev/plans/): per-feature implementation and decision records (one per substantial feature, indexed by its README). The forward backlog is [ROADMAP.md](ROADMAP.md); the format for both lives in `.claude/rules/roadmap-plans.md`.
- [docs/dev/upstream-sync.md](docs/dev/upstream-sync.md) — porting upstream Mihon changes by hand (Reikai is a standalone repo, not a GitHub fork): the process, commit convention, verbatim-cp + `// RK` hand-merge method, recurring gotchas, and the running synced-base ledger. Enforced by `docs-lint` + the `pre-commit` hook (no em dash, no bare `#N`; content-source names allowed).
- [docs/dev/feature-ports.md](docs/dev/feature-ports.md) — the **borrowed-feature** refs (Komikku, Tsundoku, LNReader), which are NOT a base sync: no "synced through" frontier, so the record is per feature (what was taken, from which SHA, last checked, verdict), plus where Reikai is *ahead* (don't port backwards) and what was deliberately not taken. Read before porting from a ref: a matching commit title proves nothing, and a fork's own Mihon syncs must come from `refs/mihon` instead. Same lint as upstream-sync.md.
- **Doc flow (finish an item, then ship):** [ROADMAP.md](ROADMAP.md) forward backlog, then [CHANGELOG.md](CHANGELOG.md) `[Unreleased]` (the source of truth for release notes, benefit-first bold headline), then [docs/dev/shipped.md](docs/dev/shipped.md) done-log at release-cut. CHANGELOG format: `.claude/rules/workflow.md`; ROADMAP and shipped.md format: `.claude/rules/roadmap-plans.md`.
- [docs/dev/readme-showcase.md](docs/dev/readme-showcase.md) — how the README showcase animation (`screens.webp`) is captured and built; the reproduction kit (stills + frame + scripts) lives in `.github/readme-images/showcase/`.
- **Read-only reference clones live in the `refs/` directory, which is a SIBLING of this `app/` repo, not inside it.** The repo root is `E:\Code\yokai-y2k\app`; the clones are at `E:\Code\yokai-y2k\refs` (i.e. `../refs/<name>` from the repo root, declared as absolute paths in `.claude/settings.json`). `refs/foo` relative to the app cwd does NOT resolve; use `../refs/foo` or the absolute path. The sync / port sources and what each is for:
  - `refs/mihon/` — the live upstream **base**. Ported by hand on an ongoing basis; the process + running ledger live in [docs/dev/upstream-sync.md](docs/dev/upstream-sync.md). Do not credit (it is the base).
  - `refs/komikku/` — **Komikku**, a healthy Mihon fork; the source of borrowed **feature ports** into Reikai (the EXH/adult subsystem, the MD enhanced source, the native edit-info dialog, library tag search, and more). Compare implementation, not surface; credit like the other ports.
  - `refs/lnreader-main/` (+ `refs/lnreader-2.0.3-Pre-release/`, `refs/lnreader-plugins/`) — **LNReader**; the origin of the current **novel reader** engine (the vendored `core.js`) and the LN plugin ecosystem.
  - `refs/tsundoku/` — **Tsundoku**, an Apache-2.0 Mihon fork built for novels; the reference for **novel-reader features and the future native-reader migration** (alongside LNReader). See [docs/dev/plans/novel-reader-tsundoku.md](docs/dev/plans/novel-reader-tsundoku.md).
  - Other clones are mostly self-evident from their names. Non-obvious: `refs/tachiyomi-extension/` is the **Suwayomi** extension repo (`Suwayomi/tachiyomi-extension`, for connecting Reikai to a self-hosted Suwayomi server as an in-app source), NOT the archived `tachiyomiorg/tachiyomi-extensions`.

## Skills for common flows

The skill descriptions carry the details; the routing that isn't in them: `/scout` for one non-trivial task (investigate, then plan), `/code-research` for broad questions spanning many files; `/session-handoff` with no arguments does the full sweep (Handoff.md, ROADMAP, dependent docs, memory sync + push); `/ship` for commit/push/PR with Reikai conventions; `/debug-fix --fast` for hotfixes. Also available: `/tighten`, `/port-audit`, `/pr-review`, `/test-steps`.
