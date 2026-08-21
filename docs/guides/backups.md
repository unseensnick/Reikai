---
title: Backups
titleTemplate: Guides
description: Backups helps you prevent losing your library if something happens.
---

# Backups

_Dev records: [novel-backup.md](../dev/plans/novel-backup.md), [legacy-yokai-import.md](../dev/plans/legacy-yokai-import.md); the streaming divergence in [upstream-sync.md](../dev/upstream-sync.md). Doc map: [README.md](../README.md)._

Backups can be created to save your library data and app settings.
You can transfer and restore backup files between devices, and between **Reikai** and other apps in the same lineage.

::: tip How to create a backup
1. Go to <nav to="data-and-storage">.
1. Select **Create backup** and choose a location to save it.

<img
  class="only-light"
  src="/docs/guides/backups/backup.light.webp"
  alt="Backup and restore"
  width="672"
  height="190"
  loading="lazy"
  decoding="async"
/>
<img
  class="only-dark"
  src="/docs/guides/backups/backup.dark.webp"
  alt="Backup and restore"
  width="672"
  height="190"
  loading="lazy"
  decoding="async"
/>
:::

## General backup details

### What is included in a backup?
Backups (with pre-selected items) will contain the following:

One backup covers both libraries: everything below applies to manga and light novels alike.

#### Library data
- **Library entries**
- **Chapters** - Chapter data for saved entries
- **Tracking** - Trackers added to individual saved entries
- **History** - Read history for saved entries
- **Categories**
- **Merged groups** - The sources you grouped together under one entry, saved as source-and-address references, so they rebuild correctly even onto a fresh install
- **All read entries** - Keeps unsaved entry data (not included in automatic backups)

#### Settings data
- **App settings**
- **Extension stores**
- **Source settings**
- **Include sensitive settings** - Tracker login tokens (not included by default)

### What is not included in a backup?
- **Extensions**
- **Downloaded chapter files** including [local source](/docs/guides/local-source/) chapters
- **Custom covers** applied to entries
- **Cached cover images**, which are re-downloaded on demand
- **Android permissions** granted to the app, which you re-grant on the new install

::: tip
To convert your backups to JSON or to view and edit the information outside of the app, you can use [Mihon Backup Viewer](https://github.com/Animeboynz/Mihon-Backup-Viewer).
:::

## Restoring a backup
Restore a compatible backup file in <nav to="data-and-storage">.

::: tip
To ensure a smooth restoration process, remember to:

1. Log into the [Tracking services](/docs/guides/tracking) you previously used.
1. Download any extensions you've used in your backup.

The app will list any missing trackers and/or extensions in the Restore screen.
:::

Manga extensions are recorded rather than bundled, so until you install a matching one the entry
reappears in your library but cannot fetch chapters. **Novel plugins are the exception**: they are
re-downloaded automatically from their saved addresses once the restore finishes.

### Transferring downloads to a new installation
During the setup or after restoring a backup to **Reikai**:
1. In <nav to="data-and-storage">, double-check your specified [Storage location](/docs/faq/storage) that **Reikai** has access to.
1. Transfer or move your previously downloaded chapters into the "downloads" folder of your set Storage location.
1. In <nav to="advanced">, tap on "Reindex downloads" to rescan your downloaded chapters.

## Suggestions for backups

### Enabling automatic backups
It is highly recommended to enable automatic backups to ensure you can recover in case of any issues.

::: tip How to enable automatic backups
1. Go to <nav to="data-and-storage">.
1. Set a **backup frequency** to schedule automatic backups.
- Automatic backup files can be found in your specified [Storage location](/docs/faq/storage)'s "autobackup" folder.
- In case of an error or issue, this allows you to retain a recent copy of your library data.

<img
  class="only-light"
  src="/docs/guides/backups/automatic_backups.light.webp"
  alt="Automatic backups"
  width="672"
  height="530"
  loading="lazy"
  decoding="async"
/>
<img
  class="only-dark"
  src="/docs/guides/backups/automatic_backups.dark.webp"
  alt="Automatic backups"
  width="672"
  height="530"
  loading="lazy"
  decoding="async"
/>
:::

### Syncing backups with external cloud services
Cross device sync in **Reikai** is not currently available, but users can use
[FolderSync](https://play.google.com/store/apps/details?id=dk.tacit.android.foldersync.lite)
in order to sync backup files to Drive automatically with the following steps:

1. Install the FolderSync app from the link above.
1. Enable [Automatic Backups](/docs/guides/backups#enabling-automatic-backups) and set it to your desired frequency.
1. In the FolderSync app, navigate and select the "autobackup" folder to begin syncing to your preferred cloud service.
1. On your second device, download the latest backup from your cloud service to restore into **Reikai**.

Users who are familiar with [Autosync for Google Drive](https://play.google.com/store/apps/details?id=com.ttxapps.drivesync)
or [Tasker](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm) can setup auto sync of their backups similarly.

## Backups from other apps

**Reikai** uses Mihon's backup format, so `.tachibk` files move between apps in the same family:
[Mihon](https://mihon.app) itself and the forks it endorses, which are
[TachiyomiJ2K](https://mihon.app/forks/TachiyomiJ2K/), [TachiyomiSY](https://mihon.app/forks/TachiyomiSY/),
[TachiyomiAZ](https://mihon.app/forks/TachiyomiAZ/), [Yōkai](https://mihon.app/forks/Yokai/) and
[Komikku](https://mihon.app/forks/Komikku/). A backup from any of them restores here with your
library, categories, reading history and tracking links, and a backup made here restores in them.

Older Yōkai-based **Reikai** builds are covered as well. Reikai grew out of Yōkai before moving onto
Mihon, and the backup format did not change with it.

What does not come across is anything specific to the app that wrote the file. Every fork saves its
own settings alongside the shared data, and an app without that feature ignores them, so a round trip
is safe for your library and lossy for that app's extras.
