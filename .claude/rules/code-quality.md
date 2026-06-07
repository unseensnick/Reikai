---
alwaysApply: true
---

# Code Quality

## Coding principles (project-wide)

- **DRY**: Before adding a helper, search the codebase (or run an Explore agent in plan mode) for an existing equivalent.
- **YAGNI**: Only add what the current task requires. No speculative APIs, optional parameters, or abstractions for hypothetical callers.
- **KISS**: Prefer the simplest correct solution. Complexity must be justified by concrete requirements, not elegance or anticipated scale.
- **Minimal blast radius**: A bug fix changes only what's broken. A feature adds only what's specified. Leave working surrounding code untouched.
- **No standalone refactor sprints**: Refactor incrementally alongside the feature or fix that motivated it. Never propose a separate "cleanup pass" unless the user asks.

## Anti-defaults

- No premature abstractions. Three similar lines beat a helper used once.
- Don't add features or improvements beyond what was asked.
- Don't refactor adjacent code while fixing a bug.
- No dead code or commented-out blocks. Git has history.
- Comments, KDoc, and docstrings exist for contributors and maintainers. Keep them short and concise without losing context. Explain WHY, not WHAT (rename if a WHAT comment is needed). Reserve KDoc for module boundaries (public APIs of `source/api`, repository interfaces), not every internal function. Never a wall of text.
- No em dashes (—) in prose, comments, commit messages, or PR bodies. Use commas, parentheses, periods, or colons. Em dashes are a Claude stylistic tic that flags writing as AI-generated.
- No AI-generated watermarks. Don't add "Co-Authored-By: Claude", "Generated with Claude Code", robot emoji footers, or similar tags to commits, PRs, code, or docs.

## Naming (Kotlin)

- Files: PascalCase matching the primary class (`LibraryPresenter.kt`, `MangaHeaderHolder.kt`).
- Classes/objects: PascalCase. Functions/properties: camelCase. Constants: SCREAMING_SNAKE.
- Booleans: `is` / `has` / `should` / `can` prefix. Predicates: `is*` / `has*`. Factories: `create*`. Converters: `to*`.
- Composables: PascalCase verb-less noun (`MangaCard`, `SourceChipRow`).
- Acronyms as words: `userId`, `httpClient`, not `userID` / `HTTPClient`.

## Code Markers

`TODO(author): desc (#issue)` for planned work. `FIXME(author): desc (#issue)` for known bugs. `HACK(author): desc (#issue)` for ugly workarounds (explain the proper fix). `NOTE: desc` for non-obvious context. Owner + issue link required. Never `XXX`, `TEMP`, `REMOVEME`.

## File organization

- Imports: stdlib/jvm, kotlinx/androidx/material, third-party, project (`eu.kanade.*` / `yokai.*` / `tachiyomi.*`). Blank line between groups. No star imports (per `.editorconfig`).
- One top-level class per file when it's a UI/presenter/screen. Small data classes can co-locate.
- Function order in classes: public API first, then private helpers in call order.
- Compose `@OptIn` annotations: propagate at the call site, don't suppress globally.
