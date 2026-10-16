package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.presentation.library.LibraryQueryFields
import reikai.presentation.library.LibraryQuerySource
import reikai.presentation.library.categoryMatches
import reikai.presentation.library.libraryQueryMatches
import tachiyomi.domain.category.model.Category
import kotlin.time.Instant

/** Each details row's library search finds exactly the series the row counts. */
class StatsLibraryQueryTest {

    @ParameterizedTest
    @EnumSource(StatsDimension::class, names = ["START_YEAR"], mode = EnumSource.Mode.EXCLUDE)
    fun `a row's search finds the series it counts`(dimension: StatsDimension) {
        val groups = titles.groupsBy(dimension, TimeZone.UTC)

        groups.associate { it.key to found(it.key) } shouldBe groups.associate { it.key to it.count }
    }

    private fun found(key: StatsGroupKey): Int {
        val query = key.libraryQuery(trackerNames::get, { id -> categories.firstOrNull { it.id == id }?.name })!!
        val node = QueryNode.from(query)
        val matches = node.categoryMatches(categories)
        return titles.count { libraryQueryMatches(node, it, fields(matches)) }
    }

    private fun fields(categoryMatches: Map<String, Set<Long>>) = LibraryQueryFields<StatsTitle>(
        id = { it.entryId.rawId },
        title = { "" },
        author = { null },
        artist = { null },
        description = { null },
        notes = { null },
        genre = { it.genres },
        sources = { title -> title.sources.map { LibraryQuerySource(it.key, it.name, it.language, false) } },
        unreadCount = { it.totalChapters - it.readChapters },
        readCount = { it.readChapters },
        totalChapters = { it.totalChapters },
        dateAdded = { null },
        fetchInterval = { 0 },
        nextUpdate = { null },
        matchesChapter = { _, _ -> false },
        matchesTagTerm = { _, _ -> false },
        status = { it.status },
        trackerNames = { title -> title.trackerIds.mapNotNull(trackerNames::get) },
        categoryIds = { it.categoryIds.ifEmpty { listOf(0L) } },
        matchesCategory = { title, term -> categoryMatches[term]?.let { ids -> title.categoryIds.any { it in ids } } },
        meanScore = { it.meanScore },
    )

    private val trackerNames = mapOf(1L to "AniList", 2L to "MyAnimeList")

    private val categories = listOf(Category(5L, "Read", 0L, 0L), Category(6L, "Reading", 1L, 0L))

    private val titles = listOf(
        title(1L, status = 1L, score = null, chapters = 0, trackers = emptyList(), categories = emptyList()),
        title(2L, status = 2L, score = 1.2, chapters = 5, trackers = listOf(1L), categories = listOf(5L)),
        title(3L, status = 2L, score = 7.4, chapters = 300, trackers = listOf(1L, 2L), categories = listOf(6L)),
        title(4L, status = 6L, score = 9.6, chapters = 30, trackers = listOf(2L), categories = listOf(5L, 6L)),
    )

    private fun title(
        id: Long,
        status: Long,
        score: Double?,
        chapters: Long,
        trackers: List<Long>,
        categories: List<Long>,
    ) = StatsTitle(
        title = "",
        entryId = EntryId.Manga(id),
        status = status,
        sources = listOf(StatsSource("s$id", "Source $id", if (id % 2 == 0L) "en" else "ja")),
        genres = listOf(if (id % 2 == 0L) "Drama" else "Comedy"),
        categoryIds = categories,
        totalChapters = chapters,
        readChapters = 0,
        trackerIds = trackers,
        meanScore = score,
        trackStatus = null,
        trackerStartedAt = null,
        trackerFinishedAt = null,
        readDuration = 0,
        firstLoggedReadAt = null as Instant?,
    )
}
