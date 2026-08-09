package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The add order both content types run. It is one implementation over each type's own verbs, so the
 * order is pinned here once rather than per type; what each type passes in is pinned at its own call
 * site. Background: docs/dev/plans/content-layer-add-flow.md.
 */
class AddSequenceTest {

    private val calls = mutableListOf<String>()

    private suspend fun favoriteSucceeding(): Long? {
        calls += "favorite"
        return 7L
    }

    private suspend fun favoriteFailing(): Long? {
        calls += "favorite"
        return null
    }

    private suspend fun file(id: Long, categoryIds: List<Long>) {
        calls += "file $id ${categoryIds.joinToString()}"
    }

    @Test
    fun `the favorite lands before the categories`() = runTest {
        addEntry({ listOf(3L) }, ::favoriteSucceeding, ::file)

        calls shouldBe listOf("favorite", "file 7 3")
    }

    @Test
    fun `a failed favorite files nothing`() = runTest {
        addEntry({ listOf(3L) }, ::favoriteFailing, ::file)

        calls shouldBe listOf("favorite")
    }

    @Test
    fun `a failed favorite reports the add abandoned`() = runTest {
        addEntry({ listOf(3L) }, ::favoriteFailing, ::file) shouldBe AddOutcome.Failed
    }

    @Test
    fun `no usable default writes nothing at all`() = runTest {
        addEntry({ null }, ::favoriteSucceeding, ::file)

        calls shouldBe emptyList()
    }

    @Test
    fun `no usable default asks the caller to prompt`() = runTest {
        addEntry({ null }, ::favoriteSucceeding, ::file) shouldBe AddOutcome.NeedsCategoryChoice
    }

    @Test
    fun `the picker's confirm writes both, favorite first`() = runTest {
        finishAdd(listOf(3L, 4L), ::favoriteSucceeding, ::file)

        calls shouldBe listOf("favorite", "file 7 3, 4")
    }

    @Test
    fun `an empty category choice still favorites`() = runTest {
        finishAdd(emptyList(), ::favoriteSucceeding, ::file)

        calls shouldBe listOf("favorite", "file 7 ")
    }
}
