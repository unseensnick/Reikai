package reikai.presentation.reader.text

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.coil.NovelImage
import reikai.novel.content.NovelChapterAddress
import reikai.novel.local.LocalNovelSource

/** Robolectric, because the getter takes a real Context; the loads it queues are never run here. */
@RunWith(RobolectricTestRunner::class)
class NovelImageGetterLocalPictureTest {

    private val address = LocalNovelSource.pictureAddress("Saga", "Book.epub", "OEBPS/plate.png", 1)

    @Test
    fun `a local book's picture is queued to load like a fetched one, not drawn as a failure`() {
        val getter = NovelImageGetter(
            context = ApplicationProvider.getApplicationContext(),
            scope = CoroutineScope(StandardTestDispatcher()),
            contentWidth = 1000,
            maxPictureHeight = null,
            sourceId = LocalNovelSource.ID,
            textSizePx = 16f,
            textColor = { Color.BLACK },
            resolveView = { null },
            onImagesLanded = { _, _, _ -> },
        )
        getter.getDrawable(address)

        getter.startLoading() shouldBe true
    }

    @Test
    fun `resolving a chapter's pictures against its base keeps a local book's address`() {
        NovelChapterAddress.absolute("https://site.example/chapter", address) shouldBe address
    }

    @Test
    fun `the picture viewer opens a local book's picture with its source`() {
        NovelPictureGesture.modelOf(address, LocalNovelSource.ID) shouldBe NovelImage(address, LocalNovelSource.ID)
    }
}
