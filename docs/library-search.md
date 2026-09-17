---
title: Library search
titleTemplate: Guides
description: Find entries in your library by title, author, genre or source, and search gallery sources by their tags.
---

# Library search

_Dev record: [library-tag-search.md](dev/plans/library-tag-search.md). Doc map: [README.md](README.md)._

To search, tap the search icon in <nav to="main_library">. Search is case-insensitive.

## Manga

Your text finds an entry whose title, author, artist or description contains it.

It also finds an entry when every comma-separated term matches it: part of the source's name, or the whole name of one of its genres. Start a term with `-` to exclude entries it matches, so `action, -horror` finds action entries that are not horror.

| Query | Finds |
|---|---|
| `id:1425` | the entry with that exact id |
| `src:<source id>` | entries from the source with that number |
| `src:local` | entries from the local source |

## Light novels

Your text finds a novel whose title, author, artist or one of its genres contains it.

## Gallery sources

Entries from gallery sources, such as the [adult sources](adult-sources.md) and MangaDex, use the tag grammar those sites use instead:

- `artist:toyya` or `female:glasses` finds a tag in that namespace. Short forms work too: `a:` artist, `f:` female, `m:` male, `g:` group, `p:` parody, `c:` character, `l:` language.
- Plain words match the title, author, artist, description, source name, genres, tags and alternative titles.
- Every term has to match. A leading `-` excludes a term, quotes keep a phrase together, `$` asks for an exact tag, and `*` or `?` are wildcards.

Search uses each entry's own details from its source, not ones you changed with **Edit info**.
