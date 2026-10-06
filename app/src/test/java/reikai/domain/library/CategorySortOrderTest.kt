package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CategorySortOrderTest {

    // The Int is what installs and backups already carry, so each order keeps its number for good.
    @ParameterizedTest
    @CsvSource("0, MANUAL", "1, A_TO_Z", "2, Z_TO_A")
    fun `a stored value reads back as its order`(stored: Int, order: CategorySortOrder) {
        CategorySortOrder.fromStored(stored) shouldBe order
    }

    @ParameterizedTest
    @CsvSource("0, MANUAL", "1, A_TO_Z", "2, Z_TO_A")
    fun `an order is stored as the value it reads back from`(stored: Int, order: CategorySortOrder) {
        order.stored shouldBe stored
    }

    @Test
    fun `an unknown stored value reads as manual`() {
        CategorySortOrder.fromStored(7) shouldBe CategorySortOrder.MANUAL
    }
}
