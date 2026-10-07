package reikai.presentation.recents

import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.RecentsCategoryFilter
import reikai.domain.entry.EntryId
import reikai.domain.recents.RECENTS_FEED_LIMIT
import tachiyomi.domain.manga.model.CustomMangaInfo
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The newly-added lane both adapters build through [recentsAddedLane]: the surface's category filter
 * reaches the query, the feed's cutoff and limit bound it, and a custom title lands on its own entry.
 */
class RecentsAddedLaneTest {

    private data class Row(val id: Long, val title: String)

    private data class Query(val after: Long, val limit: Long, val included: List<Long>, val excluded: List<Long>)

    private val queries = mutableListOf<Query>()

    private fun lane(
        categories: Flow<RecentsCategoryFilter> = flowOf(RecentsCategoryFilter()),
        rows: List<Row> = listOf(Row(1, "one"), Row(2, "two")),
        custom: List<CustomMangaInfo> = emptyList(),
    ) = recentsAddedLane(
        categories = categories,
        subscribe = { after, limit, included, excluded ->
            queries += Query(after, limit, included, excluded)
            flowOf(rows)
        },
        customInfo = flowOf(custom),
        customInfoId = CustomMangaInfo::mangaId,
        entryId = Row::id,
        withCustomInfo = { info -> copy(title = info?.title ?: title) },
        toItem = {
            RecentsItem(entryId = EntryId.Manga(it.id), timestamp = 0L, lane = RecentsLane.Added, payload = it)
        },
    )

    @Test
    fun `the lane says it is loading until the query answers`() = runTest {
        lane().first() shouldBe RecentsLaneRows.Loading
    }

    @Test
    fun `the surface's category selection reaches the query`() = runTest {
        lane(categories = flowOf(RecentsCategoryFilter(include = listOf(3), exclude = listOf(4)))).take(2).toList()

        queries.map { it.included to it.excluded } shouldBe listOf(listOf(3L) to listOf(4L))
    }

    @Test
    fun `a changed category selection queries again under the new one`() = runTest {
        val categories = MutableStateFlow(RecentsCategoryFilter(exclude = listOf(4)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lane(categories = categories).collect {} }

        categories.value = RecentsCategoryFilter(exclude = listOf(5))

        queries.map { it.excluded } shouldBe listOf(listOf(4L), listOf(5L))
    }

    @Test
    fun `the query is capped at the feed limit`() = runTest {
        lane().take(2).toList()

        queries.single().limit shouldBe RECENTS_FEED_LIMIT
    }

    @Test
    fun `the query reaches back no further than the feed's window`() = runTest {
        val now = Clock.System.now()

        lane().take(2).toList()

        queries.single().after shouldBeInRange
            (now - 93.days).toEpochMilliseconds()..(now - 88.days).toEpochMilliseconds()
    }

    @Test
    fun `a custom title replaces its own entry's title only`() = runTest {
        val loaded = lane(custom = listOf(CustomMangaInfo(mangaId = 2, title = "renamed"))).take(2).toList().last()

        loaded.items.map { (it.payload as Row).title } shouldBe listOf("one", "renamed")
    }
}
