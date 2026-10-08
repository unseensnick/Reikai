---
title: Reader settings
titleTemplate: Guides
description: This section relates to the reading experience in the app and navigating the reader.
---

# Reader settings

Manga and light novels are read in different ways, so each has its own settings screen: <nav to="manga-reader"> and <nav to="novel-reader">. A setting that appears on both, such as **Keep screen on**, is stored separately for each.

Most of these can also be changed while reading: open a chapter, tap the middle of the screen, and press the gear icon.

## Manga reader

#### Default reading mode <Badge type="info" text="Paged (right to left)" />

This setting sets the reader's default direction when you open a series.

::: tabs
== Paged (right to left)
Right-to-left, the default way of reading manga.

- Swipe right for next page.
- Swipe left for previous page.
== Paged (left to right)
Left-to-right, the default way of reading comics.

- Swipe left for next page.
- Swipe right for previous page.
== Paged (vertical)
- Swipe up for next page.
- Swipe down for previous.

== Long strip
Default way of reading webtoons.
== Long strip with gaps
Long strip but with a little space between pages.
:::

::: tip
You can set a different mode for one series: open a chapter, tap the middle of the screen, press the gear icon, and pick from **Reading mode**.
:::

#### Double tap animation speed <Badge type="info" text="Normal" />
Double tap animation speed changes the speed in which the zoom happens when double tapping.

::: tabs
== No animation
<video src="/docs/guides/reader-settings/animation-speed_off.webm" width="288" height="618" autoplay loop muted playsinline preload="metadata" aria-label="No animation"></video>
== Normal
<video src="/docs/guides/reader-settings/animation-speed_normal.webm" width="288" height="618" autoplay loop muted playsinline preload="metadata" aria-label="Normal"></video>
== Fast
<video src="/docs/guides/reader-settings/animation-speed_fast.webm" width="288" height="618" autoplay loop muted playsinline preload="metadata" aria-label="Fast"></video>
:::

#### Show reading mode <Badge type="info" text="On" />
Briefly show the current reading mode when the reader is opened.

#### Show tap zones overlay <Badge type="info" text="Off" />
Briefly shows an overlay for the current tap zones when reader is opened.

#### Animate page transitions <Badge type="info" text="On" />
This setting applies a smooth transition when tapping to change page.

### Display

#### Default rotation <Badge type="info" text="Free" />

This allows you to control how the screen is going to be oriented.

::: tabs
== Free
TBA
== Portrait
TBA
== Landscape
TBA
== Locked portrait
TBA
== Locked landscape
TBA
== Reverse portrait
TBA
:::

#### Background color <Badge type="info" text="Black" />

::: tabs
== Black
<img src="/docs/guides/reader-settings/background-color_black.webp" alt="Black" width="512" height="788" loading="lazy" decoding="async" />
== Gray
<img src="/docs/guides/reader-settings/background-color_gray.webp" alt="Gray" width="512" height="788" loading="lazy" decoding="async" />
== White
<img src="/docs/guides/reader-settings/background-color_white.webp" alt="White" width="512" height="788" loading="lazy" decoding="async" />
== Auto
Automatically sets the color based on the content of your page.
:::

#### Fullscreen <Badge type="info" text="On" />
Allows app elements to extend to the edges of the screen, including the status and navigation bars.

#### Show content in cutout area <Badge type="info" text="On" />
Displays reader content in the camera cutout area, maximizing the use of the entire screen. Only available on a device with a cutout, while **Fullscreen** is on.

#### Keep screen on <Badge type="info" text="Off" />
Keeps the screen from going to sleep.

#### Show page number <Badge type="info" text="On" />
Shows the current page number at the bottom of the screen.

#### Chapter title <Badge type="info" text="Name" />
What the reader's bar calls the open chapter: its **Name**, its **Number**, or **Number and name**. The novel reader has its own copy of this setting.

### E-Ink
#### Flash on page change <Badge type="info" text="Off" />
Flashes the screen on page change to reduce ghosting on E-ink displays. The three settings below only appear once this is on.

#### Flash duration <Badge type="info" text="100 ms" />
How long each flash lasts.

#### Flash every <Badge type="info" text="1 page" />
How many pages pass between flashes. Higher is less distracting and lets more ghosting build up.

#### Flash with <Badge type="info" text="Black" />
Whether the flash is black, white, or white then black.

### Reading

#### Skip chapters marked read <Badge type="info" text="Off" />
Skips over already read chapters while reading.

#### Skip filtered chapters <Badge type="info" text="On" />
Skips over filtered chapters while reading.

#### Skip duplicate chapters <Badge type="info" text="Off" />
Skips over chapters detected as duplicates. With **Downloaded only** on, the copy on your device is the one kept.

#### Mark chapter read when skipping ahead <Badge type="info" text="Off" />
When you jump to the next chapter, marks the one you skipped as read.

#### Start auto-scroll when opening a chapter <Badge type="info" text="Off" />
Starts auto-scroll each time you open the reader. **Auto-scroll** in the reader's Controls tab starts or stops it until you leave the reader, without changing this setting, and so does its bottom bar button once you add it under **Bottom bar buttons**. It pauses while the menu is open and while your finger is on the screen.

#### Page turn interval <Badge type="info" text="5 s" />
Paged modes only. How long a page stays on screen before auto-scroll turns it, counted from when the page has loaded, so it never turns past a page that is still loading.

#### Scroll speed <Badge type="info" text="1.0x" />
Long strip modes only. How fast auto-scroll moves the strip. It waits at a page that is still loading and scrolls on past one that failed, so its Retry button comes into view.

#### Auto webtoon mode <Badge type="info" text="On" />
Opens manhwa, manhua and webtoons in **Long strip** without you setting it per series.

It goes by what the source says, not by the pictures: a "Manhwa", "Manhua", "Webtoon" or "Long strip" genre tag, or a source name that gives it away. A "Manga" genre tag rules it out, and a "Comic" tag or comic source rules out a Manhwa or Manhua guess. Genres you changed with **Edit info** count instead of the source's, and in a merged series one source saying it is enough. A mode you picked for a series always wins.

#### Bottom bar buttons
Which buttons sit in the reader's bottom bar, and in what order. Rotation, reading mode, view chapters and crop borders are on by default. The settings gear can be moved like the others but not switched off.

#### Resume reading position <Badge type="info" text="Off" />
Reopens a chapter where you left it, even one already marked read.

#### Pages to preload <Badge type="info" text="4 pages" />
How far ahead pages are fetched. Higher is smoother and costs more data.

#### Always show chapter transition <Badge type="info" text="On" />
Shows chapter transitions regardless of whether the next chapter is loaded or not.

### Paged

#### Tap zones <Badge type="info" text="Default" /> {#tap-zones-pages}

::: tabs
== Default
Right and Left in **Paged (left to right)** and **Paged (right to left)**. L shaped in **Paged (vertical)** and the long strip modes.
<img src="/docs/guides/reader-settings/tap-zones_right-and-left.webp" alt="Right and Left" width="247" height="600" loading="lazy" decoding="async" />
== L shaped
<img src="/docs/guides/reader-settings/tap-zones_l-shaped.webp" alt="L shaped" width="247" height="600" loading="lazy" decoding="async" />
== Kindle-ish
<img src="/docs/guides/reader-settings/tap-zones_kindle-ish.webp" alt="Kindle-ish" width="247" height="600" loading="lazy" decoding="async" />
== Edge
<img src="/docs/guides/reader-settings/tap-zones_edge.webp" alt="Edge" width="247" height="600" loading="lazy" decoding="async" />
== Right and Left
<img src="/docs/guides/reader-settings/tap-zones_right-and-left.webp" alt="Right and Left" width="247" height="600" loading="lazy" decoding="async" />
== Disabled
No tap zones to assist with navigation will be active.
:::

#### Invert tap zones <Badge type="info" text="None" /> {#invert-tap-zones-pages}

::: tabs
== None
Keeps the default tap zones.
== Horizontal
Changes so that the tap zones are flipped horizontally.
== Vertical
Changes so that the tap zones are flipped vertically.
== Both
Changes so that the tap zones are flipped horizontally and vertically.
:::

#### Crop borders <Badge type="info" text="Off" /> {#crop-borders-pages}

:::tabs
== Off
<img src="/docs/guides/reader-settings/crop-borders_off.webp" alt="Not cropping borders" width="512" height="788" loading="lazy" decoding="async" />
== On
<img src="/docs/guides/reader-settings/crop-borders_on.webp" alt="Cropping borders" width="512" height="788" loading="lazy" decoding="async" />
:::

#### Split wide pages <Badge type="info" text="Off" /> {#split-wide-pages}
TBA

#### Invert split page placement <Badge type="info" text="Off" /> {#invert-split-page-placement}
Swaps which half of a split double-page spread comes first. Only useful once **Split wide pages** is on.

#### Rotate wide pages to fit <Badge type="info" text="Off" /> {#rotate-wide-pages}
TBA

#### Flip orientation of rotated wide pages <Badge type="info" text="Off" /> {#flip-rotated-wide-pages}
Rotates those pages the other way round. Only useful once **Rotate wide pages to fit** is on.

#### Scale type <Badge type="info" text="Fit screen" />

::: tabs
== Fit screen
<img src="/docs/guides/reader-settings/scale-type_fit-screen.webp" alt="Fit screen" width="512" height="788" loading="lazy" decoding="async" />
== Stretch
<img src="/docs/guides/reader-settings/scale-type_stretch.webp" alt="Stretch" width="512" height="788" loading="lazy" decoding="async" />
== Fit width
<img src="/docs/guides/reader-settings/scale-type_fit-width.webp" alt="Fit width" width="512" height="788" loading="lazy" decoding="async" />
== Fit height
<img src="/docs/guides/reader-settings/scale-type_fit-height.webp" alt="Fit height" width="512" height="788" loading="lazy" decoding="async" />
== Original size
<img src="/docs/guides/reader-settings/scale-type_original-size.webp" alt="Original size" width="512" height="788" loading="lazy" decoding="async" />
== Smart fit
<img src="/docs/guides/reader-settings/scale-type_smart-fit.webp" alt="Smart fit" width="512" height="788" loading="lazy" decoding="async" />
:::

#### Zoom start position <Badge type="info" text="Automatic" />

::: tabs
== Automatic
Starts at the left of the page in **Paged (left to right)**, at the right in **Paged (right to left)**, and in the center in **Paged (vertical)**. With the **High quality renderer** on, **Paged (vertical)** starts at the left instead.
== Left
<img src="/docs/guides/reader-settings/zoom-start-position_left.webp" alt="Left" width="512" height="788" loading="lazy" decoding="async" />
== Right
<img src="/docs/guides/reader-settings/zoom-start-position_right.webp" alt="Right" width="512" height="788" loading="lazy" decoding="async" />
== Center
<img src="/docs/guides/reader-settings/zoom-start-position_center.webp" alt="Center" width="512" height="788" loading="lazy" decoding="async" />
:::

#### Automatically zoom into wide images <Badge type="info" text="On" />
TBA

#### Pan wide images <Badge type="info" text="On" />
TBA

### Long strip

**Tap zones**, **Invert tap zones**, **Crop borders**, **Split wide pages**, **Invert split page placement**, **Rotate wide pages to fit** and **Flip orientation of rotated wide pages** appear here as well as under **Paged**, and each mode keeps its own answer. They work as described above.

#### Side padding <Badge type="info" text="0%" />
Adds the specified padding to the left and right of the screen. Shown while **Use high quality renderer** in <nav to="advanced"> is off.

#### Min width <Badge type="info" text="100%" />
How wide the strip is drawn, as a share of the screen. Shown in place of **Side padding** while **Use high quality renderer** is on.

#### Sensitivity for hiding menu on scroll <Badge type="info" text="Low" />
How quickly you have to scroll a long strip before the menu hides: **Highest** hides it on the slightest scroll, **Lowest** only on a fast one. A change applies the next time the reader opens. It has no effect while **Use high quality renderer** in <nav to="advanced"> is on.

#### Double tap to zoom <Badge type="info" text="On" />
Zooms into the image on double tap.

#### Disable zoom out <Badge type="info" text="Off" />
Stops a pinch from zooming a long strip out smaller than its normal width.

### High quality renderer

**Use high quality renderer** in <nav to="advanced"> <Badge type="info" text="Off" /> draws every reading mode with a newer renderer. While it is on, the reader's settings sheet adds a few options of its own, which are not on the settings screen.

#### Dual page view <Badge type="info" text="Never" />
On the **Reading** tab, for **Paged (left to right)** and **Paged (right to left)** only. **Always** shows two pages side by side, and **When wide** does so only while the screen is wider than it is tall.

#### Gap <Badge type="info" text="10%" />
On the **Reading** tab, for **Long strip with gaps** only. The space between pages, as a share of the screen.

#### Transition animation <Badge type="info" text="Basic" />
On the **Appearance** tab, paged modes only. How the page turns: **Basic**, **Page flip** (also to the left or right), **Stack** in four directions, **Sphere**, **Cube (Inside)**, **Cube (Outside)**, **Fade**, **Fade to white** or **None**. While two pages are shown, **Transition animation (dual)** takes over, with the same choices apart from the left and right page flips.

#### Display cutout mode <Badge type="info" text="Avoid" />
On the **Appearance** tab, paged modes only. How the page sits around the camera cutout: **Ignore** draws under it, while **Avoid** and **Shift** keep the page clear of it. While two pages are shown, **Display cutout mode (dual)** <Badge type="info" text="Ignore" /> takes over.

### Navigation

#### Volume keys <Badge type="info" text="Off" />
Enables page navigation with the volume keys.

#### Invert volume keys <Badge type="info" text="Off" />
Inverts what the volume keys do.

#### Volume key scroll amount <Badge type="info" text="75%" />
How much of the screen one press moves. It applies to the long strip modes only, since a paged reader turns a whole page either way. Shown while **Volume keys** is on and **Use high quality renderer** in <nav to="advanced"> is off.

#### Show chapter navigator <Badge type="info" text="On" />
Shows the slider for moving through the chapter. With it off, neither the slider nor the vertical navigator is drawn, and the previous and next chapter buttons move to the two ends of the button bar. The settings below it only appear while it is on.

#### Use vertical chapter navigator in <Badge type="info" text="None" />
Shows a vertical progress slider instead of the horizontal one, in the reading modes you pick. Nothing is picked by default, so the two settings below it stay hidden until you choose a mode here.

#### Place vertical navigator on the left side <Badge type="info" text="Off" />
Moves that slider to the left edge, for left-handed reading.

#### Vertical navigator height <Badge type="info" text="65" />
How tall the slider is, as a percentage of the screen.

### Actions

#### Show actions on long tap <Badge type="info" text="On" />
Shows the following options on long tap while the reader is open.

- Set as cover
- Copy to clipboard
- Share
- Save

#### Save pages into separate folders <Badge type="info" text="Off" />
Saves each page you save from the reader into its own folder named after the series, inside Pictures/Reikai, instead of putting every page directly in Pictures/Reikai.

## Novel reader

### Reading

#### Rendering mode <Badge type="info" text="Native text" />
How a chapter is drawn. **Native text** lays the text out in the app. **WebView** renders the chapter as a web page, which keeps more of its own formatting and unlocks the styling and snippet options under **Chapter text**. Changing it from the reader's settings sheet rebuilds the reader in place, at the same position.

#### Select text by long press <Badge type="info" text="Off" />
Lets you select and copy text. Under **Native text**, links stop responding to taps once this is on. Like **Rendering mode**, it takes effect at once.

#### Continuous chapters <Badge type="info" text="On" />
Scrolls straight on into the next chapter. Off makes each chapter its own page.

#### Always show chapter transition <Badge type="info" text="On" />
Shows the marker between two chapters every time, not only where chapters are missing. Only shown while **Continuous chapters** is on.

#### Add the next chapter at <Badge type="info" text="95%" />
How far into a chapter you have to read before the next one appears below it. A chapter shorter than the screen adds the next one straight away. Only shown while **Continuous chapters** is on.

#### Chapter title <Badge type="info" text="Name" />
What the reader's bar calls the open chapter: its **Name**, its **Number**, or **Number and name**.

#### Default rotation <Badge type="info" text="Free" />
How the screen is oriented. It offers the same choices as the manga reader.

#### Fullscreen <Badge type="info" text="On" />
Lets the page extend under the status and navigation bars.

#### Show content in cutout area <Badge type="info" text="On" />
Draws the page into the camera cutout. Only available on a device with a cutout, while **Fullscreen** is on.

#### Tap zones <Badge type="info" text="Disabled" />
How a tap on the page is read. **Disabled** shows or hides the menu wherever you tap.

**Top and bottom**, **L shaped**, **Kindle-ish**, **Edge** and **Right and Left** divide the page into zones that scroll back, scroll forward and open the menu. **Center**, **Large center** and **Bottom** draw only a menu zone, and a tap anywhere else is left to the page, so reading does not flash the menu.

#### Invert tap zones <Badge type="info" text="None" />
Flips the zones horizontally, vertically or both. Hidden for layouts it would not change: **Disabled**, **Center** and **Large center**. **Bottom** only flips vertically.

#### Bottom zone height <Badge type="info" text="12%" />
How tall the menu zone is. Only shown for the **Bottom** layout.

#### Show tap zones overlay <Badge type="info" text="Off" />
Briefly shows the tap zones when the reader opens. The overlay always shows when you change the layout; this only adds it on opening. Hidden while **Tap zones** is **Disabled**.

#### Swipe between chapters <Badge type="info" text="Off" />
Swipe sideways to move to the previous or next chapter.

#### Skip chapters marked read <Badge type="info" text="Off" />
Skips over already read chapters while reading.

#### Skip filtered chapters <Badge type="info" text="On" />
Skips over filtered chapters while reading.

#### Skip duplicate chapters <Badge type="info" text="Off" />
Skips over chapters detected as duplicates: a chapter number one source lists more than once. In a merged novel, chapters from different sources are never treated as duplicates. With **Downloaded only** on, the copy on your device is the one kept.

#### Mark chapter read at <Badge type="info" text="97%" />
How far into a chapter you have to get before it counts as read.

#### Mark chapter read when skipping ahead <Badge type="info" text="Off" />
When you jump to the next chapter, marks the one you skipped as read.

#### Start auto-scroll when opening a chapter <Badge type="info" text="Off" />
Starts scrolling the text on its own each time you open the reader. The **Auto-scroll** checkbox in the reader's Controls tab, and the **Auto-scroll** button once you add it under **Bottom bar buttons**, start or stop it until you leave the reader without changing this setting. It pauses while the menu is open, while your finger is on the screen and during read aloud.

#### Scroll speed <Badge type="info" text="1.0x" />
How fast auto-scroll moves the text.

#### Bottom bar buttons
Which buttons sit in the novel reader's bottom bar, and in what order. View chapters, rotation, text size, theme and read aloud are on by default. The settings gear can be moved like the others but not switched off.

#### Resume reading position <Badge type="info" text="Off" />
Reopens a chapter where you left it, even one already marked read.

### Text display

#### Font <Badge type="info" text="Default" />
Opens the font list, with **Built in** fonts and **Your fonts**, each drawn in its own face. Tap **Add font** to add one:

* **Import from device** takes a TTF or OTF file.
* **Download** searches the whole Google Fonts library. Type a name in **Font name** and tap a match, or tap **Download** to fetch the name as typed. The search needs a connection; a font you already have works offline.

A font you add is selected straight away. Fonts are kept in a `fonts` folder in your storage location, so they survive a reinstall. Remove one with the bin icon beside it under **Your fonts**; if it was in use, the reader goes back to **Default**. The **Font** row in the reader's settings sheet picks from the same list but cannot add fonts.

#### Line spacing <Badge type="info" text="1.5x" />
The space between lines, as a multiple of the text size.

#### Alignment <Badge type="info" text="Left" />
**Left**, **Center**, **Justify** or **Right**.

#### Top, bottom, left and right margins
The space around the text. The top margin starts at 50 dp and the others at 16 dp.

#### Paragraph indent <Badge type="info" text="0.0em" />
Indents the first line of every paragraph, as a multiple of your text size.

#### Paragraph spacing <Badge type="info" text="1.5em" />
The gap between paragraphs, as a multiple of your text size.

### Theme

Set in the reader itself: the **Appearance** tab of the settings sheet, or the theme button on the bottom bar.

**Follow system** <Badge type="info" text="On" /> uses the light swatch while your phone is in light mode and the grey one in dark mode. The six swatches pick a fixed theme instead: light, sepia, mint, grey, dark or black.

For colours of your own, use the **Background color** and **Text color** rows below the swatches. Each opens a colour picker, and changing one keeps the other as the page shows it now. There is one custom pair, not a list of saved themes; tap a swatch or **Follow system** to leave it.

### Chapter text

These change a chapter before it is shown. Several only exist for **WebView**, because the native renderer has no stylesheet or scripts to apply them to.

#### Hide repeated chapter title <Badge type="info" text="Off" />
Drops a heading at the top of a chapter that just repeats its name.

#### Force lowercase <Badge type="info" text="Off" />
Shows the whole chapter in lowercase.

#### Block images and video <Badge type="info" text="Off" />
Skips media a chapter embeds, for text-only reading or to save data.

#### Split walls of text <Badge type="info" text="Off" />
Adds paragraph breaks to chapters that arrive as one unbroken block. **Words before a split** <Badge type="info" text="50" /> appears below it once it is on.

#### Use the chapter's own styling <Badge type="info" text="On" />
Keeps a chapter's CSS instead of stripping it. **WebView** only.

#### Run scripts a chapter embeds <Badge type="info" text="Off" />
Usually leftover code from the source page rather than part of the chapter. **WebView** only.

#### Chapter styling wins <Badge type="info" text="Off" />
Lets the chapter's size, colour and spacing override yours. **WebView** only.

#### Use the chapter's own fonts <Badge type="info" text="Off" />
Keeps the typeface a chapter asks for. **WebView** only, and hidden while **Chapter styling wins** is on, since the chapter's styling already decides the font.

#### Show raw HTML <Badge type="info" text="Off" />
Shows a chapter's markup as text.

#### Find and replace
Rules that rewrite chapter text before it is shown.

#### CSS snippets and JavaScript snippets
Your own styles and code, added to every chapter page. **WebView** only. A restored backup brings JavaScript snippets back switched off.

### Navigation

#### Volume keys <Badge type="info" text="Off" />
Scrolls with the volume keys.

#### Invert volume keys <Badge type="info" text="Off" />
Inverts what the volume keys do.

#### Volume key scroll amount <Badge type="info" text="75%" />
How much of the screen one press moves.

#### Show chapter navigator <Badge type="info" text="On" />
Shows the progress slider or the vertical navigator. With it off, neither is drawn, the previous and next chapter buttons move to the two ends of the button bar, and the settings below it are hidden.

#### Vertical chapter navigator <Badge type="info" text="On" />
Shows a vertical progress slider at the side of the screen. The two settings below it only appear while it is on.

#### Place vertical navigator on the left side <Badge type="info" text="Off" />
Moves that slider to the left edge, for left-handed reading.

#### Vertical navigator height <Badge type="info" text="65" />
How tall the slider is, as a percentage of the screen.

#### Sensitivity for hiding menu on scroll <Badge type="info" text="Low" />
How far you have to scroll before the menu hides: **Highest** hides it after the smallest scroll, **Lowest** after the largest. A change applies the next time the reader opens.

### Read aloud

The **Read aloud** button on the bottom bar shows or hides the read-aloud controls, and a long press stops reading. The controls float above the bar and stay when the menu hides: **Read from here**, **Previous paragraph**, **Play** / **Pause**, **Next paragraph** and **Sleep timer**. The sleep timer stops reading after 15, 30, 45 or 60 minutes, or at the **End of chapter**, and the notification shows how long is left.

The settings below are in <nav to="novel-reader">.

#### Engine
Which text-to-speech engine reads the chapter. Only shown when more than one is installed.

#### Voice languages <Badge type="info" text="All" />
Narrows the voice list to the languages you pick. Only shown when the engine has voices in more than one language.

#### Voice <Badge type="info" text="Default" />
The voice the engine uses.

#### Speed <Badge type="info" text="1.0x" /> and Pitch <Badge type="info" text="1.0" />
How fast and how high the voice reads.

#### Continue to the next chapter <Badge type="info" text="Off" />
Keeps reading into the next chapter when one ends.

#### Keep paragraph in view <Badge type="info" text="On" />
Scrolls the paragraph being read back on screen when it moves off. **Scroll to top** <Badge type="info" text="On" /> below it puts that paragraph at the top rather than the middle.

#### Highlight paragraph <Badge type="info" text="On" />
Marks the paragraph being read. With it on, **Highlight style** <Badge type="info" text="Background" /> picks a background, an underline or an outline, **Highlight color** picks its colour, and **Text color** sets the text drawn over a background highlight.

#### Highlight sentence <Badge type="info" text="Off" />
Marks only the sentence being read instead of its whole paragraph, in the same style and colours. It sends the text to the voice one sentence at a time, so some engines pause slightly between sentences. Only shown while **Highlight paragraph** is on.

### Accessibility

#### Keep screen on <Badge type="info" text="Off" />
Keeps the screen from going to sleep while reading a novel.

#### Show reading progress <Badge type="info" text="On" />
Shows how far through the chapter you are.

#### Bionic reading <Badge type="info" text="Off" />
Bolds the first part of each word, which some readers find easier to track.

#### Remove extra spacing <Badge type="info" text="Off" />
Strips the blank space some sources leave between paragraphs.
