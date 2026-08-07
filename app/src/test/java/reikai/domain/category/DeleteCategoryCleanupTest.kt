package reikai.domain.category

import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Pins the shared category delete cleanup that both content types run. The silent-failure risk it guards
 * is the preference scrub: before this was shared, deleting a novel category left its id dangling in the
 * six novel category-id prefs, so an include/exclude filter kept pointing at a category that no longer
 * exists. These assert the deleted id is scrubbed from every set pref (and only that id), the default is
 * cleared only when it names the deleted category, and the survivors are renumbered gaplessly.
 */
class DeleteCategoryCleanupTest {

    private val repository = mockk<CategoryRepository>(relaxed = true)

    @Test
    fun `scrubs only the deleted id from every set preference`() = runTest {
        coEvery { repository.getUnfiltered() } returns emptyList()
        val includeSet = FakePreference(setOf("1", "2", "3"))
        val excludeSet = FakePreference(setOf("2"))
        val unrelatedSet = FakePreference(setOf("5"))

        deleteCategoryAndCleanup(
            repository,
            categoryId = 2L,
            defaultCategoryPreferences = listOf(FakePreference(-1)),
            categorySetPreferences = listOf(includeSet, excludeSet, unrelatedSet),
        )

        listOf(includeSet.get(), excludeSet.get(), unrelatedSet.get()) shouldBe
            listOf(setOf("1", "3"), emptySet(), setOf("5"))
    }

    @Test
    fun `clears the default preference only when it names the deleted category`() = runTest {
        coEvery { repository.getUnfiltered() } returns emptyList()
        val namesDeleted = FakePreference(-1).apply { set(2) }
        val namesOther = FakePreference(-1).apply { set(5) }

        deleteCategoryAndCleanup(repository, 2L, listOf(namesDeleted), emptyList())
        deleteCategoryAndCleanup(repository, 2L, listOf(namesOther), emptyList())

        listOf(namesDeleted.isSet(), namesOther.get()) shouldBe listOf(false, 5)
    }

    /** A universal category is referenced by both libraries, so deleting it has to clean both sides. */
    @Test
    fun `clears a matching default preference on every side it is given`() = runTest {
        coEvery { repository.getUnfiltered() } returns emptyList()
        val mangaDefault = FakePreference(-1).apply { set(2) }
        val novelDefault = FakePreference(-1).apply { set(2) }

        deleteCategoryAndCleanup(repository, 2L, listOf(mangaDefault, novelDefault), emptyList())

        listOf(mangaDefault.isSet(), novelDefault.isSet()) shouldBe listOf(false, false)
    }

    @Test
    fun `renumbers the surviving categories into a gapless order`() = runTest {
        coEvery { repository.getUnfiltered() } returns listOf(category(1), category(3), category(4))
        val orderedIds = slot<List<Long>>()
        coEvery { repository.updateAllOrders(capture(orderedIds)) } just Runs

        deleteCategoryAndCleanup(repository, 2L, listOf(FakePreference(-1)), emptyList())

        orderedIds.captured shouldBe listOf(1L, 3L, 4L)
    }

    /** The system row keeps its -1 sort, else it ties with the first user category and can sort after it. */
    @Test
    fun `leaves the system category out of the renumbering`() = runTest {
        coEvery { repository.getUnfiltered() } returns listOf(category(0), category(1), category(3))
        val orderedIds = slot<List<Long>>()
        coEvery { repository.updateAllOrders(capture(orderedIds)) } just Runs

        deleteCategoryAndCleanup(repository, 2L, listOf(FakePreference(-1)), emptyList())

        orderedIds.captured shouldBe listOf(1L, 3L)
    }

    @Test
    fun `deletes the category row`() = runTest {
        coEvery { repository.getUnfiltered() } returns emptyList()

        deleteCategoryAndCleanup(repository, 7L, listOf(FakePreference(-1)), emptyList())

        coVerify { repository.delete(7L) }
    }

    private fun category(id: Long) = Category(id = id, name = "c$id", order = id, flags = 0L)
}

/** In-memory [Preference] for the cleanup under test, which only exercises get / set / delete / isSet. */
private class FakePreference<T>(private val default: T) : Preference<T> {
    private var value: T = default
    private var assigned = false

    override fun key() = "fake"
    override fun get(): T = value
    override fun set(value: T) {
        this.value = value
        assigned = true
    }
    override fun isSet(): Boolean = assigned
    override fun delete() {
        value = default
        assigned = false
    }
    override fun defaultValue(): T = default
    override fun changes(): Flow<T> = throw UnsupportedOperationException()
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = throw UnsupportedOperationException()
}
