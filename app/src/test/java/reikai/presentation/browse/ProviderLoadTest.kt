package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A multi-provider Browse list shows each half as it lands, and never reads empty while one is out. */
class ProviderLoadTest {

    @Test
    fun `nothing answered is loading`() {
        ProviderLoad.of(listOf(null, null)) shouldBe ProviderLoad(isLoading = true, hasPending = true)
    }

    @Test
    fun `one of two answered stops loading but is still pending`() {
        ProviderLoad.of(listOf(listOf("manga"), null)) shouldBe ProviderLoad(isLoading = false, hasPending = true)
    }

    @Test
    fun `everything answered is neither loading nor pending`() {
        ProviderLoad.of(listOf(listOf("manga"), emptyList<String>())) shouldBe
            ProviderLoad(isLoading = false, hasPending = false)
    }

    @Test
    fun `an empty list with a half still pending is not empty`() {
        Listed(items = emptyList(), hasPending = true).isEmpty shouldBe false
    }

    @Test
    fun `an empty list with every half answered is empty`() {
        Listed(items = emptyList(), hasPending = false).isEmpty shouldBe true
    }

    private data class Listed(override val items: List<String>, override val hasPending: Boolean) : ProviderList
}
