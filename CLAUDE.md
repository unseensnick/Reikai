# Yōkai-Y2K

Android manga reader. Personal fork of [Yōkai](https://github.com/null2264/yokai) (Tachiyomi/Mihon lineage) adding multi-source grouping, manual merge/unmerge, and category sort order. See `docs/development.md` for architecture, modules, fork features, and reference clones.

## Commands

Builds run through Android Studio (`Build → Make/Rebuild`). Java 17, `minSdk 23`, `targetSdk 36`.

- `./gradlew lintKotlin` — ktlint check
- `./gradlew formatKotlin` — ktlint auto-fix
- `./gradlew :domain:test` — domain unit tests (JUnit 5 / MockK / Kotest)
- Stale edits in running app → **Build → Clean Project** in Android Studio (Kotlin incremental compile can serve stale class files).

## Versioning (gates every push)

5-segment `upstream.fork-patch` (e.g. `1.9.7.5.1`). Before pushing, bump both in `app/build.gradle.kts`: `_versionName` (string) and `versionCode` (int, always increment). Docs/CI-only commits can skip — flag it so the user decides.

## Identity (preserve in upstream merges)

`applicationId = "eu.kanade.tachiyomi"`, release suffix `.y2k`. App name string `Yōkai-Y2K` in `i18n/src/commonMain/moko-resources/base/strings.xml`. Keep Y2K for identity/packaging; keep upstream for everything else.

## Branch rule

Never merge `upstream/master` directly into a feature branch — resolve rebrand conflicts on `main` once, then merge `main` into the branch.

## Reference clones

Read-only sibling clones registered in `permissions.additionalDirectories`:

- `yokai` — direct upstream (null2264/yokai).
- `mihon` — upstream of Yōkai (Tachiyomi-lineage).
- `komikku` — source of ported features (e.g. related-mangas).
- `tachiyomi-extension` — legacy extension repo, archived; historical reference only.
- `keiyoushi-extensions-source` — active Mihon-lineage **extension source** code that Y2K users install from.
- `keiyoushi-extensions` — Keiyoushi **distribution** repo (compiled APKs + `index.json` served to the in-app extension list).
