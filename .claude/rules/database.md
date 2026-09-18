---
paths:
  - "data/src/main/sqldelight/**"
  - "data/src/**/dao/**"
  - "data/src/**/database/**"
---

# Database (SQLDelight)

- **Never modify an existing migration's SQL.** Schema changes go in a new `.sqm` file under `data/src/main/sqldelight/tachiyomi/migrations/`. Existing migrations may have already run on user devices: editing their statements silently corrupts already-migrated databases. Comment-only edits (`--` lines) are allowed: comments never execute, so they can be reworded without affecting any device.
- Migrations are ordered by their numeric filename prefix. New migrations get the next number.
- **Migrations are verified by `./gradlew verifySqlDelightMigration`**, which applies every `.sqm` to the committed `data/src/main/sqldelight/43.db` snapshot and diffs the result against the `.sq` files, so a new migration is covered with nothing to add. Run it before committing a schema change; CI runs it too. Never regenerate or delete `43.db`: it is the schema as it stood before `43.sqm`, built from that commit's tree, and one generated from today's tree would verify nothing.
- Do not hand-build an older schema in a unit test (create today's, drop what a migration adds, migrate). It is true only while that migration is the newest, and broke on the next one. Test query behaviour on `Schema.create`; the verify task proves a migrated database matches it.
- Never drop a column or table without confirming the data is no longer needed. Backups from older app versions still contain it.
- Backup-restore compatibility: Reikai uses Mihon's backup format (`.tachibk`), and backup files don't bind to `applicationId`. These backups are not interchangeable with pre-rebase Yōkai-based builds (different schema); keep them compatible with the Mihon backup format so a backup round-trips across Reikai versions.
- Prefer SQLDelight's `.sq` query syntax over raw `SqlDriver.execute`. The generated typesafe API is the reason we use this library.
- Index changes go in their own migration, not bundled with schema changes. Easier to roll back independently.
- Never seed data in a migration. Use the app's first-run logic instead.
