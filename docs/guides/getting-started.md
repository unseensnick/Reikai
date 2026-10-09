---
title: Getting started
titleTemplate: Start here
description: Install Reikai, add a manga source and a light novel source, and read your first chapter.
---

# Getting started

Install Reikai, add somewhere to read from, and open your first chapter.

## Install Reikai

1. Download the latest version from the [download page](/download/).
1. Open the `.apk` file and follow the installer.

Reikai is not on an app store.
It checks for its own updates in <nav to="about"> under **Check for updates**.

## Add sources

Reikai comes with no sources to browse.
You add them from a repo, which is an address someone publishes listing sources you can install.
Reikai does not run or recommend any repo.

Sources come in two forms (see [extension apps and plugins](/docs/faq/browse/extensions#extension-apps-and-plugins)):

* **Extension apps** are Android apps. Every manga extension is one, and so are Tsundoku's and IReader's light novel extensions.
* **Plugins** are light novel sources in the LNReader format. They run inside Reikai and never appear in your device's app list.

::: danger Caution
Extensions and plugins from a third-party repo have full access to the app and may contain malware.
:::

### Add a repo

1. Go to <nav to="extensions">, open the three-dot menu and tap **Repos**. The same screen is in <nav to="browse"> as **Repos**.
1. Tap **Add repo** and paste the address exactly as the repo gives it.
1. Reikai works out which kind of repo it is: an extension store (for manga extensions, or Tsundoku's and IReader's novel extensions) or a plugin repo (for LNReader plugins). It refuses an address it cannot read.

A manga repo and a light novel repo are added the same way, so add one of each if you read both.

### Install a source

1. Go back to <nav to="extensions"> and pull down to refresh.
1. Tap the install button next to an extension app or a plugin.

Installing an extension app asks Android for permission the first time; see [enabling third-party installations](/docs/faq/browse/extensions#enabling-third-party-installations).
A plugin installs without asking.

You can also read files already on your device with the [local source](/docs/guides/local-source/).

## Read your first chapter

1. Go to <nav to="sources"> and tap a source, or tap **Global search** (the globe icon) to search all of them at once.
1. Browse its **Popular** or **Latest** lists, or search for a title.
1. Tap a series to open its page, then tap **Add to library** to keep it in your <nav to="main_library">.
1. Tap **Start** to open the first chapter. Next time the same button reads **Resume**.

Can't find a series? Some sources use the romanized Japanese title, such as **Boku no Hero Academia** instead of **My Hero Academia**, so try both.

## Next steps

* [Back up your library](/docs/guides/backups), and turn on automatic backups.
* [Track your reading](/docs/guides/tracking) on AniList, MyAnimeList and other sites.
* [Merge sources](/docs/multi-source) when the same series comes from more than one source.
* Adjust the [reader settings](/docs/guides/reader-settings).
