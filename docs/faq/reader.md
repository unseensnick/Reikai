---
title: Reader
titleTemplate: Frequently Asked Questions
description: Frequently Asked Questions about the Reader.
---

# Reader
Frequently Asked Questions about the Reader.

## Why didn't the page load?
Besides network-related problems, **Reikai** may occasionally fail to recognize certain images.
To address this, simply exit and re-enter the reader, often resolving the issue.

## Can I see two pages at once?
Yes, in the two horizontal paged modes. Open a chapter, tap the middle of the screen, press the gear icon, and set **Dual page view**:

* **Never** keeps one page at a time.
* **Always** pairs pages up, which suits a tablet or an unfolded foldable.
* **When wide** only pairs them when the screen is wider than it is tall, so turning the device sideways is what switches it on.

The option only appears in **Paged (left to right)** and **Paged (right to left)**. Pairing is side by side, so the vertical and long strip modes do not offer it.

## What do all the settings do?
For detailed instructions, please consult the guides section on the website [here on reader settings](/docs/guides/reader-settings).

## I turned on Auto webtoon mode but a manhwa still opens paged. Why?

Three things cause that:

- **The source never said what it is.** Auto webtoon mode goes by what the source tells
  Reikai, not by the pictures. It looks for a "Manhwa", "Manhua", "Webtoon" or "Long
  strip" genre tag, or a source name that gives it away. Open the series' details and
  look at its genres: no such tag means there was nothing to go on. Most sites tag it,
  some skip it, and a few have pages customized enough that the tag never reaches the
  app.
- **The genres also say "Manga".** That one is checked before anything else and stops the
  guess dead, which is what keeps a mixed-content source from webtooning its whole
  catalogue. A series tagged both "Manga" and "Manhwa" is treated as manga. A "Comic" tag
  is weaker: it beats "Manhwa" and "Manhua" but loses to "Webtoon" and "Long strip".
- **You already picked a mode for that series.** Your own choice always wins. To check,
  open the reader settings sheet and look at **Reading mode** under **For this series**:
  if anything other than **Default** is selected, that is your pick overriding
  everything, and selecting **Default** hands it back.

Either way, you have two fixes:

- **Fix the tags yourself.** If the series is in your library, open the details overflow
  menu, Edit info, and add "Webtoon" or "Long strip". Those two outrank a "Comic" tag, so
  they are the safe pair to add; a "Manga" tag has to be removed either way, since nothing
  outranks it. Reikai reads your edited tags, so it picks up webtoon mode the next time you
  open the series. The edit never touches the source, so Reset undoes it cleanly.
- **Or skip tags entirely** and set the mode once from the reader's reading-mode button.
  It sticks, and it beats everything above. The button is an icon rather than a label, and
  the panel it opens lists the real modes only, with **Revert to default** below them.
