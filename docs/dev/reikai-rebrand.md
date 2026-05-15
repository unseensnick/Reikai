# Rebrand `Yōkai-Y2K` → `Reikai` — cold-start plan

This doc is the single-source recipe for renaming the fork. A fresh session reading this from scratch should be able to execute the rebrand end-to-end without consulting prior conversation history.

The rebrand is **not started yet** — this doc is the pre-flight plan. When the user is ready, work it top-to-bottom.

## Why this exists

"Y2K" in the current name was a casual letter-grab (Y from Yōkai, 2K from J2K) — no thematic intent. "Reikai" (霊界, "spirit world") pairs deliberately with the upstream "Yōkai" (妖怪, "spirit-creature") — same Japanese-mythology vocabulary, suggesting "the same world from a different angle." The new R-monogram icon (`Reikai-Release-Alt.png` in `refs/Yōkai-Y2K-New-Icons/`) is also objectively cleaner artwork than the current inherited-from-upstream Yōkai icon.

The internal `applicationIdSuffix` of `.y2k` / `.debugY2k` / `.nightlyY2k` **stays** — existing installs upgrade in place (same package ID → same SharedPreferences, same database, no orphan apps).

## Locked-in decisions

These were settled in the planning conversation. Do not re-litigate them in a cold-start session unless the user explicitly reopens them.

1. **App label = `Reikai`.** Not "Reikai 霊界", not "Reikai (Yōkai-Y2K)". Single word.
2. **Alternate experimental icons stay as Yōkai artwork.** Only the DEFAULT launcher icon swaps. The Blue / Orange / Gray / Classic variants in `BasePreferences.AppIcons` keep their old artwork — making Reikai-themed variants is deferred to a future task.
3. **No release-boundary commit.** No `_versionName` / `versionCode` bump. The rebrand lands as a single bullet inside the existing `[Unreleased] → Changes` block. Matches the alpha-cycle versioning policy.
4. **GitHub repo rename goes first**, then the code/docs work uses the new name from the start. GitHub auto-redirects every old URL, so existing PR / issue / commit links keep resolving.
5. **Local on-disk folder stays `E:\Code\yokai-y2k\`.** Not getting renamed. The `.claude/settings.json` `additionalDirectories` paths reference disk paths to sibling clones, not GitHub URLs, and don't need changing.

## Order of operations

```
0. Rename GitHub repo + update local origin remote
1. Create chore/rebrand-reikai branch off main
2. Strings — i18n base + debug/nightly overrides
3. Icons — three Image Asset Studio runs (main / debug / nightly)
4. Source code — non-string repo refs
5. Top-level docs — README, CHANGELOG, CLAUDE.md
6. Claude rules + skills + project docs
7. CI workflows
8. Memory files (Claude-side, outside the project)
9. README icon image
10. Verify
11. /ship
```

## Step 0 — GitHub repo rename

```bash
gh repo rename --repo unseensnick/yokai-y2k reikai
git remote set-url origin git@github.com:unseensnick/reikai.git   # or HTTPS equivalent
git remote -v                                                      # verify
```

Old URLs (`github.com/unseensnick/yokai-y2k/...`) auto-redirect to the new repo. PR / issue / commit links from the past keep working.

## Step 1 — Branch

```bash
git checkout -b chore/rebrand-reikai
```

## Step 2 — App identity strings

Edit [`i18n/src/commonMain/moko-resources/base/strings.xml`](../../i18n/src/commonMain/moko-resources/base/strings.xml) lines 3-5:

| Key | Before | After |
| --- | --- | --- |
| `app_name` | `Yōkai-Y2K` | `Reikai` |
| `app_short_name` | `Yōkai-Y2K` | `Reikai` |
| `app_normalized_name` | `Yokai-Y2K` | `Reikai` |

All three are `translatable="false"`, so per-locale strings.xml files don't need editing.

Also check [`app/src/debug/res/values/strings.xml`](../../app/src/debug/res/values/strings.xml) and [`app/src/nightly/res/values/strings.xml`](../../app/src/nightly/res/values/strings.xml) for any `app_name` override strings (e.g. `Yōkai-Y2K Debug`). Rename to `Reikai Debug` / `Reikai Nightly` if present.

## Step 3 — Launcher icons (Android Studio Image Asset Studio)

Three Image Asset Studio runs in **Android Studio Panda 4 | 2025.3.4 Patch 1** (or whatever Studio version is current). Each run targets a different build-variant `res/` directory and uses a different source PNG.

Switch the **Project** tool window to **Project** view (top dropdown) so the `app/src/{main,debug,nightly}/res` folders are visible.

### Run 1 — main launcher icon (the default everyone sees)

1. Right-click `app/src/main/res` → **New → Image Asset**.
2. **Icon Type**: `Launcher Icons (Adaptive and Legacy)`.
3. **Name**: `ic_launcher` (default — leave it).
4. **Foreground Layer** tab:
   - **Layer Type**: Image.
   - **Path**: `E:\Code\yokai-y2k\refs\Yōkai-Y2K-New-Icons\Reikai-Release-Alt.png`.
   - **Resize**: adjust if the preview shows the R touching the bezel; 80–100% is typical for monogram-style icons. Aim for the R to fill most of the safe zone with a bit of breathing room.
   - **Trim**: leave on.
5. **Background Layer** tab:
   - **Layer Type**: Color.
   - **Color**: deep purple-to-black (e.g. `#1A0033`) or solid black to lean into the source PNG's own dark gradient.
6. **Options** tab: leave **Generate** checked for both round and square legacy formats.
7. **Next**. Confirm the file list shows overwrites for every mipmap density (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) plus `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`.
8. **Finish**.

### Run 2 — debug build override

Same flow but:
- Right-click `app/src/debug/res` → New → Image Asset.
- **Foreground** source: `Reikai-Debug.png`.
- **Background** color: dark grey (`#1A1A1A`) — matches the Debug variant's monochrome theme.

### Run 3 — nightly build override

Same flow but:
- Right-click `app/src/nightly/res` → New → Image Asset.
- **Foreground** source: `Reikai-Nightly.png`.
- **Background** color: deep blue (`#0A0A2E`) — matches Nightly's blue accent.

### Post-run sanity check

- `app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp` should have a fresh timestamp and preview as Reikai-Release-Alt.
- Same for the debug and nightly variants in their respective res dirs.
- **Alternate icons** (`ic_launcher_blue.webp`, `ic_launcher_orange.webp`, `ic_launcher_gray.webp`, `ic_launcher_classic.webp` + their `_round` / `_foreground` variants) under `app/src/main/res/mipmap-*/` should be **untouched**. If they accidentally got overwritten, restore with:

  ```bash
  git checkout HEAD -- app/src/main/res/mipmap-*/ic_launcher_blue.webp \
                       app/src/main/res/mipmap-*/ic_launcher_orange.webp \
                       app/src/main/res/mipmap-*/ic_launcher_gray.webp \
                       app/src/main/res/mipmap-*/ic_launcher_classic.webp \
                       app/src/main/res/mipmap-*/ic_launcher_round_blue.webp \
                       app/src/main/res/mipmap-*/ic_launcher_round_orange.webp \
                       app/src/main/res/mipmap-*/ic_launcher_round_gray.webp \
                       app/src/main/res/mipmap-*/ic_launcher_round_classic.webp \
                       app/src/main/res/mipmap-*/ic_launcher_foreground_blue.webp \
                       app/src/main/res/mipmap-*/ic_launcher_foreground_gray.webp \
                       app/src/main/res/mipmap-*/ic_launcher_foreground_orange.webp
  ```

## Step 4 — Source code (non-string repo refs)

Grep flagged these files outside `app/build/`:

```bash
grep -lrn "yokai-y2k\|Yōkai-Y2K\|Yokai-Y2K" \
  --include="*.kt" --include="*.xml" --include="*.json" \
  app/src/main app/src/debug app/src/nightly app/src/standard
```

Likely hits:

- [`app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt`](../../app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt) — the GitHub releases URL for the auto-updater. Change `unseensnick/yokai-y2k` → `unseensnick/reikai`.
- [`app/src/main/java/yokai/data/library/taste/TrackerLibraryRepositoryImpl.kt`](../../app/src/main/java/yokai/data/library/taste/TrackerLibraryRepositoryImpl.kt) — confirm the match. If it's a log tag or comment, update; if user-facing, route through `MR.strings.app_name` instead of hardcoding.
- [`app/src/main/java/yokai/presentation/settings/screen/about/AboutScreen.kt`](../../app/src/main/java/yokai/presentation/settings/screen/about/AboutScreen.kt) — confirm any hardcoded name reads from `MR.strings.app_name`. If it does, no edit needed (the string change in Step 2 propagates automatically). If it doesn't, fix it.
- [`app/src/main/res/layout/manga_header_item.xml`](../../app/src/main/res/layout/manga_header_item.xml) + the `sw600dp-land` and `sw600dp-port` variants — likely a `@string/app_name` ref. If literal, replace.
- `app/src/standard/google-services.json` — Firebase config. Match is probably the project's display name (cosmetic; package ID unchanged so the file itself doesn't need re-downloading from Firebase).

**Do NOT edit `app/build.gradle.kts`.** The `applicationIdSuffix = ".y2k"` / `.debugY2k` / `.nightlyY2k` values stay (locked-in decision 5). `_versionName` and `versionCode` stay (locked-in decision 3).

## Step 5 — README, CHANGELOG, CLAUDE.md

- [`README.md`](../../README.md): rename the title and every body reference. Update badges to use the new repo URL (`unseensnick/reikai`).
- [`CHANGELOG.md`](../../CHANGELOG.md): add **one** bullet under `## [Unreleased] → ### Changes`:

  > Renamed the fork to **Reikai**. The Yōkai-Y2K placeholder name is retired — installs upgrade in place (same package ID), the launcher shows the new R-monogram icon and "Reikai" as the app label. The `.y2k` package suffix stays under the hood.

  **Do not rewrite released entries** that mention Yōkai-Y2K — they're historical record.
- [`CLAUDE.md`](../../CLAUDE.md): rename the top "Yōkai-Y2K" identity description. Update the "Identity (preserve in upstream merges)" section — note that `.y2k` is now a legacy suffix that doesn't match the name anymore (and the rationale: keeping it lets existing installs upgrade in place).

## Step 6 — Claude rules, skills, project docs

- [`.claude/rules/workflow.md`](../../.claude/rules/workflow.md) — `unseensnick/yokai-y2k` → `unseensnick/reikai` in the `gh pr create` flag.
- [`.claude/rules/database.md`](../../.claude/rules/database.md) — confirm what the match is; update if it's a repo URL.
- [`.claude/skills/ship/SKILL.md`](../../.claude/skills/ship/SKILL.md) — `--repo` flag in Step 4.
- [`.claude/skills/debug-fix/SKILL.md`](../../.claude/skills/debug-fix/SKILL.md) — same.
- [`.claude/settings.json`](../../.claude/settings.json) — **do not change** the `additionalDirectories` array. Those are local-disk paths (`E:\code\yokai-y2k\refs\*`), and the local folder isn't being renamed.
- [`docs/dev/development.md`](development.md) — repo URL refs.
- [`docs/dev/settings-compose-migration.md`](settings-compose-migration.md) — recently-shipped handbook with `--repo unseensnick/yokai-y2k` in the recipe. Update.
- [`docs/dev/tracker-aware-duplicate-detection.md`](tracker-aware-duplicate-detection.md) — confirm what the match is.
- [`docs/multi-source.md`](../multi-source.md), [`docs/related-mangas.md`](../related-mangas.md), [`docs/flaresolverr.md`](../flaresolverr.md) — check for repo URLs.
- This file (`docs/dev/reikai-rebrand.md`) — after the rebrand is complete, either delete it or move it to an archive folder. It's pre-flight; once the work lands, the doc has served its purpose.

## Step 7 — CI workflows

- [`.github/workflows/build_push.yml`](../../.github/workflows/build_push.yml) — release titles, body templates, alpha-release message strings. Likely matches on `Yōkai-Y2K` in the user-visible release notes. Update.
- [`.github/workflows/build_check.yml`](../../.github/workflows/build_check.yml) and [`.github/workflows/mirror.yml`](../../.github/workflows/mirror.yml) — sanity-check for repo URL refs.

## Step 8 — Memory files (Claude-side, outside the project)

In `C:\Users\unseensnick\.claude\projects\E--Code-yokai-y2k-app\memory\`:

- `reference_gh_default_target.md` — the file documents the "gh pr create defaults to upstream" gotcha. After the rename, the new repo name is what the `--repo` flag should point at. Update the entire content.
- `MEMORY.md` — the index pointer line for `reference_gh_default_target.md` mentions the old repo name. Update to reflect the new name.
- Any other memory file that quotes a `--repo unseensnick/yokai-y2k` command.

The memory directory itself is keyed off the local working-directory path (`E--Code-yokai-y2k-app`). Since the local folder isn't getting renamed (locked-in decision 5), the memory directory path stays the same — **do not move it**.

## Step 9 — README icon image

[`.github/readme-images/app-icon.webp`](../../.github/readme-images/app-icon.webp) shows the app icon in the README. Replace it with a webp export of `Reikai-Release-Alt.png`. Either:

- Use Android Studio: open the PNG, File → Save As → webp.
- Or ImageMagick CLI: `magick refs/Yōkai-Y2K-New-Icons/Reikai-Release-Alt.png -quality 90 .github/readme-images/app-icon.webp` (run from the project root).

Leave `.github/readme-images/app-icon.inkscape.svg` alone — it's the old Yōkai icon's vector source and doesn't have a Reikai equivalent yet. Replacing it would require a vector Reikai source (not in `refs/Yōkai-Y2K-New-Icons/`).

The `screens.gif` file is inherited from upstream Yōkai and shows upstream's UI — not the fork's. Replacing it with a Reikai-specific GIF is a separate task; ship the rebrand with the inherited GIF for now.

## Step 10 — Verification

```bash
./gradlew :app:compileDevDebugKotlin
./gradlew :app:testDevDebugUnitTest
```

On device:

- Install the new debug APK. Launcher icon is the Reikai-Debug grey-monogram. App label is "Reikai" (or "Reikai Debug" if a build-type override exists).
- Open the app. Library / settings / database all intact (same applicationId means same SharedPreferences and Room DB).
- Open Settings → Appearance → Change App Icon (experimental dropdown). Confirm DEFAULT now shows Reikai; the alternates (Blue / Orange / Gray / Classic) still show old Yōkai artwork — that's expected per locked-in decision 2.

Final grep sanity check (should return zero source-controlled matches):

```bash
grep -rn "Yōkai-Y2K\|Yokai-Y2K\|yokai-y2k" \
  --include="*.md" --include="*.yml" --include="*.kts" \
  --include="*.kt" --include="*.xml" --include="*.json" \
  | grep -vE "(build/|\.idea/|CHANGELOG\.md.*\[1\.9|refs/)"
```

The `CHANGELOG.md` exclusion preserves historical released-version entries (they reference Yōkai-Y2K and should not be rewritten). The `refs/` exclusion preserves the sibling clones (read-only).

## Step 11 — Ship

```
/ship
```

The ship skill already passes `--repo unseensnick/reikai --base main` (after Step 6's update lands). Commit message:

```
chore: rename fork from Yōkai-Y2K to Reikai
```

## Critical files (master list)

**Modified:**

- `i18n/src/commonMain/moko-resources/base/strings.xml` (3 strings)
- `app/src/main/res/mipmap-*/ic_launcher*.webp` (regenerated)
- `app/src/debug/res/mipmap-*/ic_launcher*.webp` (regenerated)
- `app/src/nightly/res/mipmap-*/ic_launcher*.webp` (regenerated)
- `app/src/debug/res/values/strings.xml` (if app_name override exists)
- `app/src/nightly/res/values/strings.xml` (if app_name override exists)
- `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt`
- `app/src/main/java/yokai/data/library/taste/TrackerLibraryRepositoryImpl.kt` (verify)
- `app/src/main/java/yokai/presentation/settings/screen/about/AboutScreen.kt` (verify)
- `app/src/main/res/layout/manga_header_item.xml` (+ sw600dp variants, verify)
- `.github/workflows/build_push.yml`
- `.github/readme-images/app-icon.webp`
- `README.md`
- `CHANGELOG.md` (new bullet under `[Unreleased]`)
- `CLAUDE.md`
- `.claude/rules/workflow.md`
- `.claude/rules/database.md` (verify)
- `.claude/skills/ship/SKILL.md`
- `.claude/skills/debug-fix/SKILL.md`
- `docs/dev/development.md`
- `docs/dev/settings-compose-migration.md`
- `docs/dev/tracker-aware-duplicate-detection.md` (verify)
- `docs/multi-source.md`, `docs/related-mangas.md`, `docs/flaresolverr.md` (verify)
- After the rebrand: this file (`docs/dev/reikai-rebrand.md`) — delete or archive.

**Outside the project (Claude-side memory):**

- `~/.claude/projects/E--Code-yokai-y2k-app/memory/reference_gh_default_target.md`
- `~/.claude/projects/E--Code-yokai-y2k-app/memory/MEMORY.md` (index pointer line)

**Explicitly NOT modified:**

- `app/build.gradle.kts` (`.y2k` suffix stays, no version bump)
- `app/src/standard/google-services.json` (package ID unchanged)
- `app/src/main/res/mipmap-*/ic_launcher_{blue,orange,gray,classic}*.webp` (alternates keep old artwork)
- `.claude/settings.json` `additionalDirectories` (local-disk paths, not renamed)
- Released `CHANGELOG.md` entries (historical)
- Local working directory `E:\Code\yokai-y2k\app` (not renamed on disk)
- `.github/readme-images/app-icon.inkscape.svg` (old Yōkai vector source; no Reikai vector available)
- `.github/readme-images/screens.gif` (upstream GIF; replacement is a separate task)

## Out of scope (for future tasks)

- Renaming the local on-disk folder `E:\Code\yokai-y2k\` → `E:\Code\reikai\`. Would require `.claude/settings.json` updates and is mostly cosmetic.
- Reikai-themed Blue / Orange / Gray / Classic icon variants (alternate experimental icons).
- New `screens.gif` showing Reikai UI. The inherited upstream GIF keeps working post-rebrand.
- Updating the closed upstream PR `null2264/yokai#625`. Not visible / not worth it.
