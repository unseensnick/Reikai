---
title: Library
titleTemplate: Frequently Asked Questions
description: Frequently Asked Questions about the Library.
---

# Library
Frequently Asked Questions about the Library.

## Why is my Global Update skipping entries?
The app's default behavior is to skip updates for entries that meet the following criteria:

1. Have unread chapters
1. Hasn't been started
1. Marked with a "**Completed**" status
1. Isn't predicted to receive new chapters yet

This strategy helps reduce unnecessary requests which may lead to sources implementing measures against **Reikai** or your extensions.
* We recommend to prioritize reading your unread chapters, and letting **Reikai** update the library by itself to better adapt & predict chapter releases over time.

:::tip
If an entry has infrequent updates or no updates:
* Manually update the individual entry if you know there's a new chapter for it.
* Move the entry into a separate category, and exclude the category from your Global Updates.
:::

For more information, check out the [Smart Updates](/docs/faq/updates/smart) and [Upcoming Calendar](/docs/faq/updates/upcoming) pages.

## Why am I being warned about bulk updates and downloads?
**Reikai** provides a reminder when updating multiple titles or bulk downloading chapters, since excessive requests may trigger a source's anti-bot measures.
This can lead to stalled updates and downloads, extending the time needed to complete your requests, which also impacts your device's battery life.
### To mitigate these concerns
* In <nav to="downloads">, enable "**Auto download while reading**" to download chapters while reading.
* If possible, download in small batches at a time to avoid excessive requests to avoid slow or incomplete downloads.

#### Splitting up your Library into categories
1. Go to <nav to="library">, and create categories to segment your library ("Reading", "Plan to Read", "Completed", etc.).
1. Under the **Global update** settings, tap on **Categories** and select individual categories such as "Reading" to be included.
1. If the warning persists, create another category for infrequently updated entries (such as monthly series, on hiatus), and only include the more frequently updated reading category.

## Why aren't library updates working in the background?
Some Android skins (e.g., **MIUI**) aggressively save battery & limit performance, potentially shutting down apps in the background.
* Whitelist **Reikai** from your battery saver by going to <nav to="advanced"> and tapping **Disable battery optimization**.
* If the issue still occurs, refer to [Don't Kill My App](https://dontkillmyapp.com/) for steps on disabling specific battery-saving options for your device's brand.

## How can I see the number of downloaded chapters?
Badges can be enabled by navigating to <nav to="main_library">, then going to **Filter** and clicking the **Display** tab.
Then, at the bottom, select **Download badges**.

## Can I sync between multiple devices?
**Reikai** can't sync between devices.
Use its [backup and restore](/docs/guides/backups) features, including [auto backups](/docs/guides/backups#enabling-automatic-backups), for series database and content migration to another device.

## How can I ignore duplicate chapters?
Dealing with series translated by multiple groups that result in duplicate chapter releases?

Bookmark or mark as read the undesired chapters, then open the **Filter** menu, ensure you're on the **Filter** tab, then double-tap **Bookmarked** or single-tap **Unread**.

This hides bookmarked or read chapters, enabling you to skip them as you read.
Ensure [Skip filtered chapters](/docs/guides/reader-settings#skip-filtered-chapters) is enabled at <nav to="reader"> under the section **Reading**.

Alternatively, migrate to a source without duplicates.
Refer to the [migration guide](/docs/guides/source-migration) for detailed instructions.

## Why are some cover thumbnails corrupted or blank?
If cover thumbnails appear corrupted, blank, or broken, it's likely due to an incomplete download. Fix this by refreshing the covers in settings.

Refresh your covers at <nav to="advanced"> then tap **Refresh library covers**.

## Why have some chapters been marked as unread?
If certain series chapters are marked as unread without your interaction, it could be due to changed URLs.
**Reikai** detects these changes and interprets the chapters as new.

## How do I pause reading history?
Disable **Incognito Mode** through <nav to="incognito-mode">.

## How do I only read downloaded chapters?
Enable **Download only** via <nav to="downloaded-only">.

## Why can't I disable the Downloaded filter?
Disable **Download only** via <nav to="downloaded-only">.

## I edited an entry's title (or author). Why does sorting ignore the new name?

That is intentional. When you use Edit info to change a title, author, cover, or other
details, the change affects how the entry looks (its details page and the library, updates,
and history lists) and library search, which finds it by the name you gave it. Sorting,
category grouping, and same-title source grouping keep using the entry's original source
info.

So a renamed entry is findable under your name for it, but stays where its original title
sorts and still groups with its other sources. This is deliberate: it keeps a rename from
silently reshuffling your library or splitting a merged series. The edit is stored separately
and never overwrites the source, so Reset restores the original cleanly.
