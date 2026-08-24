---
title: Related manga
titleTemplate: Guides
description: Suggestions for what to read next, drawn from the source, your trackers and your taste.
---

# Related manga

_Dev record: [recommendations.md](dev/plans/recommendations.md). Doc map: [README.md](README.md)._

A row of similar titles on the manga details page, so finishing something leads somewhere.

Suggestions come from the source you are reading, from public tracker recommendations, and, once you are signed in to a tracker, from what you have already read.
The row is then reordered toward your taste, and can hide things you have already seen.

Base feature ported from [Komikku](https://github.com/komikku-app/komikku); the taste profile, the extra suggestion streams and the reordering are Reikai's.

::: info Manga only
Light novels have no equivalent yet.
:::

## Where it appears

By default the row sits on the details page, below the description.
While it loads, a placeholder holds its place so the page does not jump; if nothing comes back, the row hides itself rather than sitting empty.

Prefer it out of the way?
**Related manga placement** <Badge type="info" text="On the details page" /> in <nav to="recommendations">, under **Sources**, moves it into the three-dot menu instead.

Results are kept for about half an hour.
Switching source with the chip row fetches again for the new source.

## Where the suggestions come from

::: tip Sources
**The source itself.** Sources that publish a "related" list contribute it directly. Ones that do not simply add nothing.

**A search on the title.** The title is broken into keywords and each is searched on the current source, so the row fills in as results arrive.

**Public tracker recommendations**, from AniList, MyAnimeList, MangaUpdates and Shikimori. No sign-in needed, and no account of yours is read.
:::

Two more streams need you signed in to AniList, MyAnimeList, MangaUpdates or Shikimori, and only fire on a manga that is itself tracked there.
Both live in <nav to="recommendations">, under **Suggestions from your tracking**, a section that appears once you are signed in to one of those four, with both **Tracker recommendations** and **Show related manga** left on.

- **Because you're reading…** <Badge type="info" text="On" /> takes titles you rated highly, keeps the ones your tracker also links to the manga you have open, and pulls in what those are compared to. Narrow, and usually the best of the bunch.
- **Matching your taste** <Badge type="info" text="On" /> searches the current source for the genres you read most. It needs a source that supports genre search; on a title-only source it adds nothing.

Turn off **Tracker recommendations** <Badge type="info" text="On" /> in **Sources** and every tracker-backed stream stops, leaving the source's own suggestions.
Each of the four trackers also has its own switch under it.

## Your taste profile

The two streams above, and the reordering below, read a taste profile: the genres you read, weighted by how you rated and what you did with each series.
Completed counts most for a genre, then Reading, then On-hold. Dropped counts against it, and Plan to read counts for nothing either way.

Building it needs your tracker library, which is private, so nothing is pulled until you opt in per tracker.

::: tip How to build a taste profile
1. Go to <nav to="recommendations"> and find **Taste profile**.
1. Turn on the trackers you want pulled. AniList, MyAnimeList, Kitsu, Shikimori and Bangumi are offered, and each appears only once you are signed in to it.
1. Tap **Refresh now**.
:::

Your library is then cached locally, so the row does not call out to every tracker each time you open a page.
It updates in place when you add or change a track entry in the app, and **Auto-refresh library** <Badge type="info" text="Off" /> can also re-pull it weekly or monthly.
**Refresh now** shows when each tracker was last pulled, and has a short cooldown between presses.

The cache is not included in your backups. After a restore it rebuilds itself from your trackers on the next pull, so nothing is lost beyond the wait.
Turning a tracker's pull off drops its cached entries, so it stops shaping the row straight away; turning it back on rebuilds them on the next pull.

## Reordering the row

With **Rerank by taste** <Badge type="info" text="On" /> the source's suggestions are reordered toward your taste.
Tracker recommendations keep the order they arrived in, since they are already personal.

Two sliders shape it, and both are hidden while reranking is off.

- **Recommendation style** <Badge type="info" text="25%" /> weighs your taste against plain popularity. At 0% your taste stops counting, though titles several sources agree on still rise and the serendipity share below still applies; at 100% it is ordered almost entirely by taste.
- **Serendipity** <Badge type="info" text="20%" /> decides how much weight unfamiliar genres get, and reserves a share of the row that keeps popularity order no matter what. That reservation is what stops a high style setting from showing you the same five genres forever.

No more than two of the taste-ranked picks may share a dominant genre; the rest are pushed to the end, so one genre cannot take over the row.

With no taste profile built, the taste half of the reordering is skipped; titles that several sources agree on are still pulled forward.

## Hiding things you have seen

Every filter is off by default, so nothing is hidden until you say so.
They apply whether or not reranking is on.

All five live in <nav to="recommendations">, under **Filters**.

- **Hide manga already in my library**, matched by title across sources and trackers.
- **Hide reading & completed**, by tracker status.
- **Hide dropped**.
- **Hide on-hold**.
- **Hide plan-to-read**.

## Opening a suggestion

A suggestion from a source opens its details page on that source, ready to read.

A suggestion from a tracker opens global search with the title filled in, so you can pick a source you actually have installed.
Tracker links do not belong to any extension, so opening one directly would leave you with a library entry nothing can fetch.

## Seeing all of them

The row shows a slice.
Several sources plus tracker fan-out routinely produce far more, and when there is more than the row can hold, **See all (N)** appears beside the **Related** heading.

That opens a full grid with the same ordering and filters and no cap, on as many columns as the screen fits.

Three icons sit in its toolbar. **Select** is always there; the other two appear only when they have something to do:

- **Group by source** splits the grid into labelled sections, so you can see what came from where: *From `<source>`*, *From your `<tracker>` recommendations*, *Because you're reading `<title>`*, *Matching your taste: `<genre>`*. It appears once the suggestions come from more than one place.
- **Show hidden** brings back whatever your filters removed, without changing the filters. It appears only when something is actually hidden.

::: tip How to add several at once
1. Long-press a cover to start selecting, or tap **Select** in the toolbar.
1. Tap the others you want, or **Select all**.
1. Tap **Add to library**.
:::

Everything selected goes into one set of categories, following your **Default category** setting.
Tracker suggestions are skipped, since they map to no installed source, and a snackbar tells you how many: *Added 5 to library, skipped 2*.

::: warning Bulk add does not check for duplicates
Adding one at a time asks whether a match already in your library should be migrated or grouped.
Adding in bulk does not, because the point of the grid is speed.
:::
