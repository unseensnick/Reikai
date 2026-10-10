---
title: Opening shared links
titleTemplate: Sources
description: Share a series or chapter link to Reikai from your browser and it opens the page or the chapter.
---

# Opening shared links

Found a series in your browser? Share the link to **Reikai** and it opens that series' page, manga or novel, so you can read it or add it to your library.

1. In your browser or any other app, open its share menu on the link.
1. Pick **Reikai**.

Reikai looks for a source that can open the link, trying manga sources before novel sources at each step:

1. The source's extension app: its own link handling where it has one, then a search on the linked site for the link. Manga extensions and Tsundoku's novel extensions do both; IReader extensions only use their link handling, and plugins skip this step.
1. A series you already have in Reikai with that address.
1. A careful guess from the address, checked against the source before it is used.

When a source is found, the series page opens. A chapter link opens that chapter in the reader instead, when the source's extension app can read chapter links.

If nothing matches, Reikai runs a global search across all your sources, manga and novels, with the shared text as the query. The same happens with any plain text you share, so sharing a title is a quick way to search for it.

The link's source has to be installed. A link to a site you have no source for ends in that global search.

There is nothing to turn on. Tapping a web link does not open Reikai by itself, because the app does not register to open most web addresses, so use the share menu. Two kinds of link are the exception and can open in Reikai when tapped:

- **MangaDex title and chapter links** open the series, or the chapter in the reader, and add the series to your library. This needs the MangaDex extension installed and [**Enable delegated sources**](built-in-sources.md) on in <nav to="browse"> (on by default). With it off, the extension takes the link and opens a MangaDex search for it instead. Without the extension, Reikai says it could not open the link.
- **Gallery links** from the sites the [gallery import](adult-sources.md#adding-many-galleries-at-once) accepts open in Reikai's import screen.

Android may ask which app to open such a link with, or keep opening it in your browser until you allow Reikai to open supported links in its app info (**Open by default**). Shared instead of tapped, these links go through the same lookup as any other link.
