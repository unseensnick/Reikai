package reikai.presentation.browse.catalogue

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.source.SavedSearchRepository
import reikai.domain.source.SourceKey
import reikai.domain.source.model.SavedSearch

/**
 * The model's own rule: a search worth keeping has a name and something in it. Everything else about a
 * saved search belongs to the repository or the catalogue's adapter, and is pinned there.
 */
class SavedSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val sourceKey = SourceKey.Novel("novelbin")
    private lateinit var repository: FakeSavedSearchRepository

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeSavedSearchRepository()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a search with a query and filters is saved`() = runTest(dispatcher) {
        val model = SavedSearchViewModel(sourceKey, repository)

        model.awaitingSave { save("Completed", SavedSearchDraft("sword", """{"a":1}""")) }

        // The whole row, not just its name: what the reader had on screen is the payload, and a save
        // that stored the name and dropped the draft would read as working right up until it is used.
        repository.rows.value.single() shouldBe SavedSearch(
            id = 1L,
            sourceKey = sourceKey,
            name = "Completed",
            query = "sword",
            filtersJson = """{"a":1}""",
        )
    }

    @Test
    fun `a search holding nothing is not saved`() = runTest(dispatcher) {
        val model = SavedSearchViewModel(sourceKey, repository)

        model.awaitingSave { save("Empty", SavedSearchDraft(query = null, filtersJson = null)) }

        repository.rows.value.shouldBeEmpty()
    }

    @Test
    fun `a search with a blank name is not saved`() = runTest(dispatcher) {
        val model = SavedSearchViewModel(sourceKey, repository)

        model.awaitingSave { save("   ", SavedSearchDraft(query = "sword", filtersJson = null)) }

        repository.rows.value.shouldBeEmpty()
    }

    @Test
    fun `a saved name is stored without its surrounding spaces`() = runTest(dispatcher) {
        val model = SavedSearchViewModel(sourceKey, repository)

        model.awaitingSave { save("  Completed  ", SavedSearchDraft("sword", filtersJson = null)) }

        repository.rows.value.single().name shouldBe "Completed"
    }

    @Test
    fun `a deleted search goes`() = runTest(dispatcher) {
        val model = SavedSearchViewModel(sourceKey, repository)
        model.awaitingSave { save("Completed", SavedSearchDraft("sword", filtersJson = null)) }

        model.awaitingSave { delete(repository.rows.value.single().id) }

        repository.rows.value.shouldBeEmpty()
    }
}

/**
 * Runs [block] and waits only for what it launched. The guard runs before the launch, so a refused
 * draft leaves no new child and this returns at once, which is what makes a negative case
 * deterministic: the write itself is on the IO dispatcher, where advancing a test dispatcher reaches
 * nothing. Pre-existing children are excluded because the model's own subscription never ends.
 */
private suspend fun SavedSearchViewModel.awaitingSave(block: SavedSearchViewModel.() -> Unit) {
    val job = viewModelScope.coroutineContext[Job]!!
    val before = job.children.toSet()
    block()
    (job.children.toSet() - before).joinAll()
}

private class FakeSavedSearchRepository : SavedSearchRepository {

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
