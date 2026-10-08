---
title: Downloads
titleTemplate: Frequently Asked Questions
description: Frequently Asked Question about Downloads.
---

# Downloads
Frequently Asked Question about Downloads.

## How do I download multiple chapters or series at the same time?
Two settings in <nav to="downloads"> control this for manga, and both start conservative on purpose: hammering a source is how you get your IP banned from it.

* **Concurrent source downloads** is how many sources are worked at once, five by default.
* **Concurrent page downloads** is how many pages are pulled at once from each of them.

Raise the second one only for a source you know tolerates it. A source that starts returning errors or blank pages under load is telling you to put it back.

Light novels ignore both settings: their queue downloads one chapter at a time, with a pause between chapters that you set under **Pacing**.

## How do I slow novel downloads down?
Novel sources do not limit their own request rate the way manga extensions do, so a fast queue can get you blocked. The **Pacing** group in <nav to="downloads"> sets the wait between novel chapter downloads. It has no effect on manga.

* **Delay between chapters** applies to every novel source. It defaults to 0.5 s, and goes up to 10 s.
* **Delay per source** lists your novel sources so you can give one its own delay. A source that declares a minimum shows **Needs at least** that much, and its choices never go below it.

The wait also adjusts by itself: after a failed chapter it doubles, up to 30 seconds, and after a successful one it drops back towards your setting.

## Why did my downloads stop midway?
Downloads stopping midway may be related to network connection issues or source problems.
**Reikai** will provide notifications regarding encountered errors during download attempts.

## Why can't I see my downloads?
Downloads might not be detected due to multiple factors:

* Inaccessibility of the download location.
  > Ensure the SD card is properly detected if in use.
* Source name changes.
  > Rename the source's folder to match the new name. Light novel folders use the source's id, so a renamed novel source needs nothing.
* Series title modified by the source.
  > Adjust the folder title to the updated name.

## How do I manage what's downloading?
Navigate to <nav to="download-queue"> to interact with queued downloads.

Manga and light novels share one list, with one card per series. While both types are queued, each card carries a badge saying which it is.

* The **Pause** / **Resume** button pauses or resumes every download, manga and novels together.
* **Sort** is an icon in the toolbar. It orders the chapters inside each series by chapter number or upload date; tap the same option again to reverse it.
* **Cancel all** is in the overflow beside it and clears the whole queue.
* Drag a card by its handle to move that series above or below any other, or use its **Move to top** and **Move to bottom** buttons. **Cancel** on a card drops that series.

Tap a card to open the series' chapters in download order. Each row shows its progress, **Queued**, or why it failed, and has three buttons: **Start downloading now** (**Retry** on a failed chapter), **Move to bottom** and **Cancel**. **Show entry** at the top opens the series' page.

## How do I find or remove one series' downloads?
Open the series and its <nav to="overflow">. Two entries show there while the series has downloaded chapters and its source is installed:

* **Open folder** opens the series' download folder in a file manager app.
* **Clear downloads** deletes every downloaded chapter of the series. Your progress, bookmarks and history stay.

On a [merged series](/docs/multi-source), both follow the source you are viewing; on **All** they cover the whole group.

## Can I search the text of a downloaded novel?
Yes. On a novel's page, open <nav to="overflow"> and tap **Search downloaded chapters**. It searches every downloaded chapter, showing each match in context, and tapping a result opens that chapter in the reader. **Find with a pattern**, **Whole words only** and **Match case** narrow the search. Only downloaded chapters are searched.

**Word count** in the same menu counts the words in the downloaded chapters: the total, the words per chapter, and a density from 1 to 10 for how long the chapters run.

Both are for light novels only.

## Can I use both internal storage and external SD card storage?
No, you must choose a single location. Internal storage performs better than external SD cards.

## Why does my device photo gallery contain series pages?
**Reikai** typically prevents series pages in downloads from appearing in your device's photo gallery by default through a `.nomedia` file.
However, in some cases, this might not function as intended.

A quick solution is to create the `.nomedia` file yourself, name it as such, and place it in your downloads folder. If the issue pertains to local source, put the `.nomedia` file in the respective local folder.

## How are downloads organized on the filesystem?
They are stored as `downloads/Source Name/Manga Name/Chapter Name_abcdef.cbz`, where the six characters after the underscore are a hash of the chapter's address.
Light novels use a folder of their own, `novel_downloads/Source id/Novel Name/`, with each chapter saved as `Chapter Name_abcdef.html`. The source folder is named after the source's id, not its display name: a [plugin](/docs/faq/browse/extensions#extension-apps-and-plugins)'s id, or for a novel extension app `tachiyomi_` or `ireader_` followed by the source's number.
The `abcdef` string is the first 6 hexadecimal digits of the MD5 hash of the URL of the chapter, so that if two chapters have the same name, they won't try to write to the same filename.
**Enable chapter name hash suffix** in <nav to="advanced"> decides whether chapter names carry it, for manga and novels alike. It is off on a fresh install and stays on for an install that had it before. With it off, only the first of two chapters that share a name can be downloaded; the other counts as downloaded and opens that one's copy.
For a manga chapter with a scanlator, it is `Scanlator Name_Chapter Name` instead of just `Chapter Name`. Novel chapters never carry a scanlator prefix.

Because of the prevalence of operating systems like Windows which have arbitrary limitations on special characters in filenames, by default Reikai will avoid using certain characters in filenames, specifically: `"*:<>?\|`.
Of course, `/` is also banned.
All of these characters are replaced by underscores if they appear in source, manga, chapter, or scanlator names.

Some users have reported using exceptionally buggy operating systems which also have problems with other Unicode characters, such as (but not necessarily limited to) emojis.
If you must use Reikai with such an operating system, turn on **Disallow non-ASCII filenames** in <nav to="advanced">, which keeps any non-ASCII character out of a filename.
Such characters will be replaced with their hexadecimal representations instead.
The special characters mentioned above are still replaced with underscores, if present, rather than hexadecimal.

None of the above considerations affect the way series and chapters are displayed in Reikai, which is based on their metadata rather than filenames.
Because the local source reads comic metadata files, if present, its functioning is also not affected by filename changes if you convert an external source download directory into a local source directory.

If you change that setting after downloading anything, you may need to do some manual work so Reikai can still find those downloads.
Chapter filenames do not need to be changed, as Reikai is able to check multiple options for a chapter filename, and will find the already-downloaded chapters.
Manga and source directory names, however, need to be updated manually if they contain non-ASCII characters.
Here is an example:

```text
./例 ソース (ALL)/最高のマンガ！/Scanlator Name_始まり_182cce.cbz
./e4be8b20e382bde383bce382b9 (ALL)/e69c80e9ab98e381aee3839ee383b3e382acefbc81/Scanlator Name_e5a78be381bee3828a_182cce.cbz
```

The inability for a device to handle Unicode characters in filenames is a bug.
Please consider contacting your device or operating system vendor to report this, or consider using a standards-compliant device in future, if possible.

## A download keeps failing. Is that a bug in Reikai?

Usually not. See [a download keeps failing](/docs/guides/troubleshooting/#a-download-keeps-failing) in the troubleshooting guide.
