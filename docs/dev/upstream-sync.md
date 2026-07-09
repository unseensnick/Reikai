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

## Deliberate divergences from upstream

Cases where Reikai knowingly does not match `refs/mihon`, so a future syncer does not "fix" them back. Revisit each when upstream settles. None are active right now.

_Previously:_

- The **Hikka tracker response leaks** (`de027cbf1`; `// RK` in `HikkaInterceptor.kt` + `HikkaApi.kt`) were reconciled on 2026-07-09. Upstream `mihonapp/mihon#3548` (mihon `ab58a9952`) now closes the refresh-token response on the success path and rewrites `getRead` to a `try`/`awaitSuccess` form that closes on the 404 path too, so Reikai dropped both `// RK` leak patches and took upstream. The only `// RK` code left in the Hikka tracker is the Fill-from-tracker `getMangaMetadata` (a Reikai feature Mihon lacks). See the ledger.
- The notes background-crash divergence over `mihonapp/mihon#3515` was reconciled on 2026-07-05 (Mihon shipped `mihonapp/mihon#3523`, a model-level fix that makes `Manga` serializable, so Reikai adopted it and reverted its serializable-args notes screen back to upstream). See the ledger.

## Synced-base ledger

Newest first. "Base" is the `refs/mihon` SHA Reikai is synced through; "Reikai" is the sync commit. For syncs older than the table, run `git log --oneline --grep="sync Mihon" -i`.

| Base (mihon) | Reikai | Date | Ported | Skipped / N/A |
|---|---|---|---|---|
| `217b20123` | `4b539d565` | 2026-07-09 | 3 ported. HTTP 416 resumable-download fix (mihon `4a66b8b5d`): hand-applied to the stock (non-`// RK`) `downloadImage` region of `Downloader.kt` with an import swap (add `HttpException`, drop the now-unused `storage.extension`); the disable + revert resumable-download pair (mihon `3d3bd49d1` + `b2ca6bc5f`) cancels to net-zero, so only the fix lands. `firebase-bom` 34.15.0 -> 34.16.0 (mihon `217b20123` = the new base, mihonapp/mihon#3562), one line in `gradle/libs.versions.toml`. **Deferred Weblate translations (mihonapp/mihon#3477) now resolved** (mihon `52f827c6f`): a scripted per-key 3-way merge across 21 locale files (skipped `az`, absent in Reikai) that imports Mihon's translated value for a key only if Reikai's base has it AND Reikai hasn't diverged on it (current locale value matches Mihon's value before that commit), auto-preserving the debrand (`app_name`, `crash_screen_description`) and skipping Mihon-only keys; also dropped the stale `pref_webtoon_vertical_navigator` from 13 locales (Reikai removed it from base when it ported the new vertical navigator, mihonapp/mihon#3531). 71 adds / 10 mods / 13 removes / 0 conflicts; every touched file XML-validated. Verified with `:app:compileReleaseKotlin`. | Release v0.20.1 (mihon `a8d3e3977`): N/A, Reikai keeps its `.y2k` identity, own CHANGELOG + issue templates; its `az/strings.xml` touch is a trailing-newline only. Disable/revert resumable downloads (mihon `3d3bd49d1` + `b2ca6bc5f`): cancel out, nothing to port. `CHANGELOG.md` (Reikai owns its own). |
| `52f827c6f` | `2283d39ff` | 2026-07-09 | 3 ported: Hikka media-type underscore-to-space + nullable `mediaType` (mihonapp/mihon#3560); `com.squareup.zstd` proguard keep (mihon `abef1cb8c`); removed the redundant `disable-code-shrink` build flag, hardcoding `isMinifyEnabled`/`isShrinkResources = true` on release now that the `benchmark` variant already provides a non-minified build (mihon `aa434285b`; verified Reikai carries the `benchmark` variant + `:baseline-profile` module and nothing else references `disable-code-shrink`). All in non-`// RK` lines of otherwise-patched files. Verified with `:app:compileReleaseKotlin`. | Regenerated baseline profiles (mihon `91e967128`): SKIPPED, Reikai generates its own via `:app:generateBaselineProfile`; Mihon's are Mihon-tuned. Weblate translations (mihonapp/mihon#3477): DEFERRED, a 19-file bulk mixing shared keys with Mihon-specific/debranded ones + Weblate metadata, better handled by a scripted translation refresh. `CHANGELOG.md` (Reikai owns its own). |
| `75f506836` | `69c0e33d1` | 2026-07-09 | 11 ported, 0 skipped, in 3 commits (`e0112a25e`..`69c0e33d1`). **Features:** MangaBaka tracker (mihonapp/mihon#3047), with a Reikai `// RK` `getMangaMetadata` + a `genres` field on the series DTO so Fill-from-tracker matches the other online trackers (verified genres exist on `api.mangabaka.org/v1/series`); vertical chapter navigator per reading-mode + height (mihonapp/mihon#3531), with a net-new `VerticalNavigatorMigration` renumbered to `181f` (Reikai's next release code, not Mihon's `25f`, which no Reikai upgrader's range would ever hit) and reguarded on `previousVersion != 0`; reader slider-step fix (mihonapp/mihon#3549). **Fixes / reconciliations:** Hikka unclosed-response fix (mihonapp/mihon#3548) fully covers and retires Reikai's two `// RK` leak islands (interceptor token-refresh success path + `getRead` 404), leaving only the Fill-from-tracker code; `extensionLib` metadata now read via `getFloat` (mihonapp/mihon#3545 + mihonapp/mihon#3559), retiring Reikai's `// RK` versionName-only workaround so `ExtensionLoader` is byte-identical to upstream again. **Bumps:** aboutLibraries v15 (mihonapp/mihon#3553), kotest (mihonapp/mihon#3544), conscrypt (mihonapp/mihon#3552), xmlutil (mihonapp/mihon#3555), setup-java v5.5.0 (mihonapp/mihon#3543) applied to Reikai's own workflows. Verified with `:app:compileReleaseKotlin`; migration exercised on-device at a temporary `versionCode 181`. | `CHANGELOG.md` (Reikai owns its own); no upstream `versionCode` bump adopted |
| `0787678c1` | `ee8b4c45e` | 2026-07-07 | 1: telemetry now checks whether Google Play Services is installed via `PackageManager` instead of the play-services availability API, so the check no longer depends on GMS being present (mihonapp/mihon#3525). Verbatim copy of `telemetry/.../TelemetryConfig.kt`, no RK island. | `README.md` + `CHANGELOG.md` (Reikai owns its own) |
| `d0c79399b` | `bb5c6a5fc` | 2026-07-07 | 3: chapter `memo` now updates for existing chapters (mihonapp/mihon#3538), Hikka plan-to-read default for no-progress titles (mihonapp/mihon#3534), and each tracker's username in Settings > Tracking (mihonapp/mihon#3533). For the username feature: verbatim-copied the marker-free contract/DTO/widget files + the new `MUCurrentUser.kt`; hand-merged the per-tracker login + `getCurrentUser()` changes around Reikai's `// RK` islands; `DummyTracker` needed the two new `Tracker` methods. Adapted three RK recommendation library-pull calls (Kitsu, Shikimori, Bangumi) to the changed `getCurrentUser()` return types so the taste profile still compiles. MdList (Reikai-only) left unwired for display username. | `README.md` + `CHANGELOG.md` (Reikai owns its own) |
| `aa46039e6` | `3f5c29d3e` | 2026-07-07 | 1: Hikka tracker support (mihonapp/mihon#1386), a net-new Ukrainian anime/manga tracker. Verbatim-copied the `data/track/hikka/` package + `brand_hikka.xml`; hand-merged `TrackerManager` (id `10L`, matching upstream), `SettingsTrackingScreen`, `TrackLoginActivity`, `AndroidManifest` around Reikai's existing MdList / MangaDex-OAuth `// RK` islands. No new proguard keep (`hikka` is under the existing `eu.kanade.**` keep). | `README.md` + `CHANGELOG.md` (Reikai owns its own) |
| `3b078331a` | `4acf0a54f` | 2026-07-06 | 1: unifile bump to Mihon's own fork (`com.github.tachiyomiorg` -> `com.github.mihon`, `e0def6b3dc` -> `08f224c8f9`) fixing non-system SAF provider support (mihonapp/mihon#3530). Hand-merged the two `gradle/libs.versions.toml` lines; no RK island. | None |
| `b8e5f22c0` | `414af9e9e` | 2026-07-06 | 1: proguard keep for `Serializable` `writeReplace`/`readResolve` (mihon b8e5f22c0), so the mihonapp/mihon#3523 backgrounding fix survives R8 in Reikai's minified preview/release builds. Verbatim copy, no RK island at the insert. | None |
| `c3bf7a78c` | `71b2a026a` | 2026-07-05 | 1: `Manga`-model serialization for safe backgrounding (mihonapp/mihon#3523). Reconciled the `mihonapp/mihon#3515` divergence: adopted the model-level fix and reverted Reikai's serializable-args notes screen back to upstream's whole-`Manga` `MangaNotesScreen`. | None |
| `94b3b5eaa` | `3e49c63f3` | 2026-07-04 | None (divergence review only) | `94b3b5eaa` revert of `mihonapp/mihon#3515`: Reikai deliberately kept its port at the time (reconciled 2026-07-05, see the row above) |
| `27284a40a` | `2d12e7c88` | 2026-07-04 | 4: notes background-crash serializable args (mihonapp/mihon#3515), notes text-select crash / composeRichEditor rc13 (mihonapp/mihon#3516), Shikimori GraphQL search+lookup+currentUser with a `// RK` not-in-list bind fix (mihonapp/mihon#3499), download-cache invalidation after restore (mihonapp/mihon#3096) | `mihonapp/mihon#3514` download wrong-file-check: Reikai already ships the correct logic (`3b1d34759`) |
| `0772f7202` | `d0cb409f7` | 2026-07-03 | 9: 7 dependency bumps, Shikimori `.io` (mihonapp/mihon#3497), xmlutil v1 + Compose ListItem deprecations (mihonapp/mihon#3507) | `mihonapp/mihon#3504` duplicate-images-on-resume: already fixed by Reikai `3b1d34759`, and upstream's helper logic is inverted |
| `d8c3440d3` | `77c4b0842` | 2026-07-01 | 1: resumable image downloads (mihonapp/mihon#3167) | None |
| `a82ccea6f` | `a5ebecd5b` | 2026-06-27 | 2: Gradle 9.6.1 (mihonapp/mihon#3475), Voyager 2.x (mihonapp/mihon#3466) | `19f1d00fc` Mihon v0.20.0 release |
| `6c6a07c0c` | `3484f2800` | 2026-06-26 | 3: extension-store content warning (mihonapp/mihon#3472), shortcut colors to app module (mihonapp/mihon#3473), setup-java bump | None |
| `735cea35f` | `30f53e211` | 2026-06-24 | 10: backup batching (mihonapp/mihon#3267), locale files, ChapterCache guard, deps | `1cb38a1c3` version-code bump |
| `b3e190c62` | `e9d06bced` | 2026-06-21 | 1: baseline-profile module refactor (mihonapp/mihon#3434) | None |
