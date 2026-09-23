package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.AdjacentLoadFailure
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** The load state is conflated, so a failure repeated word for word must still read as a new one. */
class ReaderLoadStateTest {

    @Test
    fun `a novel failure repeated word for word is a new failure`() {
        ReaderLoadState.Failed("no connection", canKeepReading = true) shouldNotBe
            ReaderLoadState.Failed("no connection", canKeepReading = true)
    }

    @Test
    fun `a manga failure repeated word for word is a new failure`() {
        AdjacentLoadFailure(5L, "no connection", fromSource = false) shouldNotBe
            AdjacentLoadFailure(5L, "no connection", fromSource = false)
    }
}
