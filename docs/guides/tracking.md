---
title: Tracking
titleTemplate: Guides
description: Tracking helps track your library with different online services.
---

# Tracking

_Dev records: [novel-tracking.md](../dev/plans/novel-tracking.md), [tracker-aware-duplicate-detection.md](../dev/tracker-aware-duplicate-detection.md). Doc map: [README.md](../README.md)._

**Reikai** supports various tracking services to help you automatically update your tracking details such as read chapters, scoring, start & finish dates, etc.

Link supported tracking services in <nav to="tracking">.

## Services

**Reikai** currently supports tracking with [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://www.mangaupdates.com/), [Shikimori](https://shikimori.one/), [MangaBaka](https://mangabaka.org/), [Hikka](https://hikka.io/) and [Bangumi](https://bangumi.tv/). Signing in to MangaDex adds its own **MDList** tracker.

* You must add the desired tracker to each series to begin tracking.
* Track entries privately with supported tracking services (AniList, Kitsu, MangaBaka, Bangumi).
* You can adjust each field in the tracker entry by tapping on it.

::: warning Light novels reach fewer services
Only trackers with a real light-novel search are offered on a novel: **AniList**, **Kitsu**, **MyAnimeList**, **MangaUpdates**, **Shikimori**, **MangaBaka** and **Hikka**. Bangumi and the enhanced services below are manga only, so a novel's tracking sheet simply does not list them. Everything else on this page works the same for both.
:::

:::info Tracker behavior in Reikai
* Tracking is one-way: **Reikai -> Tracker**
* Status, Start & Finish date automatically changes when you start & complete a series.
* After reading the last page of a chapter, or marking a chapter as read, the tracker's progress will update.
* Offline progress syncs when back online.
:::

## Enhanced services

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

When an entry is part of a [multi-source group](/docs/multi-source), you do not set tracker links per source. One binding covers the whole group, and the setting that governs this is **Share trackers across merged sources** in <nav to="tracking">, on by default.

**Adding** a tracker binds it to the source you are viewing and counts for every source in the group: the chip shows on each one, reading a chapter from any of them advances it, and the library's tracker filter, score sort and status grouping all see it. There is one binding while the group is merged, never a copy per source, so the progress shown is always the group's.

**Merging** needs no tracker step. The moment entries are one group, a tracker bound on any member counts for all of them. If two members were already tracked with different remote entries on the same service, both bindings stay and the furthest-read one counts.

**Removing** a tracker unbinds it from every source in the group. Leaving a sibling's binding would keep the entry showing as tracked in the library filters even though its chip is gone.

**Splitting the group up** hands each in-library source its own copy of the bindings first, carrying the group's furthest-read progress, so both halves keep tracking on their own. That applies however you break it up: **Split** in the Manage sources sheet, the library's <icon name="unmerge"> action, **Remove from library**, or **Clear all merges** in <nav to="advanced">, which is two separate rows there, one for manga and one for novels. Two cases are skipped rather than guessed: a source that is not in your library, and a tracker whose linked remote entry disagrees across the group.

Turning the setting off gives each source its own tracking again. Existing bindings are left alone either way, so you can switch back.

::: tip Removing an entry from your library keeps its tracker rows
They are dropped only when the entry is deleted from the database, by **Clear database** in <nav to="advanced">. Re-adding a removed source brings its tracker back with it.
:::

## Refreshing every tracker at once

Open the three-dot menu in <nav to="main_library"> and tap **Refresh tracker data**.

Scores, statuses and remote progress are normally pulled when you open an entry, so an entry you have not opened lately can hold stale values, and the library can sort and filter on exactly those values. This pulls fresh data for every tracked entry in one pass, manga and light novels together, whichever library you are looking at.

It visits only entries carrying a tracker you are signed into, so an untracked library finishes immediately, and a merged entry refreshes every tracker bound anywhere in its group. When it finishes you get a count plus the names of any services that failed; the usual cause is a remote list item that no longer exists, which re-binding fixes. It is deliberately separate from the library update, which runs on a schedule, because this is a network call per bound tracker.

## General questions

### How do I log in to trackers?
1. Go to <nav to="tracking">.
1. Tap the desired tracker to begin login.

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
