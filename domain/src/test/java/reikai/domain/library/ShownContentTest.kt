package reikai.domain.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class ShownContentTest {

    private val stored = FakePreference(ContentType.MANGA)
    private val shown = FakePreference(ContentType.ALL)
    private val scoped = ShownContentPreference(stored, shown)

    @Test
    fun `the chip keeps its own choice while both types show`() {
        scoped.get() shouldBe ContentType.MANGA
    }

    @Test
    fun `the chip reads the shown type while the other is hidden`() {
        shown.set(ContentType.NOVELS)

        scoped.get() shouldBe ContentType.NOVELS
    }

    @Test
    fun `changes follow the Show setting`() = runTest {
        val seen = mutableListOf<ContentType>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scoped.changes().toList(seen) }

        shown.set(ContentType.NOVELS)

        seen.last() shouldBe ContentType.NOVELS
    }

    @Test
    fun `the state Browse reads follows the Show setting`() = runTest {
        shown.set(ContentType.NOVELS)

        scoped.stateIn(backgroundScope).value shouldBe ContentType.NOVELS
    }

    @Test
    fun `the stored side ignores the Show setting`() {
        shown.set(ContentType.NOVELS)

        scoped.stored.set(ContentType.ALL)

        scoped.stored.get() shouldBe ContentType.ALL
    }

    @Test
    fun `a write while a type is hidden leaves the stored choice alone`() {
        shown.set(ContentType.NOVELS)

        scoped.set(ContentType.NOVELS)

        stored.get() shouldBe ContentType.MANGA
    }

    @Test
    fun `the stored choice comes back when both types show again`() {
        shown.set(ContentType.NOVELS)
        scoped.set(ContentType.NOVELS)

        shown.set(ContentType.ALL)

        scoped.get() shouldBe ContentType.MANGA
    }

    @Test
    fun `a write while both types show reaches the stored choice`() {
        scoped.set(ContentType.ALL)

        stored.get() shouldBe ContentType.ALL
    }

    @Test
    fun `a new order goes back into the slots its rows held`() {
        listOf("m1", "n1", "m2", "n2", "m3").withShownOrder(listOf("n2", "n1")) shouldBe
            listOf("m1", "n2", "m2", "n1", "m3")
    }

    @Test
    fun `a new order over the whole list replaces it`() {
        listOf("a", "b").withShownOrder(listOf("b", "a")) shouldBe listOf("b", "a")
    }

    private class FakePreference(private val default: ContentType) : Preference<ContentType> {
        private val value = MutableStateFlow(default)

        override fun key() = "fake"
        override fun get() = value.value
        override fun set(value: ContentType) {
            this.value.value = value
        }
        override fun isSet() = true
        override fun delete() = Unit
        override fun defaultValue() = default
        override fun changes(): Flow<ContentType> = value
        override fun stateIn(scope: CoroutineScope): StateFlow<ContentType> = value
    }
}
