package reikai.novel.font

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/** A reader font the user added goes into a backup whole and comes back on another device, so its setting finds it. */
@RunWith(RobolectricTestRunner::class)
class NovelFontBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun manager(fonts: File) = NovelFontManager(
        context,
        mockk<StorageManager> { every { getFontsDirectory() } answers { UniFile.fromFile(fonts) } },
        mockk(),
    )

    @Test
    fun `an added font comes back whole and nothing else in the folder rides along`() = runTest {
        val before = tmp.newFolder("before")
        val after = tmp.newFolder("after")
        File(before, "Literata.ttf").writeBytes(FONT)
        File(before, "notes.txt").writeText("not a font")

        manager(before).backedUp().forEach { (name, bytes) -> manager(after).restore(name, bytes) }

        after.listFiles()!!.associate { it.name to it.readBytes().toList() } shouldBe
            mapOf("Literata.ttf" to FONT.toList())
    }

    @Test
    fun `only the fonts in the folder go into a backup`() = runTest {
        val fonts = tmp.newFolder("fonts")
        File(fonts, "Literata.ttf").writeBytes(FONT)
        File(fonts, "notes.txt").writeText("not a font")

        manager(fonts).backedUp().map { it.first } shouldBe listOf("Literata.ttf")
    }

    @Test
    fun `a file that is not a font is refused on restore`() = runTest {
        val after = tmp.newFolder("after")

        manager(after).restore("Evil.ttf", "<html>".toByteArray()) to after.list()!!.toList() shouldBe
            (false to emptyList<String>())
    }

    private companion object {
        // A TrueType header followed by a body; the manager checks the header only.
        val FONT = byteArrayOf(0, 1, 0, 0) + ByteArray(64) { it.toByte() }
    }
}
