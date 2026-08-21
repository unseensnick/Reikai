---
title: Library search
titleTemplate: Guides
description: Search your library by field, compare numbers and dates, and combine or exclude terms.
---

# Library search

_Dev records: [library-all-chip.md](dev/plans/library-all-chip.md), [library-tag-search.md](dev/plans/library-tag-search.md). Doc map: [README.md](README.md)._

Searching your library does more than match titles.
You can search one field, compare numbers and dates, combine terms and exclude things.

To search, tap the search icon in <nav to="main_library">.

The same grammar works for manga and for light novels, so a query means the same thing whichever library you are on, including **All**.
Everything is case-insensitive.

## Plain words

A word with no field name searches the title, author, artist, description, genre, source name and notes.
Two words means both have to match.

| Query | Finds |
|---|---|
| `dungeon` | anything with "dungeon" in any of those fields |
| `dungeon reincarnation` | entries matching both words |
| `"slime datta ken"` | the exact phrase, thanks to the quotes |

Commas separate terms too, so `dungeon, reincarnation` reads the same as writing them with a space.

## Searching one field

Put the field name and a colon in front of the term to search only that field.

| Field | Also written as | Example |
|---|---|---|
| `title:` | | `title:solo` |
| `author:` | | `author:kubo` |
| `artist:` | | `artist:murata` |
| `description:` | `desc:` | `desc:academy` |
| `genre:` | `tag:` | `genre:horror` |
| `source:` | `src:` | `source:mangadex` |
| `notes:` | `note:` | `notes:reread` |
| `language:` | `lang:` | `lang:en` |
| `srcid:` | `source_id:`, `sourceid:`, `src_id:` | `srcid:novelarrow` |
| `chapter:` | `ch:` | `chapter:epilogue` |

::: info Three fields need their name typed
A plain word never searches `language:`, `srcid:` or `chapter:`, so those only answer when you name them.
:::

**`source:` matches the source's display name**, so `source:mangadex` and `src:manga` both work on a partial name.
**`srcid:` matches its exact identity** instead, a number for manga sources and a plugin name for novel sources.
Reach for `srcid:` when two sources have similar names.
On manga, `source:local` finds entries from your local source.

**`chapter:` searches chapter names**, so `chapter:epilogue` finds every entry with a chapter called that.
It is the one field that looks past the entry itself, which is why a plain word never includes chapter names.

An empty quoted value finds entries where a field is missing: `genre:""` finds entries with no genres at all.
A bare `genre:` with nothing after it reads as ordinary text instead.

::: warning `chapter:` is not accent-insensitive
Its case-insensitivity covers plain letters only, so an accented character has to match the chapter's own casing.
`ch:épilogue` does not find "Épilogue".
:::

## Comparing numbers and dates

| Field | Also written as | Meaning |
|---|---|---|
| `unread` | | unread chapter count |
| `read` | | read chapter count |
| `total` | | total chapter count |
| `id` | | the entry's internal id |
| `added` | | the date you added it |
| `nextupdate` | `nu` | when it is next due to be checked |
| `fetchinterval` | `fi` | how often it is checked, in days |

Compare with `>`, `<`, `>=`, `<=` or `=`.
Dates are written year-month-day, like `2026-08-01`.

| Query | Finds |
|---|---|
| `unread>5` | more than five unread chapters |
| `unread=0` | nothing left to read |
| `total<20` | short series |
| `added>2026-01-01` | added this year |
| `id=1425` | that exact entry, though on **All** a manga and a novel can share a number, so you may get one of each |

::: warning Two of these skip light novels entirely
`nextupdate` and `fetchinterval` are manga-only, because novels have no update schedule.
A query using either **never matches a novel**, not even negated: `nu<2026-12-01` and `-nu<2026-12-01` both return manga only.
That is deliberate, so a comparison a novel cannot answer never quietly pads your results on **All**.
:::

## Excluding and combining

Terms sitting next to each other mean "and", `||` means "or", a leading `-` excludes, and parentheses group.
Writing `&&` for "and" is allowed but never needed.

| Query | Finds |
|---|---|
| `-horror` | everything except horror |
| `-genre:ecchi` | everything without that genre |
| `genre:action genre:comedy` | both genres |
| `genre:action \|\| genre:comedy` | either genre |
| `(genre:action \|\| genre:comedy) -horror` | either genre, and not horror |
| `chapter:finale -genre:horror` | has a chapter called finale, is not horror |

## Gallery sources

Entries from gallery sources also answer the namespace tag grammar those sources use, such as `artist:toyya` or `female:glasses`, on top of everything above.
An entry matches if either grammar matches it.
See [adult sources](adult-sources.md).

## If something does not match

- **A misspelled field name is searched as plain text.** `titel:solo` looks for the literal text rather than warning you, and finds nothing.
- **Search uses the details you set, not the source's.** Rename an entry through **Edit info** and search finds it under your name, no longer the source's. The same goes for an author, artist, description or genre you overrode. Sorting and grouping still use the source's values.
- **`chapter:` only searches chapters already saved on your device**, which for most entries means everything fetched so far rather than the source's full catalogue.
- **A grouped entry is matched as one.** [Merged sources](multi-source.md) are searched through the entry you see, not each source behind it.
