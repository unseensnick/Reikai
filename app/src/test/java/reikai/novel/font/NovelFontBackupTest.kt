package reikai.novel.font

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * What the font manager hands a backup and takes back from one: the fonts in the folder, whole, and nothing that is not
 * one. The backup file itself carries them through NovelFontRestoreTest.
 */
@RunWith(RobolectricTestRunner::class)
class NovelFontBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val preferences =
        NovelPreferences(AndroidPreferenceStore(context.getSharedPreferences("fonts", Context.MODE_PRIVATE)))

    private fun manager(fonts: File) = NovelFontManager(
        context,
        mockk<StorageManager> { every { getFontsDirectory() } answers { UniFile.fromFile(fonts) } },
        mockk(),
        preferences,
    )

    /** A leading dot is a name the folder takes, so the font comes back under it and the setting still finds it. */
    @Test
    fun `a font restores under its own name when the folder takes it`() = runTest {
        val after = tmp.newFolder("after")
        preferences.readerFontFamily().set(".Literata.ttf")

        manager(after).restore(".Literata.ttf", FONT)

        after.list()!!.toList() to preferences.readerFontFamily().get() shouldBe
            (listOf(".Literata.ttf") to ".Literata.ttf")
    }

    @Test
    fun `a font whose name cannot be a file restores cleaned and the setting follows it`() = runTest {
        val after = tmp.newFolder("after")
        preferences.readerFontFamily().set("Lit/erata.ttf")

        manager(after).restore("Lit/erata.ttf", FONT)

        after.list()!!.toList() to preferences.readerFontFamily().get() shouldBe
            (listOf("Lit_erata.ttf") to "Lit_erata.ttf")
    }

    @Test
    fun `an added font comes back whole and nothing else in the folder rides along`() = runTest {
        val before = tmp.newFolder("before")
        val after = tmp.newFolder("after")
        File(before, "Literata.ttf").writeBytes(FONT)
        File(before, "notes.txt").writeText("not a font")

        manager(before).backedUp().collect { (name, bytes) -> manager(after).restore(name, bytes) }

        after.listFiles()!!.associate { it.name to it.readBytes().toList() } shouldBe
            mapOf("Literata.ttf" to FONT.toList())
    }

    @Test
    fun `only the fonts in the folder go into a backup`() = runTest {
        val fonts = tmp.newFolder("fonts")
        File(fonts, "Literata.ttf").writeBytes(FONT)
        File(fonts, "notes.txt").writeText("not a font")

        manager(fonts).backedUp().toList().map { it.first } shouldBe listOf("Literata.ttf")
    }

    @Test
    fun `a file that is not a font is refused on restore`() = runTest {
        val after = tmp.newFolder("after")

        manager(after).restore("Evil.ttf", "<html>".toByteArray()) to after.list()!!.toList() shouldBe
            (false to emptyList<String>())
    }

    /** The bytes start like a TrueType font; the name says it is not one. */
    @Test
    fun `a file not named as a font is refused on restore`() = runTest {
        val after = tmp.newFolder("after")

        manager(after).restore("x.html", FONT) to after.list()!!.toList() shouldBe (false to emptyList<String>())
    }

    /** The reader reads a value without .ttf or .otf as a bundled font, so a shortened name must keep it. */
    @Test
    fun `a font whose name is too long for a file restores shortened with its extension`() = runTest {
        val after = tmp.newFolder("after")
        val name = "a".repeat(260) + ".ttf"
        preferences.readerFontFamily().set(name)

        manager(after).restore(name, FONT)

        val stored = "a".repeat(236) + ".ttf"
        after.list()!!.toList() to preferences.readerFontFamily().get() shouldBe (listOf(stored) to stored)
    }

    @Test
    fun `restoring a font under a cleaned name leaves a setting that named another font`() = runTest {
        val after = tmp.newFolder("after")
        preferences.readerFontFamily().set("Other.ttf")

        manager(after).restore("Lit/erata.ttf", FONT)

        preferences.readerFontFamily().get() shouldBe "Other.ttf"
    }

    @Test
    fun `a font already there at the same size is left as it is`() = runTest {
        val after = tmp.newFolder("after")
        val there = ByteArray(FONT.size) { 7 }
        File(after, "Literata.ttf").writeBytes(there)

        manager(after).restore("Literata.ttf", FONT)

        File(after, "Literata.ttf").readBytes().toList() shouldBe there.toList()
    }

    private companion object {
        // A TrueType header followed by a body; the manager checks the header only.
        val FONT = byteArrayOf(0, 1, 0, 0) + ByteArray(64) { it.toByte() }
    }
}
