# Library search

_Dev records: [library-all-chip.md](dev/plans/library-all-chip.md), [library-tag-search.md](dev/plans/library-tag-search.md). Doc map: [README.md](README.md)._

*Library → the search icon.*

Searching your library does more than match titles. You can search a specific field, compare numbers and dates, combine terms, and exclude things. The same grammar works for manga and light novels, so a query means the same thing whichever chip you are on, including **All**.

Everything is case-insensitive.

## Plain words

A word with no field name searches the **title, author, artist, description, genre, source name and notes**. Two words means both must match:

| Query | Finds |
|---|---|
| `dungeon` | anything with "dungeon" in any of those fields |
| `dungeon reincarnation` | entries matching both words |
| `"slime datta ken"` | the exact phrase, thanks to the quotes |

Commas work as separators too, so `dungeon, reincarnation` is the same as writing them with a space.

## Searching one field

Put the field name and a colon in front of the term. This narrows the search to that field only.

| Field | Aliases | Example |
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

Three of these are only reachable by typing the field name, because a plain word never searches them: `language:`, `srcid:` and `chapter:`.

**`source:` matches the source's display name**, so `source:mangadex` and `src:manga` both work by partial name. **`srcid:` matches the source's exact identity** instead, which is a number for manga sources and a plugin name for novel sources. Use `srcid:` when two sources have similar names. On manga, `source:local` finds entries from your local source.

**`chapter:` searches chapter names**, so `chapter:epilogue` finds every entry that has a chapter called that. It is the one field that looks beyond the entry itself, which is why an ordinary word search never includes chapter names. One caveat: its case-insensitivity covers plain letters only, so accented characters must match the chapter's own casing (`ch:épilogue` does not find "Épilogue").

An empty quoted value finds entries where a field is missing: `genre:""` finds entries with no genres at all. (A bare `genre:` with nothing after it reads as ordinary text, not an empty field.)

## Comparing numbers and dates

| Field | Aliases | Meaning |
|---|---|---|
| `unread` | | unread chapter count |
| `read` | | read chapter count |
| `total` | | total chapter count |
| `id` | | the entry's internal id |
| `added` | | the date you added it |
| `nextupdate` | `nu` | when it is next due to be checked |
| `fetchinterval` | `fi` | how often it is checked, in days |

Use `>`, `<`, `>=`, `<=` or `=`:

| Query | Finds |
|---|---|
| `unread>5` | more than five unread chapters |
| `unread=0` | nothing left to read |
| `total<20` | short series |
| `added>2026-01-01` | added this year |
| `id=1425` | that exact entry (on the All chip, a manga and a novel can share a number, so it can show one of each) |

Dates are written year-month-day, like `2026-08-01`.

### Two of these do not apply to light novels

`nextupdate` and `fetchinterval` are manga-only, because novels have no update schedule. A query using either **never matches a novel**, not even when you negate it: both `nu<2026-12-01` and `-nu<2026-12-01` return manga only. This is deliberate, so that a comparison a novel cannot answer never quietly pads your results on the All chip.

## Excluding, combining, grouping

| Query | Finds |
|---|---|
| `-horror` | everything except horror |
| `-genre:ecchi` | everything without that genre |
| `genre:action genre:comedy` | both genres |
| `genre:action \|\| genre:comedy` | either genre |
| `(genre:action \|\| genre:comedy) -horror` | either genre, and not a horror entry |
| `chapter:finale -genre:horror` | has a chapter called finale, is not horror |

Terms sit next to each other for "and", `||` means "or", `-` in front of a term excludes it, and parentheses group. Writing `&&` for "and" is allowed but never necessary.

## Adult and gallery sources

Entries from gallery sources also answer the namespace tag grammar those sources use, for example `artist:toyya` or `female:glasses`, on top of everything above. An entry shows up if either grammar matches it. See [adult-sources.md](adult-sources.md).

## If something does not match

- **A misspelled field name is searched as plain text.** `titel:solo` looks for the literal text "titel:solo" rather than warning you, and finds nothing.
- **Search uses the details you set, not the source's.** If you renamed an entry through Edit info, search finds it under your name and no longer under the source's. The same applies to an author, artist, description or genre you overrode. Sorting and grouping still use the source values.
- **`chapter:` only searches chapters already saved on your device**, which for most entries means everything fetched so far, not the source's full catalogue.
- **A grouped entry is matched as one.** Merged sources are searched through the entry you see, not each source behind it.
