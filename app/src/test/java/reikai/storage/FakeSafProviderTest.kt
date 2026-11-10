package reikai.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.storage.FakeSafProvider.ChildListingFault
import reikai.util.openTruncatingOutputStream
import java.io.File

/** What unifile's picked-folder path does over a provider, healthy and under each listing fault. */
@RunWith(RobolectricTestRunner::class)
class FakeSafProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var series: File

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        series = tmp.newFolder("Reikai", "Series")
        File(series, "a.cbz").writeText("aaa")
        File(series, "b.cbz").writeText("bbbb")
    }

    private fun picked(authority: String = FakeSafProvider.EXTERNAL_STORAGE): UniFile =
        UniFile.fromUri(context, FakeSafProvider.treeUri(File(tmp.root, "Reikai"), authority))!!

    private fun seriesDir(): UniFile = picked().findFile("Series")!!

    @Test
    fun `the picked folder is a tree document, not a raw file`() {
        picked().javaClass.simpleName shouldBe "TreeDocumentFile"
    }

    @Test
    fun `a listing names the folder's files`() {
        seriesDir().listFiles()!!.map { it.name to it.length() } shouldBe listOf("a.cbz" to 3L, "b.cbz" to 4L)
    }

    @Test
    fun `a file is found by name and read back`() {
        seriesDir().findFile("b.cbz")!!.openInputStream().use { it.readBytes().decodeToString() } shouldBe "bbbb"
    }

    @Test
    fun `a new file and folder are created and listed`() {
        val dir = seriesDir()
        dir.createFile("c.txt")!!.openOutputStream().use { it.write("c".toByteArray()) }
        dir.createDirectory("Vol 2")!!.isDirectory shouldBe true

        File(series, "c.txt").readText() to File(series, "Vol 2").isDirectory shouldBe ("c" to true)
    }

    @Test
    fun `createFile hands back the existing file instead of a second one`() {
        val dir = seriesDir()

        val made = dir.createFile("a.cbz")!!

        (made.length() to series.list()!!.sorted()) shouldBe (3L to listOf("a.cbz", "b.cbz"))
    }

    @Test
    fun `openOutputStream writes in place, leaving the old tail`() {
        seriesDir().findFile("b.cbz")!!.openOutputStream().use { it.write("X".toByteArray()) }

        File(series, "b.cbz").readText() shouldBe "Xbbb"
    }

    @Test
    fun `the truncating stream replaces the whole file`() {
        seriesDir().findFile("b.cbz")!!.openTruncatingOutputStream(context).use { it.write("X".toByteArray()) }

        File(series, "b.cbz").readText() shouldBe "X"
    }

    @Test
    fun `an appending stream adds to the end`() {
        seriesDir().findFile("b.cbz")!!.openOutputStream(true).use { it.write("X".toByteArray()) }

        File(series, "b.cbz").readText() shouldBe "bbbbX"
    }

    @Test
    fun `a rename moves the file and the handle follows it`() {
        val file = seriesDir().findFile("a.cbz")!!

        val renamed = file.renameTo("z.cbz")

        (renamed to file.name to series.list()!!.sorted()) shouldBe (true to "z.cbz" to listOf("b.cbz", "z.cbz"))
    }

    @Test
    fun `a rename onto a taken name is refused`() {
        seriesDir().findFile("a.cbz")!!.renameTo("b.cbz") shouldBe false
    }

    @Test
    fun `deleting a folder takes everything in it`() {
        seriesDir().delete() shouldBe true

        series.exists() shouldBe false
    }

    @Test
    fun `a failed child query lists as an empty folder`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        seriesDir().listFiles()!!.size shouldBe 0
    }

    @Test
    fun `a null cursor lists as an empty folder`() {
        FakeSafProvider.childListingFaults += ChildListingFault.NullCursor

        seriesDir().listFiles()!!.size shouldBe 0
    }

    @Test
    fun `a short answer lists as a smaller folder`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Short(1)

        seriesDir().listFiles()!!.map { it.name } shouldBe listOf("a.cbz")
    }

    @Test
    fun `a still-loading answer lists as the whole folder`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Loading

        seriesDir().listFiles()!!.size shouldBe 2
    }

    @Test
    fun `on the device's provider findFile answers by address, so a failed listing cannot hide a file`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        seriesDir().findFile("a.cbz")!!.length() shouldBe 3L
    }

    @Test
    fun `on another provider findFile answers by listing, so a failed listing makes createFile a second file`() {
        FakeSafProvider.install(context, tmp.root, FakeSafProvider.OTHER)
        val dir = picked(FakeSafProvider.OTHER).findFile("Series")!!
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        val made = dir.createFile("a.cbz")!!

        (made.name to series.list()!!.sorted()) shouldBe ("a (1).cbz" to listOf("a (1).cbz", "a.cbz", "b.cbz"))
    }
}
