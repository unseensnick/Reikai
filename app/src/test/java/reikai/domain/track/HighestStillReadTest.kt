package reikai.domain.track

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HighestStillReadTest {

    private data class Row(val read: Boolean, val number: Double)

    private fun highest(vararg rows: Row) = highestStillRead(rows.asList(), Row::read, Row::number)

    @Test
    fun `an unread chapter above the read ones is passed over`() {
        highest(Row(true, 3.0), Row(false, 9.0), Row(true, 7.0)) shouldBe Row(true, 7.0)
    }

    @Test
    fun `a read chapter with no recognised number is no place to fall back to`() {
        highest(Row(true, -1.0), Row(true, 0.0)).shouldBeNull()
    }
}
