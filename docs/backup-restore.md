# Backup & restore

How to back up your library, tracking, and settings, and what to expect when you restore.

Reikai is built on Mihon, and uses Mihon's backup format: a single compressed `.tachibk` file. These backups are not interchangeable with old Yōkai-based Reikai builds: the database structure is different, so a Mihon-based install can't open a Yōkai-Reikai backup, and an old build can't open one made here.

## Creating a backup

*Settings → Data and storage → Create backup.*

Pick what to include, tap **Create**, and save the resulting `.tachibk` file somewhere safe (cloud sync, SD card, external storage).

Backups don't include downloaded chapter files. To preserve downloads, copy the app's downloads folder separately.

## Restoring a backup

*Settings → Data and storage → Restore backup → pick the .tachibk file.*

The restore replaces the current library state with the backup. The app warns before applying, so an accidental tap is recoverable until you confirm.

Backups record which sources and extensions you were using:

- **Manga extensions** you had installed are recorded, so a restore can offer to reinstall them. Until the matching extension is installed, the manga reappears in your library but can't fetch chapters.
- **Novel plugins** are re-downloaded automatically from their saved addresses after a restore.

## What's included

A backup covers both your manga library and your light-novel library.

**Manga**

- Saved manga, categories, and category assignments
- Read / unread chapter state and reading history
- Tracker links (the connection between a manga and its tracker entry)
- Which manga extensions you had installed

**Light novels**

- Saved novels with their chapters, read state, and reading history
- Novel categories and assignments
- Tracker bindings for novels
- Manual novel merge / unmerge groups

**App-wide**

- App settings (themes, reader preferences, library preferences)

## What's not included

| Not included | Why |
|---|---|
| Downloaded chapter files | Copy the downloads folder separately |
| Cover image cache | Re-downloaded on demand |
| Tracker sign-in / authentication | Sign in again under Settings → Tracking for security |
| App-level Android permissions | Re-grant on the new install |

## Merge / unmerge groups on restore

Multi-source grouping behaves differently for novels and manga:

- **Novel merge / unmerge groups** are saved as stable source-and-address references and are rebuilt correctly when you restore, including onto a fresh install.
- **Manga merge / unmerge groups** are stored using internal ids that aren't re-mapped on restore. After restoring to a fresh install, manga grouping may not come back correctly. If your manga grouping matters, expect to redo some of it after a restore.

## Migrating from an old `.yokai` build (1.9.7.5.x)

This is past history for users coming from a much older release. Reikai once changed its release package suffix from `.yokai` to `.y2k`. Android treats apps with different package ids as separate installs, so a `.y2k` build did not auto-update an existing `.yokai` build.

If you're still on a very old `.yokai` install:

1. Open the old install → Settings → Data and storage → **Create backup**. Save the file off-device.
2. Install the current build. Both apps appear in your launcher as separate installs.
3. Open the current build → Settings → Data and storage → **Restore backup** → pick the backup from step 1.
4. Verify the restore looks correct, then uninstall the old build.

Back up before uninstalling: Android wipes per-app data on uninstall, and there's no in-place transfer between two separate installs.

Note: very old `.yokai` builds predate the move to Mihon, so their backups may not restore into the current build at all. Treat that migration as best-effort and keep the old backup file.
