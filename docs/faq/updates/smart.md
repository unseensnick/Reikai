---
title: Smart updates
titleTemplate: Updates - Frequently Asked Questions
description: Reikai intelligently manages series updates to reduce the load on sources.
---

# Smart updates

Reikai intelligently works to reduce the number of series in a global update to reduce the load on sources. To be updated, a series has to meet the following conditions:

1. **Series Progress**: The series must have been **started**, with at least **one chapter read**.
2. **Ongoing Status**: The series is **not marked Completed** by the source, there's an expectation of more chapters in the future.
3. **Chapter Completion**: The series has **no unread chapters**, you've read all the chapters you already have,
4. **Time Elapsed**: There's been enough time since the last update for the series that **the app predicts a new chapter is available**.

Each condition is its own option in **Smart update**, in <nav to="library">. Manga and light novels have separate copies, under **Global update · Manga** and **Global update · Novels**, and every condition starts on.

Adult gallery sources are always left out of a global update, whichever conditions you turn off, because a gallery gains pages in place rather than gaining chapters. They have their own **Gallery update checker** instead, described in [adult sources](/docs/adult-sources).

You can see how many days the app expects to wait for the next chapter of a series **by looking at the hourglass**.
After that many days, the app will allow that series to check for updates.

::: info Light novels get all four conditions
A novel's next chapter is predicted from its own chapter history, the same way as for manga, and it
has the hourglass on its details page too. For either kind, a series with no chapters yet does not
count as not started, so it keeps being checked until it has some.
:::
