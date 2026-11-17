package reikai.presentation.reader.text

import android.graphics.drawable.ColorDrawable
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.Closeable

/**
 * A tall picture holds its source open from the moment it arrives, and only one in the chapter's text is closed
 * with the chapter, so one its render drops before it gets there has to be closed on its own.
 */
@RunWith(RobolectricTestRunner::class)
class PictureSwapsTest {

    @Test
    fun `a picture its render drops before its batch is swapped in is closed`() {
        val swaps = PictureSwaps()
        val picture = OpenPicture()
        swaps.queue(DrawableWrapper(), picture)
        swaps.take()

        swaps.closeUnswapped()

        picture.closed shouldBe true
    }

    @Test
    fun `a picture swapped in is left for the chapter to close`() {
        val swaps = PictureSwaps()
        val picture = OpenPicture()
        swaps.queue(DrawableWrapper(), picture)
        swaps.take()()

        swaps.closeUnswapped()

        picture.closed shouldBe false
    }

    private class OpenPicture : ColorDrawable(), Closeable {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
