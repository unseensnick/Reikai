---
title: Manga reader settings
titleTemplate: Reference
description: The settings in Settings -> Manga reader, with their defaults.
---

# Manga reader settings

The settings in <nav to="manga-reader">, with their defaults.
Light novels have their own screen, covered in [novel reader settings](/docs/guides/novel-reader-settings).
A setting that appears on both, such as **Keep screen on**, is stored separately for each.

Most of these can also be changed while reading: open a chapter, tap the middle of the screen, and press the gear icon.

## General

### Default reading mode <Badge type="info" text="Paged (right to left)" />

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

### Double tap animation speed <Badge type="info" text="Normal" />
Double tap animation speed changes the speed in which the zoom happens when double tapping.

::: tabs
== No animation
<video src="/docs/guides/reader-settings/animation-speed_off.webm" width="288" height="618" autoplay loop muted playsinline preload="metadata" aria-label="No animation"></video>
== Normal
<video src="/docs/guides/reader-settings/animation-speed_normal.webm" width="288" height="618" autoplay loop muted playsinline preload="metadata" aria-label="Normal"></video>
== Fast
<video src="/docs/guides/reader-settings/animation-speed_fast.webm" width="288" height="618" autoplay loop muted playsinline preload="metadata" aria-label="Fast"></video>
:::

### Show reading mode <Badge type="info" text="On" />
Briefly show the current reading mode when the reader is opened.

### Show tap zones overlay <Badge type="info" text="Off" />
Briefly shows an overlay for the current tap zones when reader is opened.

### Animate page transitions <Badge type="info" text="On" />
This setting applies a smooth transition when tapping to change page.

## Display

### Default rotation <Badge type="info" text="Free" />

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

### Background color <Badge type="info" text="Black" />

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

### Fullscreen <Badge type="info" text="On" />
Allows app elements to extend to the edges of the screen, including the status and navigation bars.

### Show content in cutout area <Badge type="info" text="On" />
Displays reader content in the camera cutout area, maximizing the use of the entire screen. Only available on a device with a cutout, while **Fullscreen** is on.

### Keep screen on <Badge type="info" text="Off" />
Keeps the screen from going to sleep.

### Show page number <Badge type="info" text="On" />
Shows the current page number at the bottom of the screen.

### Chapter title <Badge type="info" text="Name" />
What the reader's bar calls the open chapter: its **Name**, its **Number**, or **Number and name**. The novel reader has its own copy of this setting.

## E-Ink
### Flash on page change <Badge type="info" text="Off" />
Flashes the screen on page change to reduce ghosting on E-ink displays. The three settings below only appear once this is on.

### Flash duration <Badge type="info" text="100 ms" />
How long each flash lasts.

### Flash every <Badge type="info" text="1 page" />
How many pages pass between flashes. Higher is less distracting and lets more ghosting build up.

### Flash with <Badge type="info" text="Black" />
Whether the flash is black, white, or white then black.

## Reading

### Skip chapters marked read <Badge type="info" text="Off" />
Skips over already read chapters while reading.

### Skip filtered chapters <Badge type="info" text="On" />
Skips over filtered chapters while reading.

### Skip duplicate chapters <Badge type="info" text="Off" />
Skips over chapters detected as duplicates. With **Downloaded only** on, the copy on your device is the one kept.

### Mark chapter read when skipping ahead <Badge type="info" text="Off" />
When you jump to the next chapter, marks the one you skipped as read.

### Start auto-scroll when opening a chapter <Badge type="info" text="Off" />
Starts auto-scroll each time you open the reader. **Auto-scroll** in the reader's Controls tab starts or stops it until you leave the reader, without changing this setting, and so does its bottom bar button once you add it under **Bottom bar buttons**. It pauses while the menu is open and while your finger is on the screen.

### Page turn interval <Badge type="info" text="5 s" />
Paged modes only. How long a page stays on screen before auto-scroll turns it, counted from when the page has loaded, so it never turns past a page that is still loading.

### Scroll speed <Badge type="info" text="1.0x" />
Long strip modes only. How fast auto-scroll moves the strip. It waits at a page that is still loading and scrolls on past one that failed, so its Retry button comes into view.

### Auto webtoon mode <Badge type="info" text="On" />
Opens manhwa, manhua and webtoons in **Long strip** without you setting it per series.

It goes by what the source says, not by the pictures: a "Manhwa", "Manhua", "Webtoon" or "Long strip" genre tag, or a source name that gives it away. A "Manga" genre tag rules it out, and a "Comic" tag or comic source rules out a Manhwa or Manhua guess. Genres you changed with **Edit info** count instead of the source's, and in a merged series one source saying it is enough. A mode you picked for a series always wins.

### Bottom bar buttons
Which buttons sit in the reader's bottom bar, and in what order. Rotation, reading mode, view chapters and crop borders are on by default. The settings gear can be moved like the others but not switched off.

### Resume reading position <Badge type="info" text="Off" />
Reopens a chapter where you left it, even one already marked read.

### Pages to preload <Badge type="info" text="4 pages" />
How far ahead pages are fetched. Higher is smoother and costs more data.

### Always show chapter transition <Badge type="info" text="On" />
Shows chapter transitions regardless of whether the next chapter is loaded or not.

## Paged

### Tap zones <Badge type="info" text="Default" /> {#tap-zones-pages}

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

### Invert tap zones <Badge type="info" text="None" /> {#invert-tap-zones-pages}

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

### Crop borders <Badge type="info" text="Off" /> {#crop-borders-pages}

:::tabs
== Off
<img src="/docs/guides/reader-settings/crop-borders_off.webp" alt="Not cropping borders" width="512" height="788" loading="lazy" decoding="async" />
== On
<img src="/docs/guides/reader-settings/crop-borders_on.webp" alt="Cropping borders" width="512" height="788" loading="lazy" decoding="async" />
:::

### Split wide pages <Badge type="info" text="Off" /> {#split-wide-pages}
TBA

### Invert split page placement <Badge type="info" text="Off" /> {#invert-split-page-placement}
Swaps which half of a split double-page spread comes first. Only useful once **Split wide pages** is on.

### Rotate wide pages to fit <Badge type="info" text="Off" /> {#rotate-wide-pages}
TBA

### Flip orientation of rotated wide pages <Badge type="info" text="Off" /> {#flip-rotated-wide-pages}
Rotates those pages the other way round. Only useful once **Rotate wide pages to fit** is on.

### Scale type <Badge type="info" text="Fit screen" />

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

### Zoom start position <Badge type="info" text="Automatic" />

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

### Automatically zoom into wide images <Badge type="info" text="On" />
TBA

### Pan wide images <Badge type="info" text="On" />
TBA

## Long strip

**Tap zones**, **Invert tap zones**, **Crop borders**, **Split wide pages**, **Invert split page placement**, **Rotate wide pages to fit** and **Flip orientation of rotated wide pages** appear here as well as under **Paged**, and each mode keeps its own answer. They work as described above.

### Side padding <Badge type="info" text="0%" />
Adds the specified padding to the left and right of the screen. Shown while **Use high quality renderer** in <nav to="advanced"> is off.

### Min width <Badge type="info" text="100%" />
How wide the strip is drawn, as a share of the screen. Shown in place of **Side padding** while **Use high quality renderer** is on.

### Sensitivity for hiding menu on scroll <Badge type="info" text="Low" />
How quickly you have to scroll a long strip before the menu hides: **Highest** hides it on the slightest scroll, **Lowest** only on a fast one. A change applies the next time the reader opens. It has no effect while **Use high quality renderer** in <nav to="advanced"> is on.

### Double tap to zoom <Badge type="info" text="On" />
Zooms into the image on double tap.

### Disable zoom out <Badge type="info" text="Off" />
Stops a pinch from zooming a long strip out smaller than its normal width.

## High quality renderer

**Use high quality renderer** in <nav to="advanced"> <Badge type="info" text="Off" /> draws every reading mode with a newer renderer. While it is on, the reader's settings sheet adds a few options of its own, which are not on the settings screen.

### Dual page view <Badge type="info" text="Never" />
On the **Reading** tab, for **Paged (left to right)** and **Paged (right to left)** only. **Always** shows two pages side by side, and **When wide** does so only while the screen is wider than it is tall.

### Gap <Badge type="info" text="10%" />
On the **Reading** tab, for **Long strip with gaps** only. The space between pages, as a share of the screen.

### Transition animation <Badge type="info" text="Basic" />
On the **Appearance** tab, paged modes only. How the page turns: **Basic**, **Page flip** (also to the left or right), **Stack** in four directions, **Sphere**, **Cube (Inside)**, **Cube (Outside)**, **Fade**, **Fade to white** or **None**. While two pages are shown, **Transition animation (dual)** takes over, with the same choices apart from the left and right page flips.

### Display cutout mode <Badge type="info" text="Avoid" />
On the **Appearance** tab, paged modes only. How the page sits around the camera cutout: **Ignore** draws under it, while **Avoid** and **Shift** keep the page clear of it. While two pages are shown, **Display cutout mode (dual)** <Badge type="info" text="Ignore" /> takes over.

## Navigation

### Volume keys <Badge type="info" text="Off" />
Enables page navigation with the volume keys.

### Invert volume keys <Badge type="info" text="Off" />
Inverts what the volume keys do.

### Volume key scroll amount <Badge type="info" text="75%" />
How much of the screen one press moves. It applies to the long strip modes only, since a paged reader turns a whole page either way. Shown while **Volume keys** is on and **Use high quality renderer** in <nav to="advanced"> is off.

### Show chapter navigator <Badge type="info" text="On" />
Shows the slider for moving through the chapter. With it off, neither the slider nor the vertical navigator is drawn, and the previous and next chapter buttons move to the two ends of the button bar. The settings below it only appear while it is on.

### Use vertical chapter navigator in <Badge type="info" text="None" />
Shows a vertical progress slider instead of the horizontal one, in the reading modes you pick. Nothing is picked by default, so the two settings below it stay hidden until you choose a mode here.

### Place vertical navigator on the left side <Badge type="info" text="Off" />
Moves that slider to the left edge, for left-handed reading.

### Vertical navigator height <Badge type="info" text="65" />
How tall the slider is, as a percentage of the screen.

## Actions

### Show actions on long tap <Badge type="info" text="On" />
Shows the following options on long tap while the reader is open.

- Set as cover
- Copy to clipboard
- Share
- Save

### Save pages into separate folders <Badge type="info" text="Off" />
Saves each page you save from the reader into its own folder named after the series, inside Pictures/Reikai, instead of putting every page directly in Pictures/Reikai.
