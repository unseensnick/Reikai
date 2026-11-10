package reikai.novel.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/** Writing a book's file through a document provider, and the cover picture it carries. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubExportFilesTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val book = File(context.cacheDir, "Book.epub")
    private val file = mockk<UniFile> {
        every { uri } returns Uri.parse("content://$AUTHORITY/Book.epub")
        every { delete() } answers { book.delete() }
        every { length() } answers { book.length() }
    }

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(Documents::class.java, AUTHORITY)
        book.writeText("an older and much longer book")
    }

    @Test
    fun `a shorter book over an existing file leaves none of the old one behind`() = runTest {
        file.writeBookOver(context) { it.write("new".toByteArray()) }

        book.readText() shouldBe "new"
    }

    @Test
    fun `a failed export over a picked file leaves that file as it was`() = runTest {
        shouldThrow<IOException> { file.writeBookOver(context) { throw IOException("Missing chapter") } }

        book.readText() shouldBe "an older and much longer book"
    }

    @Test
    fun `a failed export into a newly created file leaves no file`() = runTest {
        book.writeText("")

        shouldThrow<IOException> { file.writeBookOver(context) { throw IOException("Missing chapter") } }

        book.exists() shouldBe false
    }

    @Test
    fun `an export leaves no copy in the cache`() = runTest {
        file.writeBookOver(context) { it.write("new".toByteArray()) }

        context.cacheDir.list()!!.filter { it.endsWith(".part") } shouldBe emptyList()
    }

    @Test
    fun `a failed write leaves no file`() = runTest {
        shouldThrow<IOException> { file.writeOrDelete(context) { throw IOException("Missing chapter") } }

        book.exists() shouldBe false
    }

    @Test
    fun `a cancelled write leaves no file`() = runTest {
        shouldThrow<CancellationException> { file.writeOrDelete(context) { throw CancellationException("stopped") } }

        book.exists() shouldBe false
    }

    private val folder = File(context.cacheDir, "exports").apply {
        deleteRecursively()
        mkdirs()
        resolve("Book.epub").writeText("the earlier book")
    }

    @Test
    fun `a failed export again leaves the earlier book`() = runTest {
        shouldThrow<IOException> {
            UniFile.fromFile(folder)!!.writeBookNamed(context, "Book.epub") { throw IOException("Missing chapter") }
        }

        folder.list()!!.toList() to folder.resolve("Book.epub").readText() shouldBe
            (listOf("Book.epub") to "the earlier book")
    }

    @Test
    fun `a finished export again replaces the earlier book`() = runTest {
        UniFile.fromFile(folder)!!.writeBookNamed(context, "Book.epub") { it.write("new".toByteArray()) }

        folder.list()!!.toList() to folder.resolve("Book.epub").readText() shouldBe (listOf("Book.epub") to "new")
    }

    @Test
    fun `an opaque cover goes in as a JPEG`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)

        coverPicture(bitmap).let { it.mime to it.bytes.take(2) } shouldBe
            ("image/jpeg" to listOf(0xFF.toByte(), 0xD8.toByte()))
    }

    @Test
    fun `a cover with transparency goes in as a PNG`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }

        coverPicture(bitmap).let { it.mime to it.bytes.take(2) } shouldBe
            ("image/png" to listOf(0x89.toByte(), 'P'.code.toByte()))
    }

    /** Writes in place on a plain "w", as the platform's document providers do since Android 10. */
    class Documents : ContentProvider() {
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val target = File(context!!.cacheDir, uri.lastPathSegment!!)
            val flags = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            return ParcelFileDescriptor.open(
                target,
                if ('t' in mode) flags or ParcelFileDescriptor.MODE_TRUNCATE else flags,
            )
        }

        override fun onCreate() = true
        override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "reikai.test.documents"
    }
}
