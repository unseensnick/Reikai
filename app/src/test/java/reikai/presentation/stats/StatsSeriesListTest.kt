package reikai.presentation.stats

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/** A Statistics row lists exactly the series it counts, as the library draws them. */
class StatsSeriesListTest {

    @ParameterizedTest
    @EnumSource(StatsDimension::class)
    fun `every row lists as many series as it counts`(dimension: StatsDimension) {
        LIBRARY.groupsBy(dimension, UTC).map { it.count } shouldBe
            LIBRARY.groupsBy(dimension, UTC).map { LIBRARY.titlesIn(it.key, UTC).size }
    }

    @Test
    fun `a tag row lists its series whatever case each source spells the tag in`() {
        val actionRow = LIBRARY.groupsBy(StatsDimension.TAG, UTC)
            .single { (it.key as StatsGroupKey.Tag).name.equals("action", ignoreCase = true) }
        LIBRARY.titlesIn(actionRow.key, UTC).map { it.entryId } shouldContainExactly
            listOf(EntryId.Manga(1L), EntryId.Novel(1L))
    }

    @Test
    fun `a merged row is found when the library leads it on another member`() {
        // Statistics reads the series off member 7; the library row leads on member 5.
        val merged = row(EntryId.Manga(5L), members = listOf(5L, 7L))
        val titleOf = { member: EntryId -> EntryId.Manga(7L).takeIf { member.rawId in setOf(5L, 7L) } }
        listOf(merged).standingFor(setOf(EntryId.Manga(7L)), titleOf) shouldBe listOf(merged)
    }

    @Test
    fun `a novel row is not taken for the manga sharing its id`() {
        listOf(row(EntryId.Novel(1L))).standingFor(setOf(EntryId.Manga(1L)), titleOf = { it }) shouldBe emptyList()
    }

    @Test
    fun `a row of another series is left out`() {
        listOf(row(EntryId.Manga(2L))).standingFor(setOf(EntryId.Manga(1L)), titleOf = { it }) shouldBe emptyList()
    }

    private fun row(entryId: EntryId, members: List<Long> = emptyList()) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = entryId.rawId),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        relatedMangaIds = members,
        entryId = entryId,
    )

    private companion object {
        val UTC = TimeZone.UTC

        fun title(
            entryId: EntryId,
            sources: List<StatsSource>,
            genres: List<String>,
            categoryIds: List<Long>,
            totalChapters: Long,
            trackerIds: List<Long>,
            meanScore: Double?,
            startedAt: Instant?,
        ) = StatsTitle(
            entryId = entryId,
            title = "",
            status = 1L,
            sources = sources,
            genres = genres,
            categoryIds = categoryIds,
            totalChapters = totalChapters,
            readChapters = 1,
            trackerIds = trackerIds,
            meanScore = meanScore,
            trackStatus = null,
            trackerStartedAt = startedAt,
            readDuration = 0,
            firstLoggedReadAt = null,
        )

        // A manga and a novel sharing a raw id, tags spelt in two cases, two sources on one series, a
        // series filed nowhere, and every value some row of each dimension groups on.
        val LIBRARY = listOf(
            title(
                EntryId.Manga(1L),
                sources = listOf(StatsSource("1", "One", "en"), StatsSource("2", "Two", "ja")),
                genres = listOf("Action", "Drama"),
                categoryIds = listOf(1L, 2L),
                totalChapters = 30,
                trackerIds = listOf(1L, 2L),
                meanScore = 8.0,
                startedAt = Instant.fromEpochMilliseconds(1_600_000_000_000L),
            ),
            title(
                EntryId.Novel(1L),
                sources = listOf(StatsSource("plugin", "Plugin", "en")),
                genres = listOf("action "),
                categoryIds = emptyList(),
                totalChapters = 0,
                trackerIds = emptyList(),
                meanScore = null,
                startedAt = null,
            ),
            title(
                EntryId.Manga(3L),
                sources = listOf(StatsSource("1", "One", "en")),
                genres = emptyList(),
                categoryIds = listOf(2L),
                totalChapters = 300,
                trackerIds = listOf(1L),
                meanScore = 3.4,
                startedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            ),
        )
    }
}
