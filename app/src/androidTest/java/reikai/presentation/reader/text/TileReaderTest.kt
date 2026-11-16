package reikai.presentation.reader.text

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The decoder's own size getters take the lock a slice decode holds and throw once it is recycled, so the
 * reader answers from what it read at open: a picture drawn on the main thread never waits on a slice.
 */
@RunWith(AndroidJUnit4::class)
class TileReaderTest {

    @Test
    fun aClosedReaderStillAnswersItsSize() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(40, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val reader = checkNotNull(TileReader.of(png))

        reader.close()
        // The recycle runs on a worker, so it is given time to have happened.
        Thread.sleep(RECYCLE_WAIT_MS)

        assertEquals(40 to 300, reader.sourceWidth to reader.sourceHeight)
    }

    private companion object {
        const val RECYCLE_WAIT_MS = 500L
    }
}
