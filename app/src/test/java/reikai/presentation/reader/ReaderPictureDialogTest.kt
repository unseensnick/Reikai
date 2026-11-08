package reikai.presentation.reader

import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.coil.NovelImage

/** Saving a chapter's picture: under a name of its own, and finished when the viewer closes mid-save. */
class ReaderPictureDialogTest {

    @Test
    fun `two pictures of one chapter save under different names`() {
        chapterPictureName("Book", "Chapter 1", NovelImage("https://a.test/1.png", "src")) shouldNotBe
            chapterPictureName("Book", "Chapter 1", NovelImage("https://a.test/2.png", "src"))
    }

    @Test
    fun `saving one picture again finds the same name`() {
        chapterPictureName("Book", "Chapter 1", "data:image/png;base64,AA") shouldBe
            chapterPictureName("Book", "Chapter 1", "data:image/png;base64,AA")
    }

    @Test
    fun `a long title is cut before the picture's part of the name`() {
        val model = NovelImage("https://a.test/1.png", "src")
        val suffix = chapterPictureName(null, null, model).substringAfterLast(" - ")

        chapterPictureName("x".repeat(400), "Chapter 1", model) shouldEndWith " - $suffix"
    }

    @Test
    fun `a long title still fits a file name`() {
        val name = chapterPictureName("x".repeat(400), "Chapter 1", NovelImage("https://a.test/1.png", "src"))

        name.byteSize() shouldBeLessThanOrEqual DiskUtil.MAX_FILE_NAME_BYTES
    }

    @Test
    fun `closing the viewer mid-save still writes the picture`() = runTest {
        val viewer = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val app = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val loaded = CompletableDeferred<Unit>()
        var written = false
        viewer.launchOutliving(app, write = { loaded.await().also { written = true } }, then = {})
        runCurrent()

        viewer.cancel()
        loaded.complete(Unit)
        advanceUntilIdle()

        written shouldBe true
    }
}
