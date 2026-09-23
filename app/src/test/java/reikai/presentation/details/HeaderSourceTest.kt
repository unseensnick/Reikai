package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HeaderSourceTest {

    @Test
    fun `the unified view of a merged group names the whole group`() {
        headerNamesWholeGroup(sourceCount = 2, selectedSource = null) shouldBe true
    }

    @Test
    fun `a chosen source of a merged group names that source`() {
        headerNamesWholeGroup(sourceCount = 2, selectedSource = 7L) shouldBe false
    }

    @Test
    fun `a series with one source names it`() {
        headerNamesWholeGroup(sourceCount = 1, selectedSource = null) shouldBe false
    }
}
