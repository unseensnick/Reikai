package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupFeedRow
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.source.FeedSavedSearchRepository
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
}

private class FakeSavedSearches : SavedSearchRepository {

    val rows = MutableStateFlow(emptyList<SavedSearch>())

    override suspend fun getById(id: Long): SavedSearch? = rows.value.firstOrNull { it.id == id }

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

    override fun subscribeBySource(sourceKey: SourceKey): Flow<List<FeedSavedSearch>> =
        rows.map { all -> all.filter { !it.global && it.sourceKey == sourceKey } }

    override suspend fun getAll(): List<FeedSavedSearch> = rows.value

    override suspend fun countGlobal(): Long = rows.value.count { it.global }.toLong()

    override suspend fun countBySource(sourceKey: SourceKey): Long =
        rows.value.count { !it.global && it.sourceKey == sourceKey }.toLong()

    override suspend fun insert(sourceKey: SourceKey, savedSearchId: Long?, global: Boolean): Long {
        val id = rows.value.size + 1L
        rows.value += FeedSavedSearch(id, sourceKey, savedSearchId, global, id)
        return id
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
