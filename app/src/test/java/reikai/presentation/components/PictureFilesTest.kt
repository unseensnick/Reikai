package reikai.presentation.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** What Save and Share in the picture viewer write, for a cover and a chapter's picture alike. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureFilesTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    // A bitmap as the coil model, since Robolectric cannot decode an image file on every desktop.
    @Test
    fun `a picture with transparency is saved as a PNG`() = runTest {
        val picture = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
        val saved = slot<Image>()
        val imageSaver = mockk<ImageSaver> { every { save(capture(saved)) } returns Uri.EMPTY }

        savePicture(context, imageSaver, picture, "Clear", forSharing = false)

        saved.captured.data().use { it.readNBytes(2).toList() } shouldBe listOf(0x89.toByte(), 'P'.code.toByte())
    }
}
