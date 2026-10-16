# Statistics

## Purpose

Statistics summarises the library and the reader's reading for manga and novels together, behind the same All / Manga / Novels switch: counts, charts of status, tracking status and scores, reading time per day with streaks, and lists of the library by one grouping at a time whose rows open those series in the library.

## How it works

### Loading

`StatsLibraryLoader.load` reads both content types once per screen into a `StatsLibrary`: each type's library as a `StatsSeries` (a merged series is one title, through the merge manager's buckets and the stored stitch counts), every member's tracks on the signed-in trackers, the reading log's sessions, each entry's lifetime reading time from history, and the tracker and category names. Each type answers the per-entry fields through `StatsTitleFields`, the shape `libraryItemQueryFields` uses, and `StatsSeries.statsTitles` builds one neutral `StatsTitle` per series. Everything after that point is written once for both types.

`StatsViewModel` (Mihon's, patched) keeps the four overview cards' numbers and adds `StatsCharts`; `StatsGroupViewModel` (one grouping's list, assisted on the `StatsDimension`) and `StatsReadingViewModel` (the Reading time screen) call the same loader. All three read `statsContentType`, so the switch set on the overview applies everywhere.

### Groupings

`groupsBy` (`StatsKernels.kt`) groups titles by a `StatsDimension` into `StatsGroup`s keyed by a typed `StatsGroupKey`, with count, chapters read and total, mean score and reading time. A title sits in one row per value it has, so source, tag, tracker and language rows can sum past the library. Tags group ignoring case, under the first spelling seen. `StatsLibrary.rows` names each key, orders the rows (score, length and year keep their own order; the rest follow `StatsSort`) and attaches the library search from `StatsGroupKey.libraryQuery`.

### Library searches

A row opens the library through `searchLibraryFromDetails` with a query the shared search kernel answers: `status:`, `tracker:""`, `category:"Name"`, a `score` range matching the bar `scoreBar` rounds into, `total` bounds, `srcid:` and so on (see [library.md](library.md), Search). Start-year rows have no search and do not open.

### Reading log

History keeps one row per chapter whose last read is overwritten, so it cannot place time on a day. The reading log (`reading_session` and `novel_reading_session`, `ReadingLogRepository`) stores one row per session: the chapter, when it ended and how long it lasted. Both readers record it beside their history save, after the incognito check. `readingTimeByDay` and `readingTimeByDayAndTitle` split a session at local midnight when the stats are computed; the per-title form keys by `EntryId`, since a manga and a novel can share a row id.

Sessions follow history: clearing all history clears them per type, Clear database's removal of reset manga history takes those chapters' sessions, a backup carries them by chapter url (`BackupReadingSession`, field 701 on `BackupManga`, 28 on `BackupNovel`), a restore puts them on the chapter with that url, and a migration copies them onto each matched chapter. `(chapter_id, ended_at)` is unique, so a restore or migrate repeated over the same data adds nothing.

### Charts

Vico draws the donuts and the column charts (`StatsChartCards.kt`), themed through `rememberM3VicoTheme`. Slice colours are fixed theme roles per value (`statusColor`, `TrackStatus.color`), so a status keeps its colour between chips. The Reading time screen's bars are plain Compose (`WeekBars`) so each day is a selectable target. Every chart carries a spoken description or, for a donut, leaves it to the legend beside it.

## Key files

- `app/src/main/java/reikai/presentation/stats/StatsLibrary.kt`: `StatsLibraryLoader`, `StatsLibrary`, `TitleReadingSession`.
- `app/src/main/java/reikai/presentation/stats/StatsSeries.kt`: `StatsSeries.statsTitles`, `titleIdByMember`.
- `app/src/main/java/reikai/presentation/stats/StatsTitle.kt`: `StatsTitle`, `StatsTitleFields`.
- `app/src/main/java/reikai/presentation/stats/StatsKernels.kt`: `groupsBy`, `readingTimeByDay`, `readingStreaks`, `scoreBar`.
- `app/src/main/java/reikai/presentation/stats/StatsRows.kt`: `StatsLibrary.rows`, `StatsSort`.
- `app/src/main/java/reikai/presentation/stats/StatsLibraryQuery.kt`: `StatsGroupKey.libraryQuery`.
- `app/src/main/java/reikai/presentation/stats/StatsCharts.kt`: `statsCharts`, `OVERVIEW_GROUPINGS`.
- `app/src/main/java/reikai/presentation/stats/StatsChartCards.kt`: `statsChartCards`, `StatsDonut`, `StatsColumns`.
- `app/src/main/java/reikai/presentation/stats/details/StatsGroupScreen.kt`: `StatsGroupScreen`.
- `app/src/main/java/reikai/presentation/stats/details/StatsReadingScreen.kt`: `StatsReadingScreen`.
- `domain/src/main/java/reikai/domain/readinglog/ReadingLogRepository.kt`: `ReadingLogRepository`.
- `data/src/main/sqldelight/tachiyomi/data/reading_session.sq`: the two session tables and their queries.

## Invariants and traps

**A rule here is written once over `StatsTitle`.** Adding a per-type branch below `statsTitles` is the duplication this layer exists to stop; a new per-entry fact goes into `StatsTitleFields`, which both types must answer.

**A row's search must find exactly the series it counts.** `StatsLibraryQueryTest` runs every row's search over the same titles; a new grouping or a grouping rule changed without its query fails it.

**Reading time per day starts when the log does.** Never backfill sessions from history: its last read is overwritten, so a backfill would place a chapter's whole time on one day.

**A session is recorded only where history is.** Recording inside the history upsert would also count migration's and the gallery update's history writes, which are not reading.

## Decisions

- **Merged series count once, except where a series has several values.** Status, score and length read the series; sources, tags, trackers and languages list every value, as library search matches any member.
- **Start year prefers a tracker's start date.** It is a real date where one exists and reaches back before the log; the log's first session covers the rest.
- **`tag:` and `lang:` keep Mihon's part-of-a-value match.** A tag row's search can therefore also find a longer tag holding it; `tracker:` and `category:` are Reikai's and match whole names.

## Upstream divergences

`StatsViewModel`, `StatsScreen`, `StatsScreenContent` and `StatsScreenState` carry `// RK` patches: the loader, the content switch, the chart cards and their links. `ReaderViewModel` records sessions; `HistoryRepositoryImpl`, `RestoreRepositoryImpl`, `MangaBackupCreator`, `MangaRestorer`, `BackupManga`, `RestoredManga` and `MigrateMangaUseCase` carry the log through clear, backup, restore and migrate. `LibrarySearchAst` adds the `status`, `tracker`, `category` and `score` fields.

## Extending

A new grouping: add a `StatsDimension` and `StatsGroupKey` case, its key in `groupKeys`, its order in `StatsLibrary.rows`, its search in `libraryQuery` (or null), its label in `StatsRowItems.kt`, and list it in `OVERVIEW_GROUPINGS` for a card. `StatsLibraryQueryTest` then covers it.

## Tests

`StatsKernelsTest`, `StatsChartsTest`, `StatsSeriesTest` (builder cases over both types), `StatsLibraryQueryTest` (each row's search), `LibraryQueryMatchTest` (the search fields), `ReadingLogRepositoryTest` (the log over both tables), and `MigrateEngineConformanceTest` (session copies). Run with `./gradlew :app:testDebugUnitTest --tests "reikai.presentation.stats.*"` and `:data:testDebugUnitTest --tests "reikai.data.readinglog.*"`.

## Related

User doc: [statistics.md](../../statistics.md).
