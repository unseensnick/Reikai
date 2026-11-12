package reikai.novel.font

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.storage.FakeSafProvider
import reikai.storage.FakeSafProvider.ChildListingFault
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/** The fonts folder on a picked folder, where a create hands back the font already of that name. */
@RunWith(RobolectricTestRunner::class)
class NovelFontManagerOnSafTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var fonts: File
    private lateinit var manager: NovelFontManager

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        fonts = tmp.newFolder("Reikai", "fonts")
        File(fonts, "Lora.ttf").writeBytes(SFNT + ByteArray(40))
        val storage = mockk<StorageManager> {
            every { getFontsDirectory() } answers { FakeSafProvider.picked(context, fonts) }
        }
        manager = NovelFontManager(context, storage, mockk(), mockk())
    }

    private fun pickedFont(bytes: ByteArray): Uri {
        val picked = File(tmp.root, "Lora.ttf").apply { writeBytes(bytes) }
        return Uri.fromFile(picked)
    }

    /** Importing a font of a name already there used to truncate that font before the new bytes landed. */
    @Test
    fun `importing a font over one of the same name while the folder cannot be listed keeps the one there`() = runTest {
        FakeSafProvider.faultedFolder = fonts
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        val result = manager.import(pickedFont(SFNT + ByteArray(4)))

        (result.isFailure to File(fonts, "Lora.ttf").length()) shouldBe (true to 44L)
    }

    @Test
    fun `importing a font over one of the same name replaces it whole`() = runTest {
        manager.import(pickedFont(SFNT + ByteArray(4)))

        (File(fonts, "Lora.ttf").length() to fonts.list()!!.toList()) shouldBe (8L to listOf("Lora.ttf"))
    }

    private companion object {
        /** The TrueType header the import checks for. */
        val SFNT = byteArrayOf(0, 1, 0, 0)
    }
}
