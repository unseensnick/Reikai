# Reikai

Android manga reader. Personal fork of [Yōkai](https://github.com/null2264/yokai) (Tachiyomi/Mihon lineage) adding multi-source grouping, manual merge/unmerge, and category sort order.

## Working approach

**Plan before acting.** Before starting any task, think through what needs to change and why — which files are affected, what the failure modes are, and whether the approach is sound. Use `EnterPlanMode` for non-trivial tasks to draft and get approval before touching code.

**Stop and replan when blocked.** If you hit an unexpected problem mid-task — a failing constraint, a broken assumption, an error you don't fully understand — stop all changes immediately and surface the blocker. Do not circumvent it (deleting a test, silencing a lint error, skipping a hook, or forcing past a tool denial). Replan from scratch with the new information.

**Offload long or hard tasks to subagents.** When a task requires deep codebase exploration, multi-file research, or extended multi-step work, spawn a subagent (`Agent` tool) to do that work. This keeps the main context window clean and avoids polluting conversation state with intermediate search noise.

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
- WHY comments, never WHAT. If code needs a "what" comment, rename instead.
- KDoc at module boundaries only (public APIs of `source/api`, repository interfaces), not every internal function.
- No em dashes (—) in prose, comments, commit messages, or PR bodies. Use commas, parentheses, periods, or colons. Em dashes are a Claude stylistic tic that flags writing as AI-generated.

## Identity (load-bearing — preserve when porting upstream)

`applicationId = "eu.kanade.tachiyomi"` and release suffix `.y2k` — both legacy; kept so existing installs upgrade in place. App name string `Reikai` lives in `i18n/src/commonMain/moko-resources/base/strings.xml`. Keep `.y2k` for packaging continuity; take upstream for everything else.

Upstream changes are **ported manually** from `refs/yokai/`. Never `git merge upstream/master` into any branch.

## Build

Android Studio (`Build → Make/Rebuild`). Java 17, `minSdk 23`, `targetSdk 36`. Domain tests: `./gradlew :domain:test`. (Kotlinter runs via the pre-push hook only — there is no `lintKotlin` Gradle task.)

## Where things live

- [docs/dev/development.md](docs/dev/development.md) — architecture, modules, fork features, reference clones, upstream-port workflow.
- [.claude/rules/architecture.md](.claude/rules/architecture.md) — presenter vs Compose+Voyager, settings two-screen pattern, preferences, coroutines, KMP, DI.
- [.claude/rules/workflow.md](.claude/rules/workflow.md) — CHANGELOG rule, commits/PRs, release-cut, upstream sync.
- [.claude/rules/code-quality.md](.claude/rules/code-quality.md) — DRY/YAGNI/KISS, naming, code markers, file organization.
- [.claude/rules/testing.md](.claude/rules/testing.md) — behavior over implementation, mock at boundaries, coroutine test patterns.
- [.claude/rules/database.md](.claude/rules/database.md) — SQLDelight migrations.
- [.claude/rules/security.md](.claude/rules/security.md) — secrets, input validation.

## Skills for common flows

- `/ship` — scan → stage → commit → push → PR with Reikai conventions (no `Co-Authored-By`, no `## Test plan`, `--repo unseensnick/Reikai --base main`).
- `/debug-fix` — bug-hunt workflow (`--fast` for hotfixes).
- `/pr-review`, `/refactor`, `/test-writer`, `/tdd`, `/explain`, `/context-budget`.
