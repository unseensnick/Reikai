# Legacy Yōkai database import

## Goal

When a user updates in place from an old Yōkai-based build (the pre-rebase `1.9.x` line) to a Mihon-based Reikai, recover their manga and novel libraries, plus their extension repositories, automatically on first launch instead of crashing on startup.

## Why

Reikai keeps the same `applicationId` (`eu.kanade.tachiyomi` + `.y2k`) and an ever-climbing `versionCode` across the Yōkai → Mihon rebase so existing installs upgrade in place. But the two forks share the same database file name, `tachiyomi.db`, with **independent and now-crossed schema version numbers**: the Yōkai schema sits at version ~40 (migrations up to `39.sqm` on the `design/library-compose` branch), while Mihon's is ~27 (migrations up to `26.sqm`). SQLDelight's driver compares the on-disk version against the code's schema version; because the Yōkai DB reports a *higher* number, it concludes the database is newer than the code, runs **no** migrations, and then queries a Yōkai-shaped DB through Mihon's tables. The first query hits a table Yōkai never had (`extension_store`) and the app crashes before any UI draws.

This affected every existing Yōkai-Y2K user updating to a Mihon-based release (both 0.1.0 and 0.1.1), reported as [unseensnick/Reikai#11](https://github.com/unseensnick/Reikai/issues/11) (surfaced as a Xiaomi/MIUI crash dialog, but not device-specific). Fresh installs and Mihon-era upgrades (0.1.0 → 0.1.1) are unaffected, which is why the separately-packaged preview build (a fresh `.debug` install) appeared to "work".

## Approach

On launch, before the app opens its database, peek at the existing `tachiyomi.db`. If it's a legacy Yōkai one, read the library out with plain SQL, package it into a normal backup file using the app's own backup model classes, move the old DB aside (renamed, never deleted), let SQLDelight create a fresh empty DB so the crash can't happen, then feed the backup through the existing, already-proven restore pipeline. The user lands in a working app with their library restored.

Settings and tracker logins are untouched: those live in SharedPreferences, which survive an in-place update, so only the database content needs recovering.

Mechanism:

- **Detection** is by schema signature, not version number, so it never false-positives on a Mihon DB: Yōkai's `categories` table carries a `manga_order` column that Mihon's never has.
- **Extraction** runs read-only raw SQL over the legacy tables (`mangas`, `chapters`, `categories`, `mangas_categories`, `history`, `manga_sync`, and the `novel*` equivalents) and builds Mihon's `Backup*` objects directly. Because the backup is built with Mihon's own classes, there is no proto-version compatibility concern; column reads are tolerant of older Yōkai DBs missing newer columns. Only favorited entries (the library) are taken.
- **Extension repos** are read from the old `extension_repos` table (which Mihon's migration `11.sqm` would normally convert, but the whole chain was skipped, so it is still present) and converted to the current `extension_store` schema, mirroring that migration (`index_url = base_url + "/repo.json"`, legacy flag set). They restore via `RestoreOptions(extensionStores = true)`. Extensions themselves are not reinstalled: they are separate packages that survive an in-place update and are rediscovered at startup.
- **Merges land flat.** Merge groups live in SharedPreferences keyed by id, which survive the in-place update, but the reset reassigns ids, so the stale id pairs would collide and wrongly group (or hide) unrelated entries. The importer clears all merge/unmerge prefs and disables same-title auto-merge, so the migrated library shows every entry in all its categories. The user re-merges in-app. This is migration-only (it runs solely from the legacy-DB path), so a normal restore on a fresh install is untouched.
- **Reset** moves `tachiyomi.db` (and its `-wal`/`-shm`) to `tachiyomi.db.yokai.bak` so a failed extraction still stops the crash and the original data stays recoverable.
- **Restore** writes the backup to a temp `.tachibk` (gzipped protobuf, same as `BackupCreator`) and enqueues `BackupRestoreJob` once DI and the fresh DB are ready, with a one-time toast.

Because the crash happens at query time before anything writes, an already-bricked 0.1.0/0.1.1 install still has its Yōkai tables intact, so updating to the fixed build recovers it.

## Key files

- `app/src/main/java/reikai/data/legacy/LegacyYokaiDbImporter.kt`: detection, extraction, backup write, DB-aside.
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`: `// RK` islands in `onCreate` (`prepareIfLegacyDb` before the DB is first opened, restore enqueue with `extensionStores = true` at the end).
- `i18n/.../base/strings.xml`: `legacy_import_notice`.
- Reuses `eu.kanade.tachiyomi.data.backup.models.*`, `BackupRestoreJob`, `RestoreOptions`, `ReikaiLibraryPreferences`.

## Status

Shipped in 0.1.2. Verified on the emulator end to end, both with a synthetic Yōkai DB and with a real Yōkai-era backup: the crash reproduces on 0.1.0, and updating to the fixed build recovers the library (266 manga and 2 extension repos in the real-data run) with chapters, read state, categories, history, and tracking; the old DB is preserved as `.yokai.bak`; a fresh DB is created (`extension_store` present, no crash); and the library renders flat, every entry visible in its categories with no manga hidden by stale merges.

## Decisions & tradeoffs

- **Recover via backup/restore, not a schema migration.** Translating the Yōkai schema into Mihon's in place is the large, risky work the rebase deliberately avoided. Reusing the proven restore pipeline is smaller and safer, and the backup proto is already compatible across the rebase (a Yōkai backup file restores into Mihon).
- **Signature detection, not version comparison.** The `manga_order` column is a driver-agnostic marker, robust regardless of how the SQLite driver stores its version, and cannot match a Mihon DB.
- **Reset on extraction failure, but never delete.** The priority is to stop the crash; the old DB is renamed aside so data is always recoverable.
- **`android.util.Log`, not the `logcat` extension.** The importer runs before `LogcatLogger` is installed in `App.onCreate`, so the extension would silently drop its lines.
- **Migrate extension repos, not extensions.** The repo list carries over so the user keeps updating/adding sources. Extensions themselves are not reinstalled: they are separate packages that survive an in-place update, so sources resolve on a real device, and a reinstall from a snapshot of the installed set would be a no-op (recorded equals installed).
- **Land the migrated library unmerged.** Preserving merges across an id reset is fragile: stale id pairs collide, and a merged group displays under a single member so other members' categories look empty. Clearing merges and disabling same-title auto-merge guarantees every entry is visible; users re-merge in-app. This was chosen over remapping merges to `{url, source}` refs, which still left categories looking sparse because of the single-representative display.
- **Library only.** Reading history for Yōkai novels isn't carried (Yōkai stored novel progress on the chapter row, which is preserved, not in a history table); per-manga reading-mode flags ride the packed `viewer` value through unchanged.
