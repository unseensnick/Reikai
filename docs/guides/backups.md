---
title: Backups
description: Backups helps you prevent losing your library if something happens.
---

# Backups

_Dev record: [data-and-backup.md](../dev/subsystems/data-and-backup.md); the streaming divergence in [upstream-sync.md](../dev/upstream-sync.md). Doc map: [README.md](../README.md)._

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
- **Manga** and **Novels** - Back up one library without the other, which also makes the file smaller
- **Chapters** - Chapter data for saved entries
- **Tracking** - Trackers added to individual saved entries
- **History** - Read history for saved entries
- **Categories**
- **Custom entry info** - The title, author, cover and tags you edited yourself, kept apart from the source's own values
- **All read entries** - Keeps data for entries you read but did not save

The sources behind each [merged series](/docs/multi-source) are saved as source-and-address references, so they rebuild correctly even onto a fresh install. They ride along with **Library entries** and have no checkbox of their own.

#### Settings data
- **App settings**
- **Extension stores** - Your extension repos, plus the list of installed [extension apps](/docs/faq/browse/extensions#extension-apps-and-plugins), manga and novel
- **Source settings**
- **Feed and saved searches** - The searches you saved on a source, and the Browse feed built on them
- **Include sensitive settings** - Saved sign-ins: tracker logins, the FlareSolverr sign-in, and source and plugin site sign-ins. Not included by default, and can only be ticked while App settings or Source settings is ticked

### What is not included in a backup?
- **Extension files**. Only the list of installed extension apps and the addresses of your plugins are saved
- **Downloaded chapter files** including [local source](/docs/guides/local-source/) chapters
- **Custom cover images** you picked from your device. A cover URL you typed in Edit info is kept, under **Custom entry info**
- **Cached cover images**, which are re-downloaded on demand
- **Android permissions** granted to the app, which you re-grant on the new install

::: tip
To convert your backups to JSON or to view and edit the information outside of the app, you can try [Mihon Backup Viewer](https://github.com/Animeboynz/Mihon-Backup-Viewer), a third-party tool built for Mihon's format. It may not show or keep data only Reikai stores, such as your light novels, so keep the original file before restoring one it edited.
:::

## Restoring a backup
Restore a compatible backup file in <nav to="data-and-storage">.

::: tip
To ensure a smooth restoration process, remember to:

1. Log into the [Tracking services](/docs/guides/tracking) you previously used.
1. Install the extensions the Restore screen lists. A restore installs no extension app or plugin itself, since a backup file could come from anyone; your extension and novel repos come back, so each is a tap away in Browse.

The Restore screen lists the extensions to install, any missing sources and any trackers you are not logged into.
:::

Entries from an extension you have not installed yet reappear in your library but cannot fetch
chapters until you install it. Your plugin repos ride along in **App settings** and your extension
app repos, manga and novel, in **Extension stores**, so leave both included to get them back.

### Transferring downloads to a new installation
During the setup or after restoring a backup to **Reikai**:
1. In <nav to="data-and-storage">, double-check your specified [Storage location](/docs/faq/storage) that **Reikai** has access to.
1. Transfer or move your previously downloaded chapters into your set Storage location: manga into its "downloads" folder, light novels into its "novel_downloads" folder.
1. In <nav to="advanced">, tap on "Reindex downloads" to rescan your downloaded chapters.

## Suggestions for backups

### Enabling automatic backups
It is highly recommended to enable automatic backups to ensure you can recover in case of any issues.

::: tip How to enable automatic backups
1. Go to <nav to="data-and-storage">.
1. Check **Automatic backup frequency**. It is on by default, every 12 hours, and you can change how often it runs or turn it off.
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

## Exporting a list of your library

A backup is for restoring into the app. For a list you can read or open in a spreadsheet, use **Library List** under **Export** in <nav to="data-and-storage">.

Pick the columns, **Title**, **Author** and **Artist** (all ticked to start), tap **Save** and choose where to save the file. It is a CSV file, `reikai_library.csv` unless you rename it, with one line per entry and no header row: your manga first, then your light novels. A merged series is listed once, and titles and names you changed with Edit info are written as you changed them.

## Backups from other apps

**Reikai** uses Mihon's backup format, so a `.tachibk` backup from Mihon, Yōkai and several other forks restores here, and a backup made here carries your manga to them.
What comes across each way, and the steps for moving to Reikai, are in [Before you upgrade](/docs/before-you-upgrade#from-yokai-yokai-y2k-or-another-fork).
