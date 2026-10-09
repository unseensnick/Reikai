---
title: Before you upgrade
titleTemplate: Start here
description: What to do before updating Reikai, or before moving to it from an older Reikai, Yōkai or another fork.
---

# Before you upgrade

_Dev record: [data-and-backup.md](dev/subsystems/data-and-backup.md). Doc map: [README.md](README.md)._

Most updates need nothing from you.
An update installs over the version you have, and your library and settings stay as they are.

Make a backup before any update anyway: <nav to="data-and-storage">, then **Create backup**.
[Backups](/docs/guides/backups) covers what a backup holds and how to restore one.

The two cases below are the ones that need extra steps.

## From Reikai 0.3.1 or older

Reikai 0.3.2 changed the ID Android uses to recognise the app.
Android treats it as a different app, so 0.3.2 and later install next to an older Reikai rather than over it, and nothing moves across on its own.

1. In the old app, go to <nav to="data-and-storage"> and tap **Create backup**. Tick **Include sensitive settings** so your tracker logins come along.
1. Install the new Reikai.
1. When it asks for a storage location, pick the same folder the old app used, so your downloads are found.
1. Restore the backup in the new app, from <nav to="data-and-storage">.
1. Check your library, then uninstall the old app.

Covers you set from an image on your device are not in a backup, so set those again.

## From Yōkai, Yōkai-Y2K or another fork

Reikai reads backups from Mihon and the forks it lists, so you move across by backing up in the old app and restoring that file in Reikai.
That covers [Mihon](https://mihon.app) itself and [TachiyomiJ2K](https://mihon.app/forks/TachiyomiJ2K/), [TachiyomiSY](https://mihon.app/forks/TachiyomiSY/), [TachiyomiAZ](https://mihon.app/forks/TachiyomiAZ/), [Yōkai](https://mihon.app/forks/Yokai/) and [Komikku](https://mihon.app/forks/Komikku/).
Yōkai-Y2K and older Yōkai-based Reikai builds use the same format.

1. In the old app, create a backup. A `.tachibk` or `.proto.gz` file works.
1. Install Reikai. It installs as a separate app, so the old one stays until you remove it.
1. Restore the backup from <nav to="data-and-storage">.
1. Install the extensions the Restore screen lists, and log in to your trackers again unless the backup included your sign-ins.

What comes across: your library, categories, reading history and tracking links.

What does not:

* An old `.json` backup from TachiyomiAZ does not restore.
* Settings specific to the app that wrote the file. Each fork saves its own extras next to the shared data, and an app without that feature ignores them.
* From a Yōkai backup, a default category or library update categories other than **Default** are left for you to pick again, since Yōkai saves no way to match them to your categories.
* Details you edited yourself come across from Komikku and Yōkai, except an edited cover address, which Yōkai does not keep.

Not every fork has been tested, so check the other app's own docs too.

## Going back the other way

A backup made in Reikai restores in Mihon and the forks above with your manga only.
Your light novels are saved in a part of the file those apps do not read.

Reikai 0.3.2 and older restore a backup made by this version without the details you edited yourself.
