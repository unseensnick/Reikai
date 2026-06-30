# Library tag-search engine

## Goal

Let the library search bar understand a structured tag query language for adult-source entries:
`namespace:tag` (with short aliases), `*` / `?` wildcards, `-` exclusion, quoted phrases, and
`$` exact, matched against each gallery's captured metadata. Plain title search and non-adult
entries are unchanged.

## Why

Reikai already indexes every gallery's tags and titles (`search_tags` / `search_titles`) and the
library already matched a tag by plain substring. The browse-side `namespace:tag` autocomplete was
ported, but the **library** half of Komikku's query language (namespaces, wildcards, exclusion,
exact, aliases) was not, so you could not narrow a large adult library to, say, one artist or
parody. This closes that parity gap.

## Approach

In plain English: when you type in the library search box, we parse the text once into structured
"components" (a plain word, or a `namespace:tag`), then for each adult-source entry we check every
component against that entry's indexed tags, alt-titles, and the usual text fields. Every component
must match (implicit AND); a `-` component must not match. Ordinary manga keep the old plain-text
matching untouched.

Mechanism:
- A trimmed port of Komikku's `exh/search` package: the `parseQuery` parser plus the component
  types (`Namespace`, `Text`, `QueryComponent`, `StringTextComponent`, `SingleWildcard`,
  `MultiWildcard`). Komikku's SQL-emitting `queryToSql` path is a browse-side concern and was not
  ported; the library matches in memory.
- Wildcards actually work here. Komikku's library matcher passes its SQL-`LIKE` pattern to
  `String.contains`, so `*` / `?` degrade to literal characters. Reikai adds `Text.asRegex()`,
  which builds a case-insensitive regex from the components (`*` -> `.*`, `?` -> `.`, literals
  regex-escaped, `$`-exact anchored), so `parody:*hero*` matches as intended.
- The engine only runs for entries whose source is a `MetadataSource` (the
  `getMainSource<MetadataSource<*, *>>()` gate), so a normal title containing `:` is never
  misread as a namespace. Everything else falls through to the existing plain-string `matches`.
- Tags and titles are batch-loaded once per library build (one query each) onto `LibraryItem`,
  and the query is parsed once per search (cached), not per entry.

## Key files

- `app/src/main/java/exh/search/` (net-new): `SearchEngine.parseQuery`, `Text.asRegex`, and the
  component types.
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryItem.kt`: `matches(constraint,
  parsedQuery, sourceManager)` gains the metadata-source engine branch (`matchesComponent`).
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt`: parses once and passes
  the components in; batch-loads `searchTitles` alongside `searchTags`.
- `data/.../search_titles.sq` + `GetSearchTitles.awaitAll()` + `MangaMetadataRepository.getAllTitles()`:
  a `selectAll` query so titles batch-load like tags (no migration, query-only change).
- Tests: `app/src/test/java/exh/search/SearchEngineTest.kt`.

## Status

Shipped on `feat/exh-parity`. Engine unit-tested (parser + wildcard/exact/exclusion regex) and
on-device verified: `artist:alp`, the alias `a:alp`, and the wildcard `artist:al*` each narrow the
library to the one matching gallery; a non-existent tag empties it; plain title search still finds
ordinary manga with the gallery hidden.

## Decisions & tradeoffs

- In-memory, not a DB query: matches our base and Komikku's library path, and the library is
  already filtered in memory. The `search_tags(namespace, name)` index would only matter for a
  DB-side approach, which is unneeded at library sizes.
- Wildcards made to actually work (regex), a deliberate improvement over Komikku's inert library
  wildcards, since the feature was explicitly wanted.
- `flushAll` resets the exclude/exact flags between components, fixing a latent Komikku carry-over
  where `-a b` would exclude both terms.
- No library-search autocomplete (the `namespace:tag` suggestion UI stays browse-filter only); a
  larger UI task, deliberately out of scope.
- Also matches a gallery's indexed alt-titles, so a term can hit a Japanese / short title variant,
  not only the displayed one.
- This is the library half of the [adult-source (EXH) subsystem](exh-subsystem.md).
