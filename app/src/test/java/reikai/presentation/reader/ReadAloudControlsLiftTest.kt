package reikai.presentation.reader

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadAloudControlsLiftTest {

    @Test
    fun `the controls sit above a showing readout, however tall its size draws it`() {
        readAloudControlsLift(readoutHeight = 90.dp, navigationBar = 24.dp) shouldBe 98.dp
    }

    @Test
    fun `without a readout the controls keep clear of the navigation bar`() {
        readAloudControlsLift(readoutHeight = 0.dp, navigationBar = 24.dp) shouldBe 48.dp
    }
}
