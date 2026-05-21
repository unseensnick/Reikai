# Reikai

Android manga reader. Personal fork of [Yōkai](https://github.com/null2264/yokai) (Tachiyomi/Mihon lineage) adding multi-source grouping, manual merge/unmerge, and category sort order. See `docs/dev/development.md` for architecture, modules, fork features, and reference clones.

## Working approach

**Plan before acting.** Before starting any task, think through what needs to change and why — which files are affected, what the failure modes are, and whether the approach is sound. Use `EnterPlanMode` for non-trivial tasks to draft and get approval before touching code.

**Stop and replan when blocked.** If you hit an unexpected problem mid-task — a failing constraint, a broken assumption, an error you don't fully understand — stop all changes immediately and surface the blocker. Do not circumvent it (deleting a test, silencing a lint error, skipping a hook, or forcing past a tool denial). Replan from scratch with the new information.

**Offload long or hard tasks to subagents.** When a task requires deep codebase exploration, multi-file research, or extended multi-step work, spawn a subagent (`Agent` tool) to do that work. This keeps the main context window clean and avoids polluting conversation state with intermediate search noise.

## Commands

Builds run through Android Studio (`Build → Make/Rebuild`). Java 17, `minSdk 23`, `targetSdk 36`.

- `./gradlew lintKotlin` — ktlint check
- `./gradlew formatKotlin` — ktlint auto-fix
- `./gradlew :domain:test` — domain unit tests (JUnit 5 / MockK / Kotest)
- Stale edits in running app → **Build → Clean Project** in Android Studio (Kotlin incremental compile can serve stale class files).

## Versioning (gates every push)

5-segment `upstream.fork-patch` (e.g. `1.9.7.5.1`). Before pushing, bump both in `app/build.gradle.kts`: `_versionName` (string) and `versionCode` (int, always increment). Docs/CI-only commits can skip — flag it so the user decides.

## Identity (preserve in upstream merges)

`applicationId = "eu.kanade.tachiyomi"`, release suffix `.y2k` (legacy — predates the Reikai rename; kept so existing installs upgrade in place). App name string `Reikai` in `i18n/src/commonMain/moko-resources/base/strings.xml`. Keep `.y2k` suffix for packaging continuity; keep upstream for everything else.

## Branch rule

Never merge `upstream/master` directly into a feature branch — resolve rebrand conflicts on `main` once, then merge `main` into the branch.

## Reference clones

Read-only sibling clones registered in `permissions.additionalDirectories`:

- `yokai` — direct upstream (null2264/yokai).
- `mihon` — upstream of Yōkai (Tachiyomi-lineage).
- `komikku` — source of ported features (e.g. related-mangas).
- `tachiyomi-extension` — legacy extension repo, archived; historical reference only.
- `keiyoushi-extensions-source` — active Mihon-lineage **extension source** code that Reikai users install from.
- `keiyoushi-extensions` — Keiyoushi **distribution** repo (compiled APKs + `index.json` served to the in-app extension list).
- `blueth-yokai` — another Yokai fork; reference for alternative feature implementations.
- `Yōkai-Y2K-New-Icons` — icon asset variants for the Y2K fork.
- `lnreader-main` — LNReader main branch (Android light-novel reader); reference for novel-reading UI and plugin architecture.
- `lnreader-2.0.3-Pre-release` — LNReader pre-release snapshot; pinned reference for a specific API surface.
- `lnreader-plugins` — LNReader plugin/source distribution repo.
