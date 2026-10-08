---
title: Opening shared links
titleTemplate: Guides
description: Share a series or chapter link to Reikai from your browser and it opens the page or the chapter.
---

# Opening shared links

Found a series in your browser? Share the link to **Reikai** and it opens that series' page, manga or novel, so you can read it or add it to your library.

1. In your browser or any other app, open its share menu on the link.
1. Pick **Reikai**.

Reikai looks for a source that can open the link, trying manga sources before novel sources at each step:

1. The source's own link handling, where its extension or plugin has one.
1. For manga, a search on the linked site for the link.
1. A series you already have in Reikai with that address.
1. A careful guess from the address, checked against the source before it is used.

When a source is found, the series page opens. A chapter link opens that chapter in the reader instead: for manga, when the extension can read chapter links; for a novel, when the chapter is already in Reikai.

If nothing matches, Reikai runs a global search across all your sources, manga and novels, with the shared text as the query. The same happens with any plain text you share, so sharing a title is a quick way to search for it.

The link's source has to be installed and enabled. A link to a site you have no source for ends in that global search.

There is nothing to turn on. Tapping a web link does not open Reikai by itself, because the app does not register to open web addresses, so use the share menu. The one exception is gallery links for the built-in [adult sources](adult-sources.md), which can open in Reikai's import screen when tapped or shared.
