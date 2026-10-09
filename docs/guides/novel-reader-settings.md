---
title: Novel reader settings
titleTemplate: Reference
description: The settings in Settings -> Novel reader, with their defaults.
---

# Novel reader settings

The settings in <nav to="novel-reader">, with their defaults.
For how to use the novel reader (rendering modes, read aloud, fonts, find and replace), see the [novel reader guide](/docs/novel-reader).
Manga has its own screen, covered in [manga reader settings](/docs/guides/reader-settings).

Most of these can also be changed while reading: open a chapter, tap the middle of the screen, and press the gear icon.

## Reading

### Rendering mode <Badge type="info" text="Native text" />
How a chapter is drawn. **Native text** lays the text out in the app. **WebView** renders the chapter as a web page, which keeps more of its own formatting and unlocks the styling and snippet options under **Chapter text**. Changing it from the reader's settings sheet rebuilds the reader in place, at the same position.

### Select text by long press <Badge type="info" text="Off" />
Lets you select and copy text. Under **Native text**, links stop responding to taps once this is on. Like **Rendering mode**, it takes effect at once.

### Continuous chapters <Badge type="info" text="On" />
Scrolls straight on into the next chapter. Off makes each chapter its own page.

### Always show chapter transition <Badge type="info" text="On" />
Shows the marker between two chapters every time, not only where chapters are missing. Only shown while **Continuous chapters** is on.

### Add the next chapter at <Badge type="info" text="95%" />
How far into a chapter you have to read before the next one appears below it. A chapter shorter than the screen adds the next one straight away. Only shown while **Continuous chapters** is on.

### Chapter title <Badge type="info" text="Name" />
What the reader's bar calls the open chapter: its **Name**, its **Number**, or **Number and name**.

### Default rotation <Badge type="info" text="Free" />
How the screen is oriented. It offers the same choices as the manga reader.

### Fullscreen <Badge type="info" text="On" />
Lets the page extend under the status and navigation bars.

### Show content in cutout area <Badge type="info" text="On" />
Draws the page into the camera cutout. Only available on a device with a cutout, while **Fullscreen** is on.

### Tap zones <Badge type="info" text="Disabled" />
How a tap on the page is read. **Disabled** shows or hides the menu wherever you tap.

**Top and bottom**, **L shaped**, **Kindle-ish**, **Edge** and **Right and Left** divide the page into zones that scroll back, scroll forward and open the menu. **Center**, **Large center** and **Bottom** draw only a menu zone, and a tap anywhere else is left to the page, so reading does not flash the menu.

### Invert tap zones <Badge type="info" text="None" />
Flips the zones horizontally, vertically or both. Hidden for layouts it would not change: **Disabled**, **Center** and **Large center**. **Bottom** only flips vertically.

### Bottom zone height <Badge type="info" text="12%" />
How tall the menu zone is. Only shown for the **Bottom** layout.

### Show tap zones overlay <Badge type="info" text="Off" />
Briefly shows the tap zones when the reader opens. The overlay always shows when you change the layout; this only adds it on opening. Hidden while **Tap zones** is **Disabled**.

### Swipe between chapters <Badge type="info" text="Off" />
Swipe sideways to move to the previous or next chapter.

### Skip chapters marked read <Badge type="info" text="Off" />
Skips over already read chapters while reading.

### Skip filtered chapters <Badge type="info" text="On" />
Skips over filtered chapters while reading.

### Skip duplicate chapters <Badge type="info" text="Off" />
Skips over chapters detected as duplicates: a chapter number one source lists more than once. In a merged novel, chapters from different sources are never treated as duplicates. With **Downloaded only** on, the copy on your device is the one kept.

### Mark chapter read at <Badge type="info" text="97%" />
How far into a chapter you have to get before it counts as read.

### Mark chapter read when skipping ahead <Badge type="info" text="Off" />
When you jump to the next chapter, marks the one you skipped as read.

### Start auto-scroll when opening a chapter <Badge type="info" text="Off" />
Starts scrolling the text on its own each time you open the reader. The **Auto-scroll** checkbox in the reader's Controls tab, and the **Auto-scroll** button once you add it under **Bottom bar buttons**, start or stop it until you leave the reader without changing this setting. It pauses while the menu is open, while your finger is on the screen and during read aloud.

### Scroll speed <Badge type="info" text="1.0x" />
How fast auto-scroll moves the text.

### Bottom bar buttons
Which buttons sit in the novel reader's bottom bar, and in what order. View chapters, rotation, text size, theme and read aloud are on by default. The settings gear can be moved like the others but not switched off.

### Resume reading position <Badge type="info" text="Off" />
Reopens a chapter where you left it, even one already marked read.

## Text display

### Font <Badge type="info" text="Default" />
Opens the font list, with **Built in** fonts and **Your fonts**, each drawn in its own face. **Default** is your device's font, or the source's own. Adding and removing fonts is covered in [the guide](/docs/novel-reader#fonts).

### Line spacing <Badge type="info" text="1.5x" />
The space between lines, as a multiple of the text size.

### Alignment <Badge type="info" text="Left" />
**Left**, **Center**, **Justify** or **Right**.

### Top, bottom, left and right margins
The space around the text. The top margin starts at 50 dp and the others at 16 dp.

### Paragraph indent <Badge type="info" text="0.0em" />
Indents the first line of every paragraph, as a multiple of your text size.

### Paragraph spacing <Badge type="info" text="1.5em" />
The gap between paragraphs, as a multiple of your text size.

## Theme

Set in the reader itself: the **Appearance** tab of the settings sheet, or the theme button on the bottom bar.

**Follow system** <Badge type="info" text="On" /> uses the light swatch while your phone is in light mode and the grey one in dark mode. The six swatches pick a fixed theme instead: light, sepia, mint, grey, dark or black.

For colours of your own, use the **Background color** and **Text color** rows below the swatches. Each opens a colour picker, and changing one keeps the other as the page shows it now. There is one custom pair, not a list of saved themes; tap a swatch or **Follow system** to leave it.

## Chapter text

These change a chapter before it is shown. Several only exist for **WebView**, because the native renderer has no stylesheet or scripts to apply them to.

### Hide repeated chapter title <Badge type="info" text="Off" />
Drops a heading at the top of a chapter that just repeats its name.

### Force lowercase <Badge type="info" text="Off" />
Shows the whole chapter in lowercase.

### Block images and video <Badge type="info" text="Off" />
Skips media a chapter embeds, for text-only reading or to save data.

### Split walls of text <Badge type="info" text="Off" />
Adds paragraph breaks to chapters that arrive as one unbroken block. **Words before a split** <Badge type="info" text="50" /> appears below it once it is on.

### Use the chapter's own styling <Badge type="info" text="On" />
Keeps a chapter's CSS instead of stripping it. **WebView** only.

### Run scripts a chapter embeds <Badge type="info" text="Off" />
Usually leftover code from the source page rather than part of the chapter. **WebView** only.

### Chapter styling wins <Badge type="info" text="Off" />
Lets the chapter's size, colour and spacing override yours. **WebView** only.

### Use the chapter's own fonts <Badge type="info" text="Off" />
Keeps the typeface a chapter asks for. **WebView** only, and hidden while **Chapter styling wins** is on, since the chapter's styling already decides the font.

### Show raw HTML <Badge type="info" text="Off" />
Shows a chapter's markup as text.

### Find and replace
Rules that rewrite chapter text before it is shown. See [the guide](/docs/novel-reader#find-and-replace).

### CSS snippets and JavaScript snippets
Your own styles and code, added to every chapter page. **WebView** only. A restored backup brings JavaScript snippets back switched off.

## Navigation

### Volume keys <Badge type="info" text="Off" />
Scrolls with the volume keys.

### Invert volume keys <Badge type="info" text="Off" />
Inverts what the volume keys do.

### Volume key scroll amount <Badge type="info" text="75%" />
How much of the screen one press moves.

### Show chapter navigator <Badge type="info" text="On" />
Shows the progress slider or the vertical navigator. With it off, neither is drawn, the previous and next chapter buttons move to the two ends of the button bar, and the settings below it are hidden.

### Vertical chapter navigator <Badge type="info" text="On" />
Shows a vertical progress slider at the side of the screen. The two settings below it only appear while it is on.

### Place vertical navigator on the left side <Badge type="info" text="Off" />
Moves that slider to the left edge, for left-handed reading.

### Vertical navigator height <Badge type="info" text="65" />
How tall the slider is, as a percentage of the screen.

### Sensitivity for hiding menu on scroll <Badge type="info" text="Low" />
How quickly you have to scroll before the menu hides: **Highest** hides it on the slightest scroll, **Lowest** only on a fast one. A change applies the next time the reader opens.

## Read aloud

How to start, pause and time read aloud is in the [novel reader guide](/docs/novel-reader#read-aloud).

### Engine
Which text-to-speech engine reads the chapter. Only shown when more than one is installed.

### Voice languages <Badge type="info" text="All" />
Narrows the voice list to the languages you pick. Only shown when the engine has voices in more than one language.

### Voice <Badge type="info" text="Default" />
The voice the engine uses.

### Speed <Badge type="info" text="1.0x" /> and Pitch <Badge type="info" text="1.0" />
How fast and how high the voice reads.

### Continue to the next chapter <Badge type="info" text="Off" />
Keeps reading into the next chapter when one ends.

### Keep paragraph in view <Badge type="info" text="On" />
Scrolls the paragraph being read back on screen when it moves off. **Scroll to top** <Badge type="info" text="On" /> below it puts that paragraph at the top rather than the middle.

### Highlight paragraph <Badge type="info" text="On" />
Marks the paragraph being read. With it on, **Highlight style** <Badge type="info" text="Background" /> picks a background, an underline or an outline, **Highlight color** picks its colour, and **Text color** sets the text drawn over a background highlight.

### Highlight sentence <Badge type="info" text="Off" />
Marks only the sentence being read instead of its whole paragraph, in the same style and colours. It sends the text to the voice one sentence at a time, so some engines pause slightly between sentences. Only shown while **Highlight paragraph** is on.

## Accessibility

### Keep screen on <Badge type="info" text="Off" />
Keeps the screen from going to sleep while reading a novel.

### Show reading progress <Badge type="info" text="On" />
Shows how far through the chapter you are.

### Bionic reading <Badge type="info" text="Off" />
Bolds the first part of each word, which some readers find easier to track.

### Remove extra spacing <Badge type="info" text="Off" />
Strips the blank space some sources leave between paragraphs.
