---
title: Statistics
description: Charts of your library and reading, for manga and novels, with lists you can open in the library.
---

# Statistics

_Dev record: [statistics.md](dev/subsystems/statistics.md). Doc map: [README.md](README.md)._

Open <nav to="statistics">. The **All** / **Manga** / **Novels** chips at the top choose what every number, chart and list below counts.

## The overview

The first four cards count your library, chapters and trackers.
Below them:

* **Publishing status** and **Tracking status** show how your series split, with the counts beside each chart. Tracking status counts series you track, by the status of your first signed-in tracker.
* **Scores** shows how many series sit at each mean tracker score from 1 to 10.
* **Reading this week** shows your reading time for the last seven days, your current streak of reading days and your longest one.
* **Sources**, **Tags**, **Categories**, **Trackers**, **Languages**, **Series length** and **Started reading** list your top five in each.

On a tablet or an unfolded phone the cards sit side by side in three groups, **At a glance**, **Charts** and **Your library by**, with every card in a row the same height. A phone shows one column.

A series merged from several sources counts once, except in **Sources**, **Tags**, **Trackers** and **Languages**, where it counts under each one it has. Those lists can add up to more than your library.

## Opening a list

**See all** on a card opens the whole list, one card per row on a phone and several side by side on a wider screen. Each shows how many of its series you have **Finished** (read every chapter), are **Reading** and have **Not started**, then its mean score and reading time. Where the list has no order of its own, the sort button in the top bar orders it by most series, chapters read, mean score or reading time.

Tap a row to open those series in your library. The library opens with a search already typed, such as `srcid:"..."` or `category:"Plan to read"`, which you can edit like any other [library search](library-search.md).

## Reading time

**Open** on **Reading this week** shows a week of reading. Tap a day's bar to list what you read that day and for how long, and use the arrows to step a week back or forward.

Reading time per day is recorded from version 0.5.0 on. Reading from before that still counts toward the overview's total, but not toward any day, streak or the reading-time lists, since earlier versions kept only each chapter's total.
Clearing all of your history clears the daily record too.

## Started reading

**Started reading** groups your series by the year you started them. It uses the start date a tracker holds for the series. Without one, it uses the first day the app recorded you reading it. A series with neither is **Unknown**.
