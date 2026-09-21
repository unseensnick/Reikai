package reikai.domain.source

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

/** Novel sources are switched on the novel disabled list, by their text id. */
class ToggleNovelSourceTest {

    private var disabled = setOf("tachiyomi:7")
    private val preference = mockk<Preference<Set<String>>> {
        every { get() } answers { disabled }
        every { set(any()) } answers { disabled = firstArg() }
    }
    private val toggle = ToggleNovelSource(mockk { every { disabledNovelSources } returns preference })

    @Test
    fun `a disabled source flips back on`() {
        toggle.await("tachiyomi:7")

        disabled shouldBe emptySet()
    }

    @Test
    fun `switching several off leaves each on the list`() {
        toggle.await(listOf("tachiyomi:8", "plugin"), enable = false)

        disabled shouldBe setOf("tachiyomi:7", "tachiyomi:8", "plugin")
    }
}
