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
Not currently. Creating an effective dual-page reader that accommodates scanlator page inconsistencies and other complexities poses challenges. This feature may be added in the future.

## What do all the settings do?
For detailed instructions, please consult the guides section on the website [here on reader settings](/docs/guides/reader-settings).

## I turned on Auto webtoon mode but a manhwa still opens paged. Why?

The reader briefly says "Reading webtoon style" whenever it picks the mode for you, so
if you never see that, it never detected anything. Two things cause that:

- **The source never said what it is.** Auto webtoon mode goes by what the source tells
  Reikai, not by the pictures. It looks for a "Manhwa", "Manhua", "Webtoon" or "Long
  strip" genre tag, or a source name that gives it away. Open the series' details and
  look at its genres: no such tag means there was nothing to go on. Most sites tag it,
  some skip it, and a few have pages customized enough that the tag never reaches the
  app.
- **You already picked a mode for that series.** Your own choice always wins. The
  reading-mode button in the reader's bottom bar reads "Default" if you never picked
  one; anything else means your pick is in charge, and "Use default" hands it back.

Either way, you have two fixes:

- **Add the tag yourself.** If the series is in your library, open the details overflow
  menu, Edit info, and add any one of those tags. Reikai reads your edited tags, so it
  picks up webtoon mode the next time you open the series. The edit never touches the
  source, so Reset undoes it cleanly.
- **Or skip tags entirely** and set the mode once with the reading-mode button. It
  sticks, and it beats everything above.
