package reikai.novel.font

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

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

        NovelFontManager(context, storage, mockk()).installed()

        mirrored.exists() shouldBe true
    }
}
