package reikai.novel.font

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.io.InputStream

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
        var visibleMidCopy: Boolean? = null
        val source = mockk<UniFile> {
            every { length() } returns 8L
            every { openInputStream() } returns
                HalfwayStream { visibleMidCopy = File(appFiles, "fonts/Lora.ttf").exists() }
        }
        val folder = mockk<UniFile> { every { findFile("Lora.ttf") } returns source }
        val context = mockk<Context> { every { filesDir } returns appFiles }
        val storage = mockk<StorageManager> { every { getFontsDirectory() } returns folder }
        val manager = NovelFontManager(context, storage, mockk(), mockk())

        manager.localFile("Lora.ttf")

        visibleMidCopy shouldBe false
    }

    /**
     * A stored name may take the whole file-name limit, and Android names a temporary file by its prefix
     * plus a number with no shortening, so a working name built on the font's own overflows the 255
     * bytes a file system takes. The JVM shortens it instead, so the bound checked is the app's own.
     */
    @Test
    fun `a font with the longest name the folder takes is copied under a name that fits`() = runTest {
        val longName = "a".repeat(DiskUtil.MAX_FILE_NAME_BYTES - ".ttf".length) + ".ttf"
        var workingNames = emptyList<Int>()
        val source = mockk<UniFile> {
            every { length() } returns 8L
            every { openInputStream() } returns HalfwayStream {
                workingNames = File(appFiles, "fonts").list().orEmpty().map { it.toByteArray().size }
            }
        }
        val folder = mockk<UniFile> { every { findFile(longName) } returns source }
        val context = mockk<Context> { every { filesDir } returns appFiles }
        val storage = mockk<StorageManager> { every { getFontsDirectory() } returns folder }
        val manager = NovelFontManager(context, storage, mockk(), mockk())

        manager.localFile(longName)

        (workingNames.isNotEmpty() && workingNames.all { it <= DiskUtil.MAX_FILE_NAME_BYTES }) shouldBe true
    }

    /**
     * Listing sweeps the copies of fonts no longer in the folder, which must not take another thread's copy
     * still being written. A working file stands in for one, since Windows refuses to delete an open file.
     */
    @Test
    fun `listing fonts leaves a copy still being written`() = runTest {
        val working = File(appFiles, "fonts/font123${NovelFontManager.PARTIAL_SUFFIX}").apply {
            parentFile!!.mkdirs()
            writeText("half")
        }
        val font = mockk<UniFile> {
            every { name } returns "Lora.ttf"
            every { isFile } returns true
        }
        val folder = mockk<UniFile> { every { listFiles() } returns arrayOf(font) }
        val context = mockk<Context> { every { filesDir } returns appFiles }
        val storage = mockk<StorageManager> { every { getFontsDirectory() } returns folder }

        NovelFontManager(context, storage, mockk(), mockk()).installed()

        working.exists() shouldBe true
    }

    /** Both renderers read a replaced font by this; the size is in it, since a SAF provider need not report a time. */
    @Test
    fun `a copy's version is its size and its time`() {
        val copy = File(appFiles, "Lora.ttf").apply {
            writeText("a font")
            setLastModified(1_000)
        }

        copy.fontVersion() shouldBe "6-1000"
    }

    /** Eight bytes, looking at the disk through [halfway] after four, while the copy is under way. */
    private class HalfwayStream(private val halfway: () -> Unit) : InputStream() {
        private var read = 0

        override fun read(): Int {
            if (read == 4) halfway()
            return if (read++ < 8) 0 else -1
        }
    }
}
