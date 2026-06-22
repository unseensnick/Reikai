# Backup & restore

How to back up your library, tracking, and settings, and what to expect when you restore.

Reikai is built on Mihon, and uses Mihon's backup format: a single compressed `.tachibk` file. These backups are not interchangeable with old Yōkai-based Reikai builds: the database structure is different, so a Mihon-based install can't open a Yōkai-Reikai backup, and an old build can't open one made here.

## Creating a backup

*Settings → Data and storage → Create backup.*

Pick what to include, tap **Create**, and save the resulting `.tachibk` file somewhere safe (cloud sync, SD card, external storage).

Backups don't include downloaded chapter files. To preserve downloads, copy the app's downloads folder separately.

One option, **Include sensitive settings (e.g. tracker login tokens)**, is off by default. Turn it on to carry your tracker sign-ins and source logins into the backup, so you don't have to log in again after restoring. Leave it off if you might share the file, since it then contains credentials.

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
| Tracker & source sign-ins | Excluded by default; opt in with **Include sensitive settings** when creating the backup. Otherwise sign in again under Settings → Tracking. |
| App-level Android permissions | Re-grant on the new install |

## Merge / unmerge groups on restore

Multi-source grouping for both manga and novels is saved as stable source-and-address references, so your merged groups are rebuilt correctly when you restore, including onto a fresh install.
