package reikai.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.storage.FakeSafProvider.ChildListingFault
import java.io.File
import java.io.IOException

/** Each storage primitive over the picked-folder path, healthy and under the faults a provider can show. */
@RunWith(RobolectricTestRunner::class)
class LocalFilesPrimitivesTest {

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

    private fun picked(folder: File = series, authority: String = FakeSafProvider.EXTERNAL_STORAGE): UniFile =
        UniFile.fromUri(context, FakeSafProvider.treeUri(folder, authority))!!

    private fun names() = series.list()!!.sorted()

    // createNew

    @Test
    fun `createNew makes an empty file under the asked name`() {
        val made = picked().createNew(context, "c.txt")

        (made.name to File(series, "c.txt").length()) shouldBe ("c.txt" to 0L)
    }

    @Test
    fun `createNew refuses a name the folder holds in another letter case`() {
        shouldThrow<FileExistsException> { picked().createNew(context, "A.CBZ") }

        names() shouldBe listOf("a.cbz", "b.cbz")
    }

    @Test
    fun `createNew refuses to create past a listing it cannot trust`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        shouldThrow<FolderUnreadableException> { picked().createNew(context, "c.txt") }

        names() shouldBe listOf("a.cbz", "b.cbz")
    }

    /** Another writer lands the name between the listing and the create: the provider dodges to "c (1).txt". */
    @Test
    fun `createNew deletes a dodged numbered file and fails`() {
        FakeSafProvider.beforeCreate = { File(series, "c.txt").writeText("theirs") }

        shouldThrow<FileExistsException> { picked().createNew(context, "c.txt") }

        (names() to File(series, "c.txt").readText()) shouldBe (listOf("a.cbz", "b.cbz", "c.txt") to "theirs")
    }

    /** The same race on a provider that answers a taken name with the existing document. */
    @Test
    fun `createNew refuses a document the provider handed back with bytes in it`() {
        FakeSafProvider.createReturnsExisting = true
        FakeSafProvider.beforeCreate = { File(series, "c.txt").writeText("theirs") }

        shouldThrow<FileExistsException> { picked().createNew(context, "c.txt") }

        File(series, "c.txt").readText() shouldBe "theirs"
    }

    @Test
    fun `createNew refuses a folder the provider handed back with entries in it`() {
        FakeSafProvider.createReturnsExisting = true
        FakeSafProvider.beforeCreate = { File(series, "Vol 2/page.jpg").apply { parentFile!!.mkdirs() }.writeText("p") }

        shouldThrow<FileExistsException> { picked().createNew(context, "Vol 2", directory = true) }

        File(series, "Vol 2/page.jpg").exists() shouldBe true
    }

    // replace

    @Test
    fun `replace writes a smaller file in the earlier one's place and leaves nothing beside it`() {
        picked().replace(context, "b.cbz") { it.write("X".toByteArray()) }

        (File(series, "b.cbz").readText() to names()) shouldBe ("X" to listOf("a.cbz", "b.cbz"))
    }

    @Test
    fun `replace keeps the earlier file when the write fails`() {
        shouldThrow<IOException> { picked().replace(context, "b.cbz") { throw IOException("cut") } }

        (File(series, "b.cbz").readText() to names()) shouldBe ("bbbb" to listOf("a.cbz", "b.cbz"))
    }

    @Test
    fun `replace writes a new name as that name`() {
        val made = picked().replace(context, "c.txt") { it.write("c".toByteArray()) }

        (made.name to File(series, "c.txt").readText()) shouldBe ("c.txt" to "c")
    }

    @Test
    fun `replace takes the earlier file's place in any letter case`() {
        picked().replace(context, "B.CBZ") { it.write("X".toByteArray()) }

        names() shouldBe listOf("B.CBZ", "a.cbz")
    }

    @Test
    fun `replace reuses the in-flight name a killed write left`() {
        File(series, "b.cbz_tmp").writeText("half of a much longer earlier attempt")

        picked().replace(context, "b.cbz") { it.write("X".toByteArray()) }

        (File(series, "b.cbz").readText() to names()) shouldBe ("X" to listOf("a.cbz", "b.cbz"))
    }

    /** Another writer takes the name between the write and the rename: theirs stays, ours goes. */
    @Test
    fun `replace of a new name taken meanwhile keeps the other writer's file and fails`() {
        FakeSafProvider.beforeRename = { File(series, "c.txt").takeUnless { it.exists() }?.writeText("theirs") }

        shouldThrow<IOException> { picked().replace(context, "c.txt") { it.write("ours".toByteArray()) } }

        (File(series, "c.txt").readText() to names()) shouldBe ("theirs" to listOf("a.cbz", "b.cbz", "c.txt"))
    }

    @Test
    fun `replace refuses to write past a listing it cannot trust`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Short(1)

        shouldThrow<FolderUnreadableException> { picked().replace(context, "b.cbz") { it.write("X".toByteArray()) } }

        (File(series, "b.cbz").readText() to names()) shouldBe ("bbbb" to listOf("a.cbz", "b.cbz"))
    }

    // rename

    @Test
    fun `rename moves the file and reports success`() {
        val file = picked().findFile("a.cbz")!!

        (file.rename("z.cbz") to names()) shouldBe (true to listOf("b.cbz", "z.cbz"))
    }

    @Test
    fun `rename onto a taken name is put back and reported failed`() {
        val file = picked().findFile("a.cbz")!!

        (file.rename("b.cbz") to names()) shouldBe (false to listOf("a.cbz", "b.cbz"))
    }

    @Test
    fun `rename changes letter case alone`() {
        val file = picked().findFile("a.cbz")!!

        (file.rename("A.cbz") to names()) shouldBe (true to listOf("A.cbz", "b.cbz"))
    }

    /** The library's own create hands back a folder already there, and a listing can hide all it holds. */
    @Test
    fun `createNew refuses a folder its parent's listing hid, however empty it reads`() {
        FakeSafProvider.listingFault = { parent ->
            if (parent == series) ChildListingFault.Short(0) else ChildListingFault.Without("Series")
        }

        shouldThrow<FileExistsException> {
            picked(File(tmp.root, "Reikai")).createNew(context, "Series", directory = true)
        }
    }

    // deletes

    @Test
    fun `deleteFile refuses a folder`() {
        shouldThrow<IllegalArgumentException> { picked(File(tmp.root, "Reikai")).findFile("Series")!!.deleteFile() }

        series.exists() shouldBe true
    }

    /** A failed document query reads as "not a folder" and as "not a file" alike; only the second refuses. */
    @Test
    fun `deleteFile refuses a folder whose own query fails`() {
        val folder = picked(File(tmp.root, "Reikai")).findFile("Series")!!
        FakeSafProvider.failingDocuments += series

        shouldThrow<IllegalArgumentException> { folder.deleteFile() }

        names() shouldBe listOf("a.cbz", "b.cbz")
    }

    @Test
    fun `deleteIfEmpty deletes nothing past a failed listing`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        (picked().deleteIfEmpty(context) to series.exists()) shouldBe (false to true)
    }

    @Test
    fun `deleteIfEmpty deletes nothing past a short listing`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Short(0)

        (picked().deleteIfEmpty(context) to series.exists()) shouldBe (false to true)
    }

    @Test
    fun `deleteIfEmpty keeps a folder with files in it`() {
        (picked().deleteIfEmpty(context) to series.exists()) shouldBe (false to true)
    }

    @Test
    fun `deleteIfEmpty deletes a folder holding only hidden markers`() {
        File(series, "a.cbz").delete()
        File(series, "b.cbz").delete()
        File(series, ".nomedia").writeText("")

        (picked().deleteIfEmpty(context) to series.exists()) shouldBe (true to false)
    }

    // copyVerified

    @Test
    fun `copyVerified copies a folder with everything in it`() {
        val target = tmp.newFolder("Reikai", "Target")

        picked(target).copyVerified(context, picked(), "Series")

        File(target, "Series").list()!!.sorted() shouldBe listOf("a.cbz", "b.cbz")
    }

    @Test
    fun `copyVerified fails and leaves no copy when the source lists short`() {
        val target = tmp.newFolder("Reikai", "Target")
        FakeSafProvider.faultedFolder = series
        FakeSafProvider.childListingFaults += ChildListingFault.Short(1)

        shouldThrow<FolderUnreadableException> { picked(target).copyVerified(context, picked(), "Series") }

        target.list()!!.toList() shouldBe emptyList()
    }

    /** On a provider whose lookup goes by listing, a failed listing used to make createFile a second file. */
    @Test
    fun `createNew on another provider refuses to create past a failed listing`() {
        FakeSafProvider.install(context, tmp.root, FakeSafProvider.OTHER)
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        shouldThrow<FolderUnreadableException> { picked(authority = FakeSafProvider.OTHER).createNew(context, "a.cbz") }

        names() shouldBe listOf("a.cbz", "b.cbz")
    }
}
