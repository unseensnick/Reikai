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
Leave the switch alone and the app behaves as though none of it exists.
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
A fresh account does not have it.
If the login page loads but ExHentai still shows nothing, that is the account, not the app, and it is granted on the site.
:::

## Settings worth knowing

These live in <nav to="e-hentai">.
The first four only appear once **Enable ExHentai** is on; the Gallery update checker is there either way.

### Image quality

Picks what the site serves you.
Auto follows your account default, and higher settings cost more of your download allowance.

### Language Filtering

Hides galleries in languages you do not read, so browsing stops being mostly noise.
Set it once and it applies everywhere you browse the source.

### Front Page Categories

Decides which categories the front page shows you at all.

### Favorites backup

Pushes the galleries you have favorited in Reikai up to a chosen favorites slot on your account, so a list you built over years also lives somewhere the app cannot lose it.
There is a back-up-now action, and **Back up favorites to account** keeps doing it as you go.
Removing a gallery from your library leaves it on the account unless you tick **Also remove from E-Hentai favorites** in the removal dialog. If that account removal fails, the gallery stays in your library and a message says why, so you can try again.

### Gallery update checker

Re-checks saved E-Hentai galleries for a newer version, which then shows up as new chapters.
These galleries are left out of the normal library update, so this is the only thing that updates them.
It can be limited to Wi-Fi and to while charging, and it keeps statistics so you can see whether it is finding anything.

## Adding many galleries at once

<nav to="batch-add"> adds a list of galleries to your library in one go. It only shows in the More tab while adult sources are on.

1. Paste the gallery links, one per line. Links from E-Hentai, ExHentai, NHentai, 8Muses and Pururin work, and so does data exported from the E-H Visited browser extension.
1. Tap **Add galleries**.

Each link is matched to an installed, enabled source for its site, preferring sources in the languages you have turned on, and tried up to twice. A chapter link adds the gallery it belongs to. Galleries go into your default category, if you have set one; with the default set to always ask, they get no category.

The screen then lists each link as **[OK]** with the gallery's title, or **[ERROR]** with the reason, and ends with how many were added and how many failed. **Finish** clears it for another batch.

## Tags in your library

Galleries saved to your library keep their source tags, and [library search](library-search.md) can search them.
So `artist:` or a content tag finds things in your own library the same way it would on the site, without opening a browser.

This is the main reason to use the built-in source rather than a generic extension: an extension gives you the images, this gives you the metadata too.

## What this is not

**Not a general adult-content unlock.**
Other adult sources are ordinary extensions: install them from a repository in <nav to="extensions"> and they work like any other source, switch or no switch.
The switch registers a small set of built-in sources, listed in [built-in-sources.md](built-in-sources.md); this page covers the E-Hentai and ExHentai support, which is the part with settings of its own.

**Not a content filter.**
Nothing here changes what any other source shows you.

## If something is missing

This support was ported from another Mihon fork, and a feature you used there may not have come across.
If something you relied on is absent, say so in an issue rather than assuming it is a bug.
