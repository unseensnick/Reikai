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

There is nothing to turn on. Tapping a web link does not open Reikai by itself, because the app does not register to open web addresses, so use the share menu. The one exception is gallery links from the sites the [gallery import](adult-sources.md#adding-many-galleries-at-once) accepts, which can open in Reikai's import screen when tapped. Shared, they go through the same lookup as any other link.
