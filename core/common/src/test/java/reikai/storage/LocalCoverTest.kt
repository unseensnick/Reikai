package reikai.storage

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.system.ImageUtil
import java.io.InputStream

class LocalCoverTest {

    // The content sniff decodes through a native library the JVM does not load; these files are text.
    @BeforeEach
    fun noNativeSniff() {
        mockkObject(ImageUtil)
        every { ImageUtil.findImageType(any<() -> InputStream>()) } returns null
    }

    @AfterEach
    fun restore() = unmockkObject(ImageUtil)

    private fun file(name: String) = mockk<UniFile> {
        every { this@mockk.name } returns name
        every { isFile } returns true
        every { openInputStream() } answers { "not a picture".byteInputStream() }
    }

    @Test
    fun `a cover file that is not a picture is passed over`() {
        val picture = file("Cover.jpg")

        listOf(file("cover.txt"), picture).localCover() shouldBe picture
    }

    /** A partly written JPEG still sniffs as one. */
    @Test
    fun `a cover a killed write left in flight is passed over`() {
        every { ImageUtil.findImageType(any<() -> InputStream>()) } returns ImageUtil.ImageType.JPEG

        listOf(file("cover.jpg_tmp")).localCover() shouldBe null
    }
}
