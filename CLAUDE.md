# Reikai

Android manga + light-novel reader. Personal fork built on [Mihon](https://github.com/mihonapp/mihon) (Tachiyomi lineage), adding light novels, multi-source grouping, manual merge/unmerge, and category sort order.

**Rebase in progress (2026-06):** Reikai was previously a fork of [Yōkai](https://github.com/null2264/yokai) and is being rebased onto Mihon. The Mihon-based foundation lives on the `design/mihon-rebase` branch. The old Yōkai-based code (branch `design/library-compose`) is kept only as the porting reference. Full plan and feature list: the rebase plan file; ongoing status: the `mihon-rebase` memory.

## Working approach

**Investigate before planning when context is thin.** If you aren't confident you understand the surrounding code, conventions, or constraints for a task (porting a Reikai feature onto Mihon, touching an unfamiliar module, changing cross-cutting infrastructure), investigate first: read the relevant files, trace the existing pattern. Two reference sources: `refs/mihon/` (the live upstream base) and the `design/library-compose` branch (Reikai's Yōkai-era features awaiting port). For non-trivial work, invoke `/scout` to produce a grounded findings report before forming a plan. Only present a plan once you're truly confident, then wait for approval before executing.

**Cite before you claim.** Every concrete claim about the codebase, framework, or upstream (a function name, a file path, a flag, "X calls Y") must come with a `file:line` citation from current code that you just read. If you can't cite it, you don't know it: read first, claim second. Memory and Handoff content are hypotheses, not facts; a memory that names a function or file is true only if it still exists in current code. When a stale memory is found, surface it for pruning instead of acting on it.

**Plan before acting.** Once you have enough context, think through what needs to change and why: which files are affected, what the failure modes are, whether the approach is sound. Use `EnterPlanMode` for non-trivial tasks to draft and get approval before touching code.

**Stop and replan when blocked.** If you hit an unexpected problem mid-task (a failing constraint, a broken assumption, an error you don't fully understand), stop all changes immediately and surface the blocker. Do not circumvent it (deleting a test, silencing a lint error, skipping a hook, or forcing past a tool denial). Replan from scratch with the new information.

**Offload long or hard tasks to subagents.** When a task requires deep codebase exploration, multi-file research, or extended multi-step work, spawn a subagent (`Agent` tool). This keeps the main context window clean.

**Explain in plain English, without dumbing down.** Default to clear everyday language: spell out what something does and why it matters before naming the construct, define jargon the first time, and prefer a concrete analogy over a term of art (the user is newer to Kotlin/Android). Plain English does NOT mean less substance: keep the real technical detail, the tradeoffs, the failure modes, and the `file:line` citations. The goal is that someone can follow the reasoning without already knowing the codebase, not that the content is thinner. When presenting findings or a plan, lead with the plain-English picture; the precise function/file names are support, not the headline.

## Architecture in brief

Mihon is **Compose + Voyager throughout**: there is no Conductor `*Controller` / RxJava `*Presenter` legacy layer to migrate from. Screens are Voyager `Screen` / `Tab` classes backed by a `ScreenModel`. DI is **Injekt**. Domain models are immutable (`tachiyomi.domain.*.model`). Preferences go through `PreferenceStore` and typed `*Preferences` classes. Persistence is SQLDelight. Full detail: [.claude/rules/architecture.md](.claude/rules/architecture.md).

## Screen conventions (match Mihon)

Every Reikai screen ported onto or added to Mihon follows Mihon's existing Voyager conventions. Index (full rationale + reference screen in [.claude/rules/compose-port.md](.claude/rules/compose-port.md)):

1. A Voyager `Screen` / `Tab` class, not a bare `@Composable fun FooScreen()`.
2. Business logic in a `ScreenModel` resolved via `rememberScreenModel { ... }`. Pure-UI screens may skip it and say so in a one-line comment.
3. No `Injekt.get<>()` / `injectLazy()` inside a `@Composable` body. Inject in the ScreenModel.
4. State exposed as `StateFlow` (typically `StateScreenModel<S>`). No RxJava on the screen path.
5. No `PreferenceStore` / `*Preferences` read inside a `@Composable`. Read in the ScreenModel, expose as state.
6. Coroutines via `screenModelScope.launchIO` / `launchUI` (ScreenModel) or `rememberCoroutineScope()` (composable). Never `GlobalScope`; use `WorkManager` for work that must outlive the screen.
7. Business logic out of `@Composable`. Side-effects in `LaunchedEffect` or the ScreenModel.
8. Re-typed to Mihon's immutable domain models; in-place edits to Mihon's own files fenced with `// RK -->` / `// RK <--` markers (see below). Net-new code lives in its own files/modules.

## Code change defaults

- **DRY**: Before adding a helper, search the codebase (or run an Explore agent in plan mode) for an existing equivalent.
- **YAGNI**: Only add what the current task requires. No speculative APIs, optional parameters, or abstractions for hypothetical callers.
- **KISS**: Prefer the simplest correct solution. Complexity must be justified by concrete requirements, not elegance or anticipated scale.
- **Minimal blast radius**: A bug fix changes only what's broken. A feature adds only what's specified. Leave working surrounding code untouched.
- **No standalone refactor sprints**: Refactor incrementally alongside the feature or fix that motivated it. Never propose a separate "cleanup pass" unless the user asks.

### Anti-defaults

- No premature abstractions. Three similar lines beat a helper used once.
- Don't add features or improvements beyond what was asked.
- Don't refactor adjacent code while fixing a bug.
- No dead code or commented-out blocks. Git has history.
- Comments, KDoc, and docstrings exist for contributors and maintainers. Keep them short and concise without losing context. Explain WHY, not WHAT (rename if a WHAT comment is needed). Reserve KDoc for module boundaries (public APIs of `source-api`, repository interfaces), not every internal function. Never a wall of text.
- No em dashes in prose, comments, commit messages, or PR bodies. Use commas, parentheses, periods, or colons. Em dashes are a Claude stylistic tic that flags writing as AI-generated.
- No AI-generated watermarks. Don't add "Co-Authored-By: Claude", "Generated with Claude Code", robot emoji footers, or similar tags to commits, PRs, code, or docs.

## Identity (load-bearing, preserve through the rebase)

`applicationId = "eu.kanade.tachiyomi"` with release suffix `.y2k` (and debug `.debugY2k`), so existing installs upgrade in place. Mihon's own `applicationId` is `app.mihon`; the namespace `eu.kanade.tachiyomi` is shared by both, so source classes resolve either way. App name string `Reikai` lives in `i18n/src/commonMain/moko-resources/base/strings.xml`. Keep the `.y2k` suffix and app name; take Mihon for everything else.

**Reikai patches on Mihon files** are fenced with `// RK -->` / `// RK <--` comment islands (grep `// RK` to find every active patch), mirroring how Komikku marks its `// SY` / `// KMK` patches. Everything that can live in its own file/module should, rather than editing Mihon's files.

## Build

Build in Android Studio. Gradle: JDK 21 (Temurin 21.0.11; matches `.github/.java-version`), formatting via Spotless (`./gradlew spotlessApply`), version catalogs `libs` and `mihonx`, build-logic via `gradle/build-logic` (`includeBuild`). Domain tests: `./gradlew :domain:test`. (CLI Gradle is intermittent on this machine; build/test on-device in Android Studio when CLI fails.)

## Design context

- [PRODUCT.md](PRODUCT.md) — register (product), users, brand personality (quiet, dense, deliberate), anti-references, design principles, accessibility. Read before any UI / visual work. Maintained via the `impeccable` skill.
- [DESIGN.md](DESIGN.md) — once seeded, holds visual tokens (color, typography, motion, components).

## Where things live

- [.claude/rules/architecture.md](.claude/rules/architecture.md) — Compose + Voyager, Injekt DI, PreferenceStore, coroutines, domain models, module layout, `// RK` markers.
- [.claude/rules/compose-port.md](.claude/rules/compose-port.md) — Reikai screen conventions on Mihon, with rationale and a reference screen.
- [.claude/rules/workflow.md](.claude/rules/workflow.md) — CHANGELOG rule, commits/PRs, release-cut, Mihon-upstream + Reikai-feature porting. **Mihon-sync standards** (the commit-message convention with `Mihon PR #N` / `Mihon Issue #N` references, the verbatim-cp + `// RK` hand-merge method, and the running synced-base ledger) live in its "Syncing with Mihon" section and, in full, the `upstream-sync` memory.
- [.claude/rules/code-quality.md](.claude/rules/code-quality.md) — DRY/YAGNI/KISS, naming, code markers, file organization.
- [.claude/rules/testing.md](.claude/rules/testing.md) — behavior over implementation, mock at boundaries, coroutine test patterns.
- [.claude/rules/database.md](.claude/rules/database.md) — SQLDelight migrations.
- [.claude/rules/security.md](.claude/rules/security.md) — secrets, input validation.
- [docs/dev/development.md](docs/dev/development.md) — architecture and module overview. NOTE: still describes the Yōkai base; being updated for Mihon.
- [docs/dev/readme-showcase.md](docs/dev/readme-showcase.md) — how the README showcase animation (`screens.webp`) is captured and built; the reproduction kit (stills + frame + scripts) lives in `.github/readme-images/showcase/`.

## Skills for common flows

- `/scout` — investigate a non-trivial task before planning; produces a findings report grounded in `file:line` citations. Use before ports or cross-cutting changes.
- `/tighten` — trim verbose prose, walls of text, journey narration, and WHAT comments from docs (and, on ask, code comments) without losing vital info. Always plans before editing.
- `/port-audit` — audit a port for behavioral parity against the Reikai source on `design/library-compose`. Use after a phase ships.
- `/ship` — scan, stage, commit, push, PR with Reikai conventions (no `Co-Authored-By`, no `## Test plan`; `--repo unseensnick/Reikai`). During the rebase, work targets `design/mihon-rebase`, not `main`.
- `/debug-fix` — bug-hunt workflow (`--fast` for hotfixes).
- `/pr-review`, `/refactor`, `/test-writer`, `/tdd`, `/explain`, `/context-budget`.
