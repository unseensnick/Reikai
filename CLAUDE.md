# Reikai

Android manga reader. Personal fork of [Yōkai](https://github.com/null2264/yokai) (Tachiyomi/Mihon lineage) adding multi-source grouping, manual merge/unmerge, and category sort order.

## Working approach

**Investigate before planning when context is thin.** If you aren't confident you understand the surrounding code, conventions, or constraints for a task (e.g., porting a screen from legacy to Compose + Voyager, touching an unfamiliar module, changing cross-cutting infrastructure), do a thorough investigation first: read the relevant files, trace the existing pattern, check `refs/` for upstream equivalents. Only present a plan once you're truly confident in it, then wait for approval before executing.

**Plan before acting.** Once you have enough context, think through what needs to change and why: which files are affected, what the failure modes are, and whether the approach is sound. Use `EnterPlanMode` for non-trivial tasks to draft and get approval before touching code.

**Stop and replan when blocked.** If you hit an unexpected problem mid-task (a failing constraint, a broken assumption, an error you don't fully understand), stop all changes immediately and surface the blocker. Do not circumvent it (deleting a test, silencing a lint error, skipping a hook, or forcing past a tool denial). Replan from scratch with the new information.

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
- Comments, KDoc, and docstrings exist for contributors and maintainers. Keep them short and concise without losing context. Explain WHY, not WHAT (rename if a WHAT comment is needed). Reserve KDoc for module boundaries (public APIs of `source/api`, repository interfaces), not every internal function. Never a wall of text.
- No em dashes (—) in prose, comments, commit messages, or PR bodies. Use commas, parentheses, periods, or colons. Em dashes are a Claude stylistic tic that flags writing as AI-generated.
- No AI-generated watermarks. Don't add "Co-Authored-By: Claude", "Generated with Claude Code", robot emoji footers, or similar tags to commits, PRs, code, or docs.

## Identity (load-bearing — preserve when porting upstream)

`applicationId = "eu.kanade.tachiyomi"` and release suffix `.y2k` — both legacy; kept so existing installs upgrade in place. App name string `Reikai` lives in `i18n/src/commonMain/moko-resources/base/strings.xml`. Keep `.y2k` for packaging continuity; take upstream for everything else.

Upstream changes are **ported manually** from `refs/yokai/`. Never `git merge upstream/master` into any branch.

## Build

Android Studio (`Build → Make/Rebuild`). Java 17, `minSdk 23`, `targetSdk 36`. Domain tests: `./gradlew :domain:test`. (Kotlinter runs via the pre-push hook only — there is no `lintKotlin` Gradle task.)

## Design context

- [PRODUCT.md](PRODUCT.md) — register (product), users, brand personality (quiet, dense, deliberate), anti-references, design principles, accessibility. Read before any UI / visual work. Maintained via the `impeccable` skill.
- [DESIGN.md](DESIGN.md) — once seeded, holds visual tokens (color, typography, motion, components) derived from the prototype.

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
