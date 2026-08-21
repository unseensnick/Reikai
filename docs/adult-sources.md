---
title: Adult sources
titleTemplate: Guides
description: Built-in gallery sources that carry real tags, uploader and page counts into your library.
---

# Adult sources

_Dev records: [exh-subsystem.md](dev/plans/exh-subsystem.md), [adult-browse-parity.md](dev/plans/adult-browse-parity.md), [library-tag-search.md](dev/plans/library-tag-search.md), [md-enhanced-source.md](dev/plans/md-enhanced-source.md). Doc map: [README.md](README.md)._

**Reikai** has built-in support for E-Hentai and ExHentai, with richer handling than an ordinary extension gives you.
Galleries carry their real tags into your library, uploader and page count show on the details screen, and your favorites can sync with the account.

::: warning Off by default
Nothing here appears until you turn it on.
Leave the switch alone and the app behaves as though none of it exists.
:::

## Turning it on

::: tip How to enable adult sources
1. Go to <nav to="advanced">.
1. Turn on **Enable adult sources (E-Hentai)**.
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

### Image quality

Picks what the site serves you.
Auto follows your account default, and higher settings cost more of your download allowance.

### Language Filtering

Hides galleries in languages you do not read, so browsing stops being mostly noise.
Set it once and it applies everywhere you browse the source.

### Front Page Categories

Decides which categories the front page shows you at all.

### Favorites backup

Copies your account's favorites into a chosen slot, so a list you built over years is not one bad click from gone.
There is a back-up-now action, and an option to keep doing it automatically.

### Gallery update checker

Re-checks saved galleries for new pages, because galleries get revised in place rather than gaining chapters the way a series does.
It can be limited to Wi-Fi and to while charging, and it keeps statistics so you can see whether it is finding anything.

## Tags in your library

Galleries saved to your library keep their source tags, and [library search](library-search.md) can search them.
So `artist:` or a content tag finds things in your own library the same way it would on the site, without opening a browser.

This is the main reason to use the built-in source rather than a generic extension: an extension gives you the images, this gives you the metadata too.

## What this is not

**Not a general adult-content unlock.**
Other adult sources are ordinary extensions: install them from a repository in <nav to="extensions"> and they work like any other source, switch or no switch.
This page is only about the built-in E-Hentai and ExHentai support.

**Not a content filter.**
Nothing here changes what any other source shows you.

## If something is missing

**Reikai** ships a deliberately smaller slice of this than the fork it came from.
Some of the more obscure features are not here, and a few never will be.

If something you relied on is absent, say so in an issue rather than assuming it is a bug.
Most of the gaps are deliberate and easy to revisit, but only if someone asks.
