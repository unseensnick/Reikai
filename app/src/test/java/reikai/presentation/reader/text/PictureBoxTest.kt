package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A chapter picture takes the box the page's `max-width: 100%` gives it, from the size it is at the source. */
class PictureBoxTest {

    @Test
    @DisplayName("a picture narrower than the column keeps its own width")
    fun narrowKeepsItsWidth() {
        pictureBox(sourceWidth = 300, sourceHeight = 600, columnPx = 1242, density = 3f) shouldBe
            PictureBox(900, 1800)
    }

    @Test
    @DisplayName("a picture wider than the column is drawn at the column, keeping its shape")
    fun wideFillsTheColumn() {
        pictureBox(sourceWidth = 800, sourceHeight = 12711, columnPx = 1242, density = 3f) shouldBe
            PictureBox(1242, 19733)
    }

    @Test
    @DisplayName("a picture with no size of its own has no box")
    fun noSizeNoBox() {
        pictureBox(sourceWidth = 0, sourceHeight = 600, columnPx = 1242, density = 3f) shouldBe null
    }

    @Test
    @DisplayName("a column with no width has no box")
    fun noColumnNoBox() {
        pictureBox(sourceWidth = 300, sourceHeight = 600, columnPx = 0, density = 3f) shouldBe null
    }
}
