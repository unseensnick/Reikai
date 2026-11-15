package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VolumeHeaderTest {

    private val series = loadedDetails().let {
        it.copy(details = it.details.copy(description = "The series", notes = "Series notes"))
    }

    @Test
    fun `a picked volume shows its own description`() {
        series.headedBy(
            VolumeOwnFiles(cover = null, description = "Volume two"),
            notes = "",
        ).details.description shouldBe
            "Volume two"
    }

    @Test
    fun `a picked volume with no description of its own shows none`() {
        series.headedBy(VolumeOwnFiles(cover = null, description = null), notes = "").details.description shouldBe null
    }

    @Test
    fun `a picked volume shows its own notes, not the series'`() {
        series.headedBy(VolumeOwnFiles(cover = null, description = null), notes = "").details.notes shouldBe ""
    }

    @Test
    fun `a picked volume's cover heads the page`() {
        series.headedBy(
            VolumeOwnFiles(cover = "vol2.jpg", description = null),
            notes = "",
        ).details.header.cover shouldBe
            "vol2.jpg"
    }

    @Test
    fun `a picked volume without a cover keeps the series'`() {
        series.headedBy(VolumeOwnFiles(cover = null, description = null), notes = "").details.header shouldBe
            series.details.header
    }
}
