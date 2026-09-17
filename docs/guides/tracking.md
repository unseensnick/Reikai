---
title: Tracking
titleTemplate: Guides
description: Tracking helps track your library with different online services.
---

# Tracking

_Dev records: [novel-tracking.md](../dev/plans/novel-tracking.md), [novel-specific-trackers.md](../dev/plans/novel-specific-trackers.md), [tracker-aware-duplicate-detection.md](../dev/tracker-aware-duplicate-detection.md). Doc map: [README.md](../README.md)._

**Reikai** supports various tracking services to help you automatically update your tracking details such as read chapters, scoring, start & finish dates, etc. Not every service stores all of that, so a tracker only shows the fields it can actually save.

Link supported tracking services in <nav to="tracking">.

## Services

**Reikai** currently supports tracking with [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://www.mangaupdates.com/), [Shikimori](https://shikimori.one/), [MangaBaka](https://mangabaka.org/), [Hikka](https://hikka.io/) and [Bangumi](https://bangumi.tv/). Signing in to MangaDex adds its own **MDList** tracker.

* You must add the desired tracker to each series to begin tracking.
* Track entries privately with supported tracking services (AniList, Kitsu, MangaBaka, Bangumi).
* You can adjust each field in the tracker entry by tapping on it.
* Start and finish dates reach MyAnimeList, AniList, Kitsu, Hikka and MangaBaka. The rest store no dates, so those rows are not shown.

::: warning Each type sees only the trackers that catalogue it
A tracking sheet lists only the services whose catalogue holds that kind of entry, so a manga and a novel offer different lists.

* **Both**: AniList, Kitsu, MyAnimeList, MangaUpdates, Shikimori, MangaBaka and Hikka.
* **Manga only**: Bangumi, MDList and the enhanced trackers below.
:::

:::info Tracker behavior in Reikai
* Tracking is one-way: **Reikai -> Tracker**
* Status changes automatically when you start & complete a series, and so do the start & finish dates on a service that stores them.
* After reading the last page of a chapter, or marking a chapter as read, the tracker's progress will update.
* Offline progress syncs when back online.
:::

## Enhanced trackers

**Reikai** also supports various self-hosted services with exclusive features between the service & the self-hosted series.

::::tabs
==Komga
* No separate login required.
* **Komga** series will automatically track.
* Two-way sync for local chapters.
* Manually read chapter syncs with delay.

> Learn how to set it up on the [Komga](https://komga.org/) website.
==Kavita
> Learn how to set it up on the [Kavita](https://www.kavitareader.com/) website.
==Suwayomi
> Learn how to set it up on the [Suwayomi](https://suwayomi.org/) website.
::::

## Trackers on a merged entry

When an entry is part of a [multi-source group](/docs/multi-source), **Share trackers across merged sources** in <nav to="tracking">, on by default, links a tracker across the group.

**Manga:** adding a tracker to one source, or merging entries where one is already tracked, copies that tracker onto every other source of the group that is in your library. Each source keeps its own copy, so unmerging needs no tracker step. Removing a tracker removes it only from the source you are viewing.

**Light novels:** the group shares one tracker while merged, and removing it clears it from every source. Unmerging from the library copies it onto each source first, so both halves keep tracking.

A tracker whose linked remote entry differs across the group is skipped rather than guessed. Turning the setting off stops new links; existing ones are left alone.

::: tip Removing an entry from your library keeps its tracker rows
They are dropped only when the entry is deleted from the database, by **Clear database** in <nav to="advanced">. Re-adding a removed source brings its tracker back with it.
:::

## General questions

### How do I log in to trackers?
1. Go to <nav to="tracking">.
1. Tap the desired tracker to begin login.

Which login you get depends on the service: most open your browser, and a few ask for a username and password in the app.

### How do I set up tracking for each series?
1. Go into the series.
1. Tap the **Tracking** button.
1. Tap **Add tracking** for the desired service.

::: tip
Search with a different title if there is no match.
:::

### How do I log in with Kitsu?
* To log in with Kitsu, you need to use your email address as your username.

### Why is Kitsu's genre list shorter?
* If your Kitsu account still has its SFW filter on, Kitsu leaves its adult categories out before Reikai ever sees them, so **Fill from tracker** gives a Kitsu-bound entry fewer genres than another tracker would.
* Nothing in Reikai can bring them back. To get them, turn the SFW filter off in your Kitsu account settings.

### Can't find a series on MyAnimeList?
* If you cannot find a series by name, you can look it up on MyAnimeList and then search for it in **Reikai** using the following format: `id:<id from series URL>`.
* You can also search for a series on your MAL profile list by searching in the following format: `my:<series name>`.

### Finding tracked/untracked series in your library
* On your <nav to="main_library"> page, tap the **Filter** button (three-lines icon), then include or exclude **Tracked**.
* If you are logged into more than one tracker, toggle each tracker you want to include or exclude.
