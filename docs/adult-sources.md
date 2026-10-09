---
title: Adult sources
titleTemplate: Guides
description: Built-in gallery sources that carry real tags, uploader and page counts into your library.
---

# Adult sources

_Dev records: [exh-subsystem.md](dev/plans/exh-subsystem.md), [adult-browse-parity.md](dev/plans/adult-browse-parity.md), [library-tag-search.md](dev/plans/library-tag-search.md), [md-enhanced-source.md](dev/plans/md-enhanced-source.md). Doc map: [README.md](README.md)._

**Reikai** has built-in support for E-Hentai and ExHentai, with richer handling than an ordinary extension gives you.
Galleries carry their real tags into your library, uploader and page count show on the details screen, and your favorites can be backed up to the account.

::: warning Off by default
Nothing here appears until you turn it on.
Leave the switch alone and the built-in adult sources, their settings and Batch add stay hidden.
Adult extensions you install yourself are not tied to it, and gallery links shared from those sites still open in Reikai through them, see [What this is not](#what-this-is-not).
:::

## Turning it on

::: tip How to enable adult sources
1. Go to <nav to="browse">.
1. Under **Extensions**, turn on **Enable adult sources (E-Hentai)**.
:::

E-Hentai then appears in <nav to="main_browse">, and you can search and read from it straight away without an account.

## Adding ExHentai

::: tip How to add ExHentai
1. Go to <nav to="e-hentai">, which only appears once adult sources are on.
1. Turn on **Enable ExHentai**, which opens a login page.
1. Sign in there. ExHentai joins E-Hentai in <nav to="main_browse">.
:::

::: warning ExHentai needs an account that already has access
A new account does not have it, and access is granted by the site, not the app.
If you sign in but ExHentai still shows nothing, check your account on the site first.
Some accounts only work with a specific igneous cookie: on the login page, tap **Advanced**, then **Custom igneous cookie**, and paste it in.
:::

## Settings worth knowing

These live in <nav to="e-hentai">.
The first four only appear once **Enable ExHentai** is on; the Gallery update checker is there either way.
After you sign in, and whenever you change one of the account settings that feed the site (Hentai@Home, Japanese titles, original images, the tag filtering and watching thresholds, Image quality, Language Filtering or Front Page Categories), Reikai saves them to a settings profile of its own on your E-Hentai and ExHentai accounts, replacing the one it made before.
That needs a free profile slot on each site, so keep no more than two other profiles there, or the upload fails.

### Image quality

Picks what the site serves you.
Auto leaves the size to the site, and the other choices fix it at 2560x, 1920x, 1280x or 800x.
On the site, larger images tend to use up your image limit faster, and the two largest sizes may need a perk on your account, so check E-Hentai's own help if a size does not take.

### Language Filtering

Hides galleries in languages you do not read, so browsing stops being mostly noise.
Set it once and it applies everywhere you browse the source.

### Front Page Categories

Decides which categories the front page and searches show by default.
A hidden category still shows when you turn on its filter.

### Favorites backup

Pushes the galleries you have favorited in Reikai up to a chosen favorites slot on your account, so a list you built over years also lives somewhere the app cannot lose it.
There is a back-up-now action, and **Back up favorites to account** keeps doing it as you go.
Removing a gallery from your library leaves it on the account, unless you remove it from its details screen and tick **Also remove from E-Hentai favorites** in the dialog that asks. If that account removal fails, the gallery stays in your library and a message says why, so you can try again.

### Gallery update checker

Re-checks saved E-Hentai galleries for a newer version, which then shows up as new chapters.
These galleries are left out of the normal library update, so this is what checks them in the background; refreshing a gallery's details screen still picks up a new version too.
Pururin and nHentai galleries are left out of the library update as well, and have no checker of their own.
It runs daily by default, can be limited to Wi-Fi, an unmetered network or while charging, and **Show updater statistics** tells you when it last ran and how many galleries it has checked.

## Adding many galleries at once

<nav to="batch-add"> adds a list of galleries to your library in one go. It only shows in the More tab while adult sources are on.

1. Paste the gallery links, one per line. Links from E-Hentai, NHentai and Pururin work, ExHentai links once ExHentai is on, and 8Muses links once its extension is installed. Data exported from the E-H Visited browser extension works too.
1. Tap **Add galleries**.

Each link is matched to an installed, enabled source for its site, preferring sources in the languages you have turned on, and tried up to twice. A link to a single page of a gallery on E-Hentai, ExHentai, NHentai or 8Muses adds the whole gallery. Galleries go into your default category, if you have set one; with the default set to always ask, they get no category.

The screen then lists each link as **[OK]** with the gallery's title, or **[ERROR]** with the reason, and ends with how many were added and how many failed. **Finish** clears it for another batch.

## Tags in your library

Galleries saved to your library keep their source tags, and [library search](library-search.md) can search them.
So `artist:` or a namespaced tag such as `female:glasses` finds things in your own library, written the way the site writes tags, without opening a browser.

This is the main reason to use the built-in source rather than the stock E-Hentai extension: the extension gives you the images, the built-in source gives you the metadata too.

## What this is not

**Not a general adult-content unlock.**
Other adult sources are extensions you install from a repository in <nav to="extensions">, and the switch does not gate them.
Some of them, the Enhanced rows in [built-in-sources.md](built-in-sources.md), get searchable tags in your library whenever they are installed, and nHentai galleries from its extension are left out of the normal library update.
The one exception is the stock E-Hentai extension, which is hidden while the switch is on, because the built-in source replaces it.
The switch registers a small set of built-in sources, listed in [built-in-sources.md](built-in-sources.md); this page covers the E-Hentai and ExHentai support, which is the part with settings of its own.

**Not a content filter.**
Nothing here filters what other sources show you.
The one source the switch touches is the stock E-Hentai extension, which is hidden while the switch is on because the built-in E-Hentai support takes its place.

## If something is missing

A feature you used in another app may not be here.
If something you need is missing, ask for it in the [Ideas discussion](https://github.com/unseensnick/Reikai/discussions/categories/ideas).
