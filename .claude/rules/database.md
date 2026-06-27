---
paths:
  - "data/src/main/sqldelight/**"
  - "data/src/**/dao/**"
  - "data/src/**/database/**"
---

# Database (SQLDelight)

- **Never modify an existing migration.** Schema changes go in a new `.sqm` file under `data/src/main/sqldelight/tachiyomi/migrations/`. Existing migrations may have already run on user devices — editing them silently corrupts already-migrated databases.
- Migrations are ordered by their numeric filename prefix. New migrations get the next number.
- Test migrations before committing: write a SQLDelight migration test (start from an older schema, apply the new one, verify the result), or at minimum exercise the affected query paths against a freshly-migrated fixture.
- Never drop a column or table without confirming the data is no longer needed. Backups from older app versions still contain it.
- Backup-restore compatibility: backup files don't bind to `applicationId`, so a Reikai backup restores into upstream Yōkai and vice versa. Schema changes must not break round-tripping between forks.
- Prefer SQLDelight's `.sq` query syntax over raw `SqlDriver.execute`. The generated typesafe API is the reason we use this library.
- Index changes go in their own migration, not bundled with schema changes. Easier to roll back independently.
- Never seed data in a migration — use the app's first-run logic instead.
