# Settings restructure

> **Standing rules for this work.** This is an owner-approved settings rewrite (2026-09-03), the second standing exemption from the no-standalone-refactor rule in [code-quality.md](../../../.claude/rules/code-quality.md) after the content-layer program. It is scoped to the screens named below and does not license adjacent cleanup. Two mechanics bind every change here: **a new settings screen is invisible to search unless it is registered** in `SettingsSearchScreen`'s hardcoded screen list, and **moving a row between groups changes its `HighlightKey`**, so a stale key fails silently by not scrolling rather than erroring. Search registration is part of each change, never a follow-up.

## Goal

Make Settings findable. Three screens carry the problem: Reader is one flat list of 68 rows with light-novel settings scattered through it, About bypasses the preference system entirely so nothing on it is searchable, and source settings live in four different places depending on which source you mean.

## Why

`unseensnick/Reikai#55` asks for reader settings that do not require hunting. The reporter's complaint is the number of options shown at once and the scrolling needed to find one, and their screenshot is the in-reader sheet, but the app-level screen behind it has the same defect one level up and is the cheaper half to fix.

The measured shape of the problem, taken 2026-09-03:

- **Reader is 68 rows** in ten groups on one screen, with novel settings in three non-adjacent places (`Reading · Novels`, `Accessibility · Novels`, and six rows inside `Reader navigation`) distinguished from their manga twins only by a title suffix that `contentTypedCategory` appends.
- **Source settings are in four places**: with the source (`SourcePreferencesScreen`, pushed from extension details and the catalogue overflow), at the Settings root for two enhanced sources (MangaDex, E-Hentai), under Browse > Sources (extension stores), and under Library > Sources (preferred sources). Which one is correct depends on the source, which is not something a user can know.
- **About is not a `SearchableSettings`.** It hand-rolls a `ScrollbarLazyColumn`, so nothing on it reaches settings search, including "check for updates" and "licenses". It also mixes version actions, legal links and social links in one flat list.
- **Recommendations is three levels down** behind an unrelated parent (Settings > Library > Recommendations), carrying 24 rows in five groups, and its breadcrumb already renders without "Library" because search breadcrumbs are two levels by construction.

Nothing about a feature's size predicts whether it is a group or a screen today: Recommendations is a pushed screen at 24 rows, Advanced > Network is an inline group at 13, and MangaDex is a whole top-level screen for 5 rows with no groups at all.

## Approach

Three passes, in order, each shippable alone.

### Pass 1: Reader

The single "Reader" root entry becomes two, "Manga reader" and "Novel reader", each holding its content type's complete set. Neither costs a tap more than the old Reader row did, and the `contentTypedCategory` suffix retires because the screen name now carries the content type.

**Two shapes were built and rejected before this one, and the reasons are worth keeping.** A hub screen with the shared settings on top and two drill-downs charged every manga user an extra tap to buy novel users a findable screen, which is a bad trade on volume alone (owner, 2026-09-03). Keeping the shared settings on both screens over one unified key was then rejected because the same row appearing on two screens reads as two independent settings, and a section header is too weak a signal to correct that.

**So the six same-named settings stay per-type, deliberately** (owner, 2026-09-03). `skipDupe` / `readerSkipDuplicateChapters()`, `markReadOnSkip` / `readerMarkReadOnSkip()`, `keepScreenOn` / `readerKeepScreenOn()`, and the volume-key trio `readWithVolumeKeys` / `readWithVolumeKeysInverted` / `readWithVolumeKeysScrollAmount` against `readerUseVolumeButtons()` / `readerVolumeButtonsInverted()` / `readerVolumeButtonsFraction()`. **This is a ruled per-type capability, not unpinned twin debt, and it should not be "fixed" by a later unification.** The reasoning: the write-once rule targets behaviour a user can observe being inconsistent, and these are ergonomics rather than rules. Paged images and continuously scrolling text want different answers, so wanting volume keys on for novels and off for manga is a real preference, not a mistake. Each screen is therefore self-contained, which is also what makes two screens legible: everything on a screen applies to that reader, with no cross-screen semantics to explain. Settings search disambiguates the pairs by breadcrumb ("Manga reader > Navigation" against "Novel reader > Navigation"), verified on device.

Two further pairs were never twins and stay split regardless: bottom buttons offers a different option set per type (`ReaderBottomButton.Scope.Manga` against `Scope.Novel`), and novel orientation deliberately omits two of manga's entries.

### Pass 2: About

Rebuilt as a normal `SearchableSettings` so its rows reach search, with the version and update actions ungrouped at the top and Legal and Links as groups below. The logo header and the link-icon row are `CustomPreference`s, the shape `SettingsDataScreen` already uses for its backup segmented buttons.

Two mechanics made this cheap. `SearchableSettings` extends Voyager's `Screen`, so every existing `screen = AboutScreen` call site and both `AboutScreen.getVersionName` callers keep working untouched. And the search index filters on a non-blank title, so a `CustomPreference` carrying only a composable never becomes a junk search result; the logo and link rows pass a blank title deliberately for that reason.

The update-check spinner survives because `TextPreference` already takes a `widget` composable. The two conditional rows (`updaterEnabled` for the update check, `!BuildConfig.DEBUG` for What's new) became `takeIf` on the same conditions, so they are unchanged in behaviour but cannot be exercised on a debug build, where both are false.

### Pass 3: Sources consolidation

One top-level "Sources" settings screen, absorbing the extension-stores rows from Browse and preferred sources from Library, plus a section listing the sources that carry app-owned settings (MangaDex and E-Hentai today) as drill-downs. Per-source extension preferences stay reachable from the source itself, because that is where you already are when you want them, and `SourcePreferencesScreen` gains a row pointing at the app-owned section when a source has one, so the two routes meet instead of competing. Browse settings keeps only what is about the browse screen (the feed, NSFW display).

Recommendations moves out of Library to its own top-level entry in the same pass (owner, 2026-09-03), which fixes both its depth and its truncated breadcrumb. It is already registered as a top-level search route, so search already treats it as a peer of Library.

## Key files

- `eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`, splitting into itself plus two new screens.
- `eu/kanade/presentation/more/settings/screen/Commons.kt`, `contentTypedCategory`, which retires with pass 1.
- `eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt`, the hardcoded screen list every new screen must join.
- `eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`, the root entry list.
- `eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt`, rebuilt on the DSL in pass 2.
- `SettingsBrowseScreen.kt`, `SettingsLibraryScreen.kt`, `SettingsMangaDexScreen.kt`, `SettingsEhScreen.kt`, and `reikai/presentation/recommendation/SettingsRecommendationsScreen.kt` for pass 3.
- `eu/kanade/tachiyomi/ui/browse/extension/details/SourcePreferencesScreen.kt`, gaining the cross-link in pass 3.

## Status

Ruled 2026-09-03. **Pass 1 shipped**: two top-level reader entries, each self-contained, the suffix retired, both registered for settings search. Verified on the emulator: the root list shows both entries one tap deep, each screen carries its full set, the two same-named settings are independent (toggling the novel keep-screen-on left the manga key untouched), and search returns both volume-key rows with distinct breadcrumbs. Gates green at 1334 app, 75 domain and 35 core:common tests.

**Pass 2 shipped**: About runs on the preference DSL and is registered for search. Verified on the emulator: the screen renders unchanged (logo, version with its build stamp, Legal, Links), searching "licenses" returns it as "About > Legal", and following that result lands on About.

**Both passes were then re-verified on a minified `nightly` build**, which matters because `release`-type builds are minified and the dev build is not. The app launched with no `TypeReference` failure (the R8 hazard the surviving Injekt calls carry), both reader entries and their screens rendered, and settings search still resolved rows on the new screens. The two build-gated About rows only render there: "What's new" needs a non-debug build, and "Check for updates" additionally needs the `enable-updater` Gradle property, so the check was run with `:app:installNightly -Penable-updater`. Tapping it completed end to end and toasted "No new updates available". The spinner in its `widget` slot was not observed, because the check returned inside 350ms.

Pass 3 is next. All of it runs before the reader takeover ([content-layer-reader-surface.md](content-layer-reader-surface.md) step 1).

## Decisions & tradeoffs

- **Two top-level reader entries, not one screen with drill-downs.** A hub costs the common case a tap, and the shared section it exists to hold turned out not to be wanted (see pass 1).
- **Recommendations leaves Library entirely** rather than gaining a second route, since two ways in is one of the inconsistencies this work removes.
- **MangaDex and E-Hentai are source settings, not root categories.** They sit at the root only because their preferences are app-owned rather than extension-owned, which is an implementation detail the user should not have to know.
- **E-Hentai's favorites backup stays with the source.** It reads as a Data and storage feature, but it writes to the source's own account rather than to a file, and Data and storage is about the app's own database.
- **Not in scope:** the Tracking, Downloads, Security and Appearance screens. They are consistent enough, and folding them in turns a bounded rewrite into an unbounded one.

## Known gap

The four existing dead-key skips in `PreferenceRestorer` have no test, and neither would a fifth. Nothing pins the rule that a retired key must not be resurrected by a backup restore. Recorded rather than fixed here, since backfilling a suite for four pre-existing skips is its own piece of work.
