package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupFeedRow
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.source.FeedSavedSearchRepository
import reikai.domain.source.MAX_FEED_ROWS
import reikai.domain.source.SavedSearchRepository
import reikai.domain.source.SourceKey
import reikai.domain.source.model.FeedSavedSearch
import reikai.domain.source.model.SavedSearch

/**
 * A restore lands on ids that are not the ones the backup was written with, so everything here is
 * matched by what it holds. The case that matters is restoring onto an install that already has some
 * of it: nothing may be doubled, and a feed row must find the search it belongs to.
 */
class FeedRestorerTest {

    private val searches = FakeSavedSearches()
    private val feed = FakeFeed()
    private val restorer = FeedRestorer(searches, feed)

    private val manga = SourceKey.Manga(9001L)
    private val comedy = BackupSavedSearch(manga.serialize(), "Comedy", "funny", """[{"a":1}]""")

    @Test
    fun `a search the install does not have is added`() = runTest {
        restorer(listOf(comedy), emptyList())

        searches.rows.value.single().name shouldBe "Comedy"
    }

    @Test
    fun `restoring the same backup twice adds nothing the second time`() = runTest {
        restorer(listOf(comedy), listOf(BackupFeedRow(manga.serialize(), true, comedy)))

        restorer(listOf(comedy), listOf(BackupFeedRow(manga.serialize(), true, comedy)))

        (searches.rows.value.size to feed.rows.value.size) shouldBe (1 to 1)
    }

    @Test
    fun `a feed row uses the search this install already has`() = runTest {
        searches.insert(manga, "Comedy", "funny", """[{"a":1}]""")

        restorer(emptyList(), listOf(BackupFeedRow(manga.serialize(), true, comedy)))

        feed.rows.value.single().savedSearchId shouldBe searches.rows.value.single().id
    }

    @Test
    fun `a search whose name differs is kept apart from the one already here`() = runTest {
        searches.insert(manga, "Something else", "funny", """[{"a":1}]""")

        restorer(listOf(comedy), emptyList())

        searches.rows.value.map { it.name } shouldBe listOf("Something else", "Comedy")
    }

    @Test
    fun `a row naming a source that cannot be read is left out`() = runTest {
        restorer(emptyList(), listOf(BackupFeedRow("not a source key", true, null)))

        feed.rows.value.shouldBeEmpty()
    }

    @Test
    fun `a plain listing row restores without a search`() = runTest {
        restorer(emptyList(), listOf(BackupFeedRow(manga.serialize(), true, null)))

        feed.rows.value.single().savedSearchId shouldBe null
    }

    @Test
    fun `two searches that differ only by query both survive`() = runTest {
        // The name is the same, so only the query clause of the match can tell them apart. Without it
        // the second collapses onto the first and the reader loses a search on restore.
        searches.insert(manga, "Comedy", "hilarious", """[{"a":1}]""")

        restorer(listOf(comedy), emptyList())

        searches.rows.value.map { it.query } shouldBe listOf("hilarious", "funny")
    }

    @Test
    fun `two searches that differ only by filters both survive`() = runTest {
        searches.insert(manga, "Comedy", "funny", """[{"b":2}]""")

        restorer(listOf(comedy), emptyList())

        searches.rows.value.map { it.filtersJson } shouldBe listOf("""[{"b":2}]""", """[{"a":1}]""")
    }

    @Test
    fun `the same name on a novel plugin is a different search`() = runTest {
        // A match is scoped to its source. Unscoped, a feed row on the plugin would be re-pointed at
        // the manga source's search and quietly show the wrong source's results.
        val novel = SourceKey.Novel("novelbin")
        searches.insert(novel, "Comedy", "funny", """[{"a":1}]""")

        restorer(listOf(comedy), emptyList())

        searches.rows.value.map { it.sourceKey } shouldBe listOf(novel, manga)
    }

    @Test
    fun `a feed row unlike the ones already here is added beside them`() = runTest {
        // The dedup has to compare the row, not merely notice the feed is not empty.
        feed.insert(manga, savedSearchId = null, global = true)

        restorer(listOf(comedy), listOf(BackupFeedRow(manga.serialize(), true, comedy)))

        feed.rows.value.map { it.savedSearchId } shouldBe listOf(null, 1L)
    }

    @Test
    fun `rows come back in the order they were arranged in`() = runTest {
        val novel = SourceKey.Novel("novelbin")

        restorer(
            emptyList(),
            listOf(
                BackupFeedRow(manga.serialize(), true, null, feedOrder = 7),
                BackupFeedRow(novel.serialize(), true, null, feedOrder = 3),
            ),
        )

        feed.rows.value.map { it.sourceKey } shouldBe listOf(novel, manga)
    }

    @Test
    fun `a backup carrying more rows than a feed holds is cut to the cap`() = runTest {
        // Backups are untrusted input, and every row fetches on every open.
        val rows = (1..MAX_FEED_ROWS + 5).map {
            BackupFeedRow(SourceKey.Manga(it.toLong()).serialize(), true, null, feedOrder = it.toLong())
        }

        restorer(emptyList(), rows)

        feed.rows.value shouldHaveSize MAX_FEED_ROWS
    }
}

private class FakeSavedSearches : SavedSearchRepository {

    val rows = MutableStateFlow(emptyList<SavedSearch>())

    override suspend fun getBySource(sourceKey: SourceKey): List<SavedSearch> =
        rows.value.filter { it.sourceKey == sourceKey }

    override fun subscribeBySource(sourceKey: SourceKey): Flow<List<SavedSearch>> =
        rows.map { all -> all.filter { it.sourceKey == sourceKey } }

    override suspend fun getAll(): List<SavedSearch> = rows.value

    override suspend fun insert(
        sourceKey: SourceKey,
        name: String,
        query: String?,
        filtersJson: String?,
    ): Long {
        val id = rows.value.size + 1L
        rows.value += SavedSearch(id, sourceKey, name, query, filtersJson)
        return id
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

private class FakeFeed : FeedSavedSearchRepository {

    val rows = MutableStateFlow(emptyList<FeedSavedSearch>())

    override fun subscribeGlobal(): Flow<List<FeedSavedSearch>> = rows.map { all -> all.filter { it.global } }

    override suspend fun getAll(): List<FeedSavedSearch> = rows.value

    override suspend fun countGlobal(): Long = rows.value.count { it.global }.toLong()

    /** Dedupes like the real one, which is where the restore's own no-double rule now lives. */
    override suspend fun insert(sourceKey: SourceKey, savedSearchId: Long?, global: Boolean): Long {
        rows.value
            .firstOrNull { it.sourceKey == sourceKey && it.global == global && it.savedSearchId == savedSearchId }
            ?.let { return it.id }
        val id = rows.value.size + 1L
        rows.value += FeedSavedSearch(id, sourceKey, savedSearchId, global, id)
        return id
    }

    override suspend fun updateOrders(orderedIds: List<Long>) {
        rows.value = rows.value.map { row ->
            orderedIds.indexOf(row.id).takeIf { it >= 0 }?.let { row.copy(feedOrder = it.toLong()) } ?: row
        }
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
