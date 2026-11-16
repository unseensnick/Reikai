package reikai.novel.font

import android.content.Context
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelFontManagerTest {

    @TempDir
    lateinit var appFiles: File

    /** The app-private copy is what the reader draws from when storage cannot answer, so an
     *  unreachable folder must not read as one the user emptied. */
    @Test
    fun `listing fonts while storage is unreachable keeps the copies the reader falls back to`() = runTest {
        val mirrored = File(appFiles, "fonts/Lora.ttf").apply {
            parentFile!!.mkdirs()
            writeText("font")
        }
        val context = mockk<Context> { every { filesDir } returns appFiles }
        val storage = mockk<StorageManager> { every { getFontsDirectory() } returns null }

        NovelFontManager(context, storage, mockk(), mockk()).installed()

        mirrored.exists() shouldBe true
    }

    /** The picker's preview and the renderer can copy one font at once, and a face read half-written is lost. */
    @Test
    fun `a font being copied is not where the reader reads it until the copy is whole`() = runTest {
        val halfway = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val source = mockk<UniFile> {
            every { length() } returns 8L
            every { openInputStream() } returns StalledStream(halfway, finish)
        }
        val folder = mockk<UniFile> { every { findFile("Lora.ttf") } returns source }
        val context = mockk<Context> { every { filesDir } returns appFiles }
        val storage = mockk<StorageManager> { every { getFontsDirectory() } returns folder }
        val manager = NovelFontManager(context, storage, mockk(), mockk())

        val copy = launch(Dispatchers.IO) { manager.localFile("Lora.ttf") }
        withContext(Dispatchers.IO) { halfway.await(5, TimeUnit.SECONDS) }
        val visibleMidCopy = File(appFiles, "fonts/Lora.ttf").exists()
        finish.countDown()
        copy.join()

        visibleMidCopy shouldBe false
    }

    /** Eight bytes, stopping after four until told to go on. */
    private class StalledStream(private val halfway: CountDownLatch, private val finish: CountDownLatch) :
        InputStream() {
        private var read = 0

        override fun read(): Int {
            if (read == 4) {
                halfway.countDown()
                finish.await(5, TimeUnit.SECONDS)
            }
            return if (read++ < 8) 0 else -1
        }
    }
}
