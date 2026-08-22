---
title: Advanced editing
titleTemplate: Local source - Guides
description: Advanced local series metadata editing for enhanced library organization.
---

# Advanced editing
Advanced local series metadata editing for enhanced library organization.

## Editing local series details

It is possible to add details to local series.
Like series from other sources, you add information about the series such as the `author`, `artist`, `description`, and `genre` tags.

Details are read from a **`ComicInfo.xml`** file in the **Series** folder. Write that file yourself: the app does not create one for a plain folder of images.

It does look inside your chapter archives once. The first time it scans a series with no `ComicInfo.xml` of its own, it takes one out of a chapter archive if it finds it there and copies it up to the series folder. Most comic archives from other tools already carry one.

::: warning That archive scan only ever runs once
If the first scan finds nothing, the app drops an empty **`.noxml`** marker in the series folder so it never re-scans, and adding a `ComicInfo.xml` to your archives afterwards will not be noticed. Putting a `ComicInfo.xml` in the series folder is what clears the marker. So if a series is stuck without details, look for `.noxml` (it is a hidden file) and write the `ComicInfo.xml` by hand.
:::

::: warning A `.json` details file is the old format
It still works, but only once. The app reads it, converts it to `ComicInfo.xml`, and **deletes the JSON**, so do not be surprised when your file disappears. Write `ComicInfo.xml` directly for anything new.
:::

The old format, for reference:

```json
{
  "title": "Example Title",
  "author": "Example Author",
  "artist": "Example Artist",
  "description": "Example Description",
  "genre": ["genre 1", "genre 2", "etc"],
  "status": "0",
  "_status values": ["0 = Unknown", "1 = Ongoing", "2 = Completed", "3 = Licensed", "4 = Publishing finished", "5 = Cancelled", "6 = On hiatus"]
}
```
::: tip
If you would rather not write metadata by hand, the Android app [Koguma-Metadata](https://github.com/ghostbear/koguma-metadata/releases/latest) and [this website](https://local.mihon.tools) both generate it for you.
:::

## Using a custom cover image

It is also possible to use a custom image as a cover for each local series.

To do this, place an image named `cover` in the root of the series folder. Any image extension works, so `cover.jpg`, `cover.png` and `cover.webp` are all fine.
The app will then use your custom image in the local source listing.
