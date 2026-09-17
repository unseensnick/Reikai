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

You can see how many days the app expects to wait for the next chapter of a series **by looking at the hourglass**.
After that many days, the app will allow that series to check for updates.

::: info Light novels get the first three conditions, not the fourth
Novel sources publish no release schedule, so there is nothing to predict from and nothing to
count down. A novel is skipped for status and unread chapters as a manga is. For progress, a
novel with no chapter read counts as not started even when it has no chapters yet, so it is
skipped until you read one. Otherwise it is checked every time.
:::
