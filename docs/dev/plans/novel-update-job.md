# Novel background update job

The background worker that periodically checks favorited light novels for new chapters, the novel twin of Mihon's manga library updater, with matching "Light novel updates" settings (interval, device restrictions, per-category gating, and a smart-update filter).

## Goal

Keep saved (favorited) novels fresh on their own schedule: on a user-set interval, re-fetch each favorite from its source, write any new chapters into the database, optionally download them, and notify the user, without them having to open each novel by hand. Give that schedule the same settings shape the manga side already has, so the two libraries feel like one app.

## Why

Favorited novels need scheduled background refresh like manga. Without a worker, a novel's chapter list only updates when the user opens its details screen and pulls to refresh. New chapters published by the source would stay invisible in the library, the Updates feed (see [unified-updates.md](unified-updates.md)), and any notification, until that manual visit. Manga has had this since Mihon; novels were stale by comparison.

## Approach

WorkManager (Android's scheduled-background-work API: the job and its conditions are declared, the OS decides when to run it and survives reboots) runs one job for novels. On its turn it walks the user's favorited novels, asks each novel's source for its current chapter list, compares that against what's already stored, and saves whatever is new. If the user opted into auto-download, the new chapters get queued. A progress notification shows which novel is being checked, and a result notification summarizes how many got new chapters. The shared Updates badge count goes up so the unified Updates tab flags the new arrivals.

Two settings control *which* novels get checked, mirroring the manga "Global update" group:

- **Categories (include/exclude):** restrict the check to chosen novel categories, or exclude some. See [novel-categories.md](novel-categories.md) for how novel categories work.
- **Smart update:** skip novels that are not worth refreshing, any combination of: skip completed novels, skip novels that still have unread chapters, skip novels you have not started reading.

Interval and device restrictions (only on Wi-Fi, only when charging, only on an unmetered network) round out the group.

### Mechanism

The worker is `NovelUpdateJob`, a `CoroutineWorker` registered with WorkManager ([app/src/main/java/reikai/data/novel/update/NovelUpdateJob.kt](../../../app/src/main/java/reikai/data/novel/update/NovelUpdateJob.kt)). It is the explicit novel analog of Mihon's `LibraryUpdateJob`.

Scheduling lives in the companion object. `setupTask` reads the interval from `NovelPreferences.libraryUpdateInterval()` (0 = off) and builds a `PeriodicWorkRequest` whose `Constraints` are derived from the device-restriction preferences (`DEVICE_ONLY_ON_WIFI`, `DEVICE_NETWORK_NOT_METERED`, `DEVICE_CHARGING`); it enqueues that as unique periodic work, or cancels it when the interval is 0. `startNow` enqueues a one-shot run for manual triggers and testing. `stop` cancels the currently running drain by tag while preserving the periodic schedule.

Each run is `doWork` -> `updateNovels`. It first loads every installed novel plugin into the host once (`installer.ensureLoaded()`), then builds the candidate list by walking `novelRepo.getFavorites()` and keeping only novels that pass both gates: `shouldUpdate` (category scope) and `passesSmartUpdate` (smart-update filter). Because both gates need suspending per-novel database lookups, this is a `buildList` loop rather than a plain `.filter`.

The per-novel work is delegated to the shared `refreshNovelFromSource` helper ([app/src/main/java/reikai/data/novel/NovelRefresh.kt](../../../app/src/main/java/reikai/data/novel/NovelRefresh.kt)), the same helper the details-screen refresh uses. It re-parses the novel, persists changed metadata under the edit-lock, syncs page 1, and walks any pages opened since the novel's last known `totalPages`. New chapters are detected by `checkNovel` with a before/after diff: snapshot the stored chapter ids, run the refresh, then return the chapters whose ids were not in the snapshot. This keeps "what is new" a simple set difference rather than re-deriving it inside the sync.

Category gating is `categoryGate`, a small pure predicate shared by both the update-scope gate (`shouldUpdate`) and the auto-download gate (`shouldDownloadFor`): exclude wins, an empty include set means "everything not excluded", and callers short-circuit the no-filter case before touching the database. The Categories preferences are `novelUpdateCategories()` / `novelUpdateCategoriesExclude()`.

Smart update is `passesSmartUpdate`. It reads `NovelPreferences.novelUpdateRestrictions()` and applies the chosen checks, reusing the manga restriction constants for parallel semantics: `MANGA_NON_COMPLETED` drops completed novels, `MANGA_HAS_UNREAD` drops novels with any unread chapter, `MANGA_NON_READ` drops novels with no read chapter. The unread/started checks load the novel's chapters, so they only run for novels that already passed the category gate. An empty restriction set updates everything.

Auto-download (when `downloadNewChapters` is on) queues the new chapters through `NovelDownloadManager`, optionally narrowed to the novel's download categories and, when "only unread" is set, dropping chapters whose number matches an already-read one (`filterChaptersForDownload`). New-chapter totals feed the shared `newUpdatesCount` badge so the unified Updates tab lights up.

Notifications are `NovelUpdateNotifier` (progress + result), using novel-specific notification channels so a user can mute novel updates while keeping manga ones.

### Settings parity

The "Light novel updates" group is built by `getNovelUpdateGroup` in `SettingsLibraryScreen.kt`, fenced as a `// RK` block alongside Mihon's manga "Global update" group. Its order mirrors manga: interval -> device restrictions -> **Categories** (a tri-state include/exclude dialog over the novel categories) -> **Smart update** (a multi-select with the three skip options, enabled only when the interval is on). Deliberately dropped versus manga: show-unread-count (no separate novel Updates count surface) and refresh-metadata (novels always re-parse, so it is marginal). The preferences are unreleased, so no database migration was needed.

## Key files

- [app/src/main/java/reikai/data/novel/update/NovelUpdateJob.kt](../../../app/src/main/java/reikai/data/novel/update/NovelUpdateJob.kt): the worker: WorkManager scheduling (`setupTask` / `startNow` / `stop`), `updateNovels`, `checkNovel`, `categoryGate`, `passesSmartUpdate`, the auto-download path.
- [app/src/main/java/reikai/data/novel/NovelRefresh.kt](../../../app/src/main/java/reikai/data/novel/NovelRefresh.kt): `refreshNovelFromSource`, the shared per-novel re-parse + page-1 sync + page-walk helper, reused by `NovelUpdateJob.checkNovel` and `NovelDetailsScreenModel.refreshNovel`.
- [app/src/main/java/reikai/data/novel/update/NovelUpdateNotifier.kt](../../../app/src/main/java/reikai/data/novel/update/NovelUpdateNotifier.kt): progress + result notifications on novel-specific channels.
- [app/src/main/java/reikai/domain/novel/NovelPreferences.kt](../../../app/src/main/java/reikai/domain/novel/NovelPreferences.kt): `libraryUpdateInterval()`, the device-restriction prefs, `novelUpdateCategories()` / `novelUpdateCategoriesExclude()`, `novelUpdateRestrictions()`, and the auto-download category prefs.
- [app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsLibraryScreen.kt](../../../app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsLibraryScreen.kt): `getNovelUpdateGroup` (the `// RK` "Light novel updates" settings block: interval, restrictions, Categories, Smart update).

## Status

Shipped (P5 round 1, the light-novel vertical) and on-device verified. `refreshNovelFromSource` and `categoryGate` were later promoted to shared helpers in the Tier 0 / Tier 2-3 duplication cleanup; the per-title result notification (deep-linked per-novel entries instead of one "N novels" line) is a tracked round-2 small-polish item.

## Decisions & tradeoffs

- **Reuse `refreshNovelFromSource`, diff for new chapters.** Rather than reimplement chapter-list reconciliation inside the worker, the job calls the same refresh helper the details screen uses and computes new chapters as a before/after id set difference. One code path to maintain, identical results between manual and background refresh. The browse-open path stays separate on `insertOrGet` (it inserts a non-favorite shadow row and does not page-walk, a genuinely different operation).
- **Reuse the manga restriction constants for smart update.** `MANGA_NON_COMPLETED` / `MANGA_HAS_UNREAD` / `MANGA_NON_READ` are content-agnostic string tags. Reusing them avoids a parallel set of novel-named constants and keeps the include/exclude/restriction semantics identical to manga. The names read slightly oddly on the novel side, but renaming would touch manga code for no behavioral gain.
- **Gate before the expensive lookups.** Category and restriction checks run in a loop with the no-filter case short-circuited before any database read, and the unread/started chapter loads only happen for novels that already passed the category gate. Avoids loading chapters for novels that were never going to be checked.
- **Separate notification channels for novels.** Novel progress/result notifications use their own channels so a user can mute novel updates independently of manga updates.
- **Settings parity, with two intentional drops.** The group matches the manga order for visual consistency, but omits show-unread-count (no dedicated novel count surface) and refresh-metadata (novels always re-parse, so the toggle would be near-meaningless). Reused existing strings; no new ones, no migration (the prefs are unreleased).
