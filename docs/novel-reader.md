---
title: Novel reader
titleTemplate: Reading
description: How to read light novels in Reikai, listen to them, change the font and clean up chapter text.
---

# Novel reader

Light novels open in the same reader as manga, drawn as text instead of pages.
This page covers what you can do while reading.
Every setting it mentions, with its default, is listed in [novel reader settings](/docs/guides/novel-reader-settings).

Tap the middle of the screen to show the menu, and the gear icon in it to change settings without leaving the chapter.

## Choose how chapters are drawn

**Rendering mode**, in <nav to="novel-reader"> under **Reading**, has two choices.

* **Native text** <Badge type="info" text="Default" /> lays the text out in the app.
* **WebView** shows the chapter as a web page. It keeps more of the chapter's own formatting, and it is the only mode where the chapter's own styling, scripts and fonts, and your own CSS and JavaScript snippets, can apply.

If a chapter looks wrong in one mode, try the other.
Switching from the reader's settings sheet rebuilds the reader at the same place in the chapter.

## Read straight on to the next chapter

With **Continuous chapters** on, which it is by default, the next chapter appears below the one you are reading, so you can keep scrolling.
A marker names each boundary between chapters.
**Add the next chapter at** sets how far into a chapter you have to be before the next one is added, 95% by default.

Turn it off to read one chapter at a time.
**Swipe between chapters** then lets you swipe sideways to move to the previous or next chapter.

## Read aloud

Reikai can read a chapter out loud with your device's text-to-speech engine.

1. Open a chapter and tap the middle of the screen.
1. Tap the **Read aloud** button on the bottom bar to show the controls.
1. Tap play, or **Read from here** (the eye icon) to start from what is on screen.

The controls are icons, and they stay on screen when the menu hides.
From left to right they are **Read from here**, **Previous paragraph**, **Play** or **Pause**, **Next paragraph** and **Sleep timer**.
To stop reading altogether, long-press the **Read aloud** button.

The sleep timer stops reading after 15, 30, 45 or 60 minutes, or at the **End of chapter**.
While it runs, the notification shows how long is left.

The voice, speed, pitch and highlighting are set in <nav to="novel-reader"> under **Read aloud**.
To keep going into the next chapter when one ends, turn on **Continue to the next chapter**.

## Fonts

Tap **Font** in <nav to="novel-reader"> to open the font list.
It shows **Built in** fonts and **Your fonts**, each drawn in its own face.

To add a font, tap **Add font**, then either:

* **Import from device** to pick a TTF or OTF file you already have, or
* **Download** to search the Google Fonts library. Type a name in **Font name** and tap a match, or tap **Download** to fetch the name as typed.

A font you add is selected straight away and works in both rendering modes.
Searching Google Fonts needs a connection, but a font you already added works offline.
Fonts are kept in a `fonts` folder in your storage location, so they survive a reinstall.

To remove one, tap the bin icon beside it under **Your fonts**.
If it was in use, the reader goes back to **Default**.

The **Font** row in the reader's settings sheet picks from the same list, but cannot add fonts.

## Tidy up chapter text

Some sources send chapters with clutter in them.
The **Chapter text** section of <nav to="novel-reader"> can clean that up before a chapter is shown:

* **Hide repeated chapter title** drops a heading that just repeats the chapter's name.
* **Block images and video** skips media the chapter embeds.
* **Split walls of text** adds paragraph breaks to a chapter that arrives as one block.
* **Force lowercase** shows the whole chapter in lowercase.

**Remove extra spacing**, under **Accessibility**, strips blank space some sources leave between paragraphs.

## Find and replace

Find and replace rewrites words or phrases in every chapter before you see it, for example to fix a name a translation keeps getting wrong.
It works in both rendering modes.

1. Go to <nav to="novel-reader"> and tap **Find and replace**.
1. Tap **Add**.
1. Give the rule a **Name**, type what to **Find**, and what to **Replace with**. Leave **Replace with** empty to remove the text.
1. Use **Sample text** to check what the rule does, then tap **Save**.

**Whole words only** and **Match case** narrow what the rule matches.
**Find with a pattern** lets you write a regular expression instead of plain text.

Tap the circle beside a rule to switch it off without deleting it, or the bin icon to delete it.

## Search downloaded chapters

You can search the text of a novel's downloaded chapters, for example to find where a character first appeared.

1. Open the novel's page.
1. Open <nav to="overflow"> and tap **Search downloaded chapters**. It only appears once the novel has downloaded chapters.
1. Type what to find and search.

Each result shows the chapter, how many matches it has and a snippet around them.
Tap one to open that chapter.
**Find with a pattern**, **Whole words only** and **Match case** work as they do in find and replace.

Only downloaded chapters are searched.
On a [merged series](/docs/multi-source), the search covers the source you are viewing, or every source when you are on **All**.
