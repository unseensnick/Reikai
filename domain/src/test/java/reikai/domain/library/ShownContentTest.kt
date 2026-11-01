package reikai.domain.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
        shown.set(ContentType.NOVELS)

        scoped.changes().first() shouldBe ContentType.NOVELS
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

        shown.set(ContentType.ALL)

        scoped.get() shouldBe ContentType.MANGA
    }

    @Test
    fun `a write while both types show reaches the stored choice`() {
        scoped.set(ContentType.ALL)

        stored.get() shouldBe ContentType.ALL
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
