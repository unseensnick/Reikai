package reikai.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.text.TextUtils
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File
import java.io.InputStream

/** A local series' cover, as both local sources write it, over a real folder. */
class WriteLocalCoverTest {

    @TempDir
    lateinit var folder: File

    private val context = mockk<Context>()

    // unifile's file-backed implementation and the gateway ask android.jar statics the JVM test jar throws on.
    @BeforeEach
    fun setUp() {
        mockkStatic(MediaScannerConnection::class, TextUtils::class, Uri::class, DocumentsContract::class)
        every { MediaScannerConnection.scanFile(any(), any(), any(), any()) } just runs
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)
        every { DocumentsContract.isTreeUri(any()) } returns false
        // The content sniff decodes natively; here every sniffed file reads as a JPEG, as a partly written one does.
        mockkObject(ImageUtil)
        every { ImageUtil.findImageType(any<() -> InputStream>()) } returns ImageUtil.ImageType.JPEG
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(MediaScannerConnection::class, TextUtils::class, Uri::class, DocumentsContract::class)
        unmockkObject(ImageUtil)
    }

    private fun write(bytes: ByteArray) = writeLocalCover(context, UniFile.fromFile(folder)!!, bytes.inputStream())

    @Test
    fun `a new cover is written as cover_jpg`() {
        write(byteArrayOf(1, 2, 3))

        File(folder, "cover.jpg").readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    /** A partly written JPEG still sniffs as one, and would take every later write. */
    @Test
    fun `a cover a killed write left in flight gives way to a new one`() {
        File(folder, "cover.jpg_tmp").writeBytes(ByteArray(64) { 7 })

        write(byteArrayOf(1))

        folder.list()!!.sorted() shouldBe listOf(".nomedia", "cover.jpg")
    }

    @Test
    fun `a failed write keeps the cover the series had`() {
        write(ByteArray(3) { 7 })
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("gone")
        }

        runCatching { writeLocalCover(context, UniFile.fromFile(folder)!!, failing) }

        (File(folder, "cover.jpg").length() to folder.list()!!.sorted()) shouldBe
            (3L to listOf(".nomedia", "cover.jpg"))
    }

    @Test
    fun `the series folder is kept out of the gallery`() {
        write(byteArrayOf(1))

        File(folder, ".nomedia").exists() shouldBe true
    }
}
