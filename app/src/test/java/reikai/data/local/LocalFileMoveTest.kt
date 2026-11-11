package reikai.data.local

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.storage.FakeSafProvider
import reikai.storage.FakeSafProvider.ChildListingFault
import reikai.storage.replaceFile
import java.io.File

/** Moving a local series' files: copy and check, commit, and only then delete the originals. */
@RunWith(RobolectricTestRunner::class)
class LocalFileMoveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun series(name: String, vararg files: Pair<String, String>): File =
        tmp.newFolder(name).also { dir ->
            files.forEach { (path, text) -> File(dir, path).apply { parentFile!!.mkdirs() }.writeText(text) }
        }

    private val context = mockk<Context>()

    private fun uni(file: File) = UniFile.fromFile(file)!!

    @Test
    fun `a folder whose listing fails is not moved`() = runTest {
        val unreadable = mockk<UniFile>(relaxed = true) {
            every { isDirectory } returns true
            every { name } returns "Vol 1"
            every { listFiles() } returns null
        }

        moveLocalFiles(context, listOf(FileCopy(unreadable, uni(tmp.newFolder("A")), null, "Vol 1"))) {}
            .shouldBeInstanceOf<LocalMoveResult.Failed>()
    }

    @Test
    fun `a picked folder that cannot be listed whole is not moved`() = runTest {
        val from = series("B", "Vol 1/a.cbz" to "a")
        // No provider answers this picked folder's count, so its listing cannot be told whole.
        val picked = spyk(uni(File(from, "Vol 1"))) {
            every { uri } returns Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3AManga/document/primary%3AManga%2FVol",
            )
        }

        moveLocalFiles(
            ApplicationProvider.getApplicationContext(),
            listOf(FileCopy(picked, uni(tmp.newFolder("A")), null, "Vol 1")),
        ) {}.shouldBeInstanceOf<LocalMoveResult.Failed>()
    }

    @Test
    fun `a move whose folder listing came back short deletes only what it copied`() = runTest {
        val from = series("B", "Vol 1/a.cbz" to "a", "Vol 1/b.cbz" to "b")
        val volume = uni(File(from, "Vol 1"))
        val listed = volume.listFiles()!!.filter { it.name == "a.cbz" }.toTypedArray()
        val short = spyk(volume) { every { listFiles() } returns listed }

        moveLocalFiles(context, listOf(FileCopy(short, uni(tmp.newFolder("A")), null, "Vol 1"))) {}

        File(from, ".moved-Vol 1/b.cbz").readText() shouldBe "b"
    }

    /** A picked folder's provider can list short the same way twice, so the copy's own check cannot see the file. */
    @Test
    fun `a listing short the same way in the copy and the delete keeps the uncopied file, hidden`() = runTest {
        FakeSafProvider.install(ApplicationProvider.getApplicationContext(), tmp.root)
        val from = series("B", "Vol 1/a.cbz" to "a", "Vol 1/b.cbz" to "b")
        val to = tmp.newFolder("A")
        FakeSafProvider.faultedFolder = File(from, "Vol 1")
        // The copy's listing, its count, and the delete's walk: each short by the same file.
        repeat(3) { FakeSafProvider.childListingFaults += ChildListingFault.Short(1) }

        moveLocalFiles(
            ApplicationProvider.getApplicationContext(),
            listOf(FileCopy(picked(from).findFile("Vol 1")!!, picked(to), null, "Vol 1")),
        ) {}

        listOf(
            File(to, "Vol 1").list()!!.toList(),
            from.list()!!.toList(),
            File(from, ".moved-Vol 1").list()!!.toList(),
        ) shouldBe
            listOf(listOf("a.cbz"), listOf(".moved-Vol 1"), listOf("b.cbz"))
    }

    /** A provider can hide the same file at every reading, so a later listing is no second oracle either. */
    @Test
    fun `a file hidden from every listing of a moved folder is still on disk after the move`() = runTest {
        FakeSafProvider.install(ApplicationProvider.getApplicationContext(), tmp.root)
        val from = series("B", "Vol 1/a.cbz" to "a", "Vol 1/b.cbz" to "b")
        val to = tmp.newFolder("A")
        FakeSafProvider.listingFault = { ChildListingFault.Without("b.cbz") }

        moveLocalFiles(
            ApplicationProvider.getApplicationContext(),
            listOf(FileCopy(picked(from).findFile("Vol 1")!!, picked(to), null, "Vol 1")),
        ) {}

        File(from, ".moved-Vol 1/b.cbz").readText() shouldBe "b"
    }

    @Test
    fun `a file that changed after it was copied stays with the original, set aside`() = runTest {
        val from = series("B", "Vol 1/a.cbz" to "a")
        val to = series("A")

        moveLocalFiles(context, listOf(FileCopy(uni(File(from, "Vol 1")), uni(to), null, "Vol 1"))) {
            File(from, "Vol 1/a.cbz").writeText("a, and more")
        }

        File(from, ".moved-Vol 1/a.cbz").readText() shouldBe "a, and more"
    }

    /** Some providers answer a create of a taken name with the folder already there, which the move did not make. */
    @Test
    fun `a failed move never deletes a folder it found already there, whatever its query answered`() = runTest {
        FakeSafProvider.install(ApplicationProvider.getApplicationContext(), tmp.root)
        val from = series("B", "Ch 1.cbz" to "one")
        val to = series("A", "Vol 1/theirs.cbz" to "theirs")
        FakeSafProvider.failingDocuments += File(to, "Vol 1")
        FakeSafProvider.createReturnsExisting = true

        moveLocalFiles(
            ApplicationProvider.getApplicationContext(),
            listOf(FileCopy(picked(from).findFile("Ch 1.cbz")!!, picked(to), "Vol 1", "Ch 1.cbz")),
        ) { error("database refused") }

        File(to, "Vol 1/theirs.cbz").readText() shouldBe "theirs"
    }

    /** The rows have moved by then, so a throw would lose the Undo of a move that went through. */
    @Test
    fun `an original whose query fails once the move has committed is left behind, not thrown over`() = runTest {
        FakeSafProvider.install(ApplicationProvider.getApplicationContext(), tmp.root)
        val from = series("B", "Ch 1.cbz" to "one")
        val to = tmp.newFolder("A")
        var queriesSinceCommit = -1
        FakeSafProvider.onCall = { call, target ->
            if (call == "queryDocument" && target.name == "Ch 1.cbz" && target.parentFile == from) {
                if (queriesSinceCommit >= 0 && ++queriesSinceCommit == 2) error("the provider died")
            }
        }

        val moved = runCatching {
            moveLocalFiles(
                ApplicationProvider.getApplicationContext(),
                listOf(FileCopy(picked(from).findFile("Ch 1.cbz")!!, picked(to), null, "Ch 1.cbz")),
            ) { queriesSinceCommit = 0 }
        }

        moved.getOrNull().shouldBeInstanceOf<LocalMoveResult.Moved>()
    }

    @Test
    fun `a second folder moved away from the same name is set aside beside the first`() = runTest {
        FakeSafProvider.install(ApplicationProvider.getApplicationContext(), tmp.root)
        val from = series("B", "Vol 1/Ch 1/001.png" to "p", ".moved-Vol 1/Ch 0/kept.png" to "k")
        val to = tmp.newFolder("A")

        moveLocalFiles(
            ApplicationProvider.getApplicationContext(),
            listOf(FileCopy(picked(from).findFile("Vol 1")!!, picked(to), null, "Vol 1")),
        ) {}

        listOf(
            from.list()!!.count { it.startsWith(".moved-Vol 1") },
            from.list()!!.count { !it.startsWith(".moved-") },
            File(from, ".moved-Vol 1/Ch 0/kept.png").readText(),
        ) shouldBe listOf(2, 0, "k")
    }

    private fun picked(folder: File): UniFile =
        UniFile.fromUri(ApplicationProvider.getApplicationContext(), FakeSafProvider.treeUri(folder))!!

    @Test
    fun `a replacement that cannot take the name leaves the old file where it was`() {
        val dir = series("S", "Vol 1.epub" to "old")
        val replacement = mockk<UniFile> {
            every { name } returns "Vol 1.epub.part"
            every { renameTo(any()) } returns false
        }

        replaceFile(uni(File(dir, "Vol 1.epub")), replacement, "Vol 1.epub", "Vol 1.epub.old")

        File(dir, "Vol 1.epub").readText() shouldBe "old"
    }

    @Test
    fun `a committed move leaves the files only at their new place`() = runTest {
        val from = series("B", "Ch 1.cbz" to "one")
        val to = series("A")

        moveLocalFiles(context, listOf(FileCopy(uni(File(from, "Ch 1.cbz")), uni(to), null, "Ch 1.cbz"))) {}

        (File(to, "Ch 1.cbz").readText() to File(from, "Ch 1.cbz").exists()) shouldBe ("one" to false)
    }

    @Test
    fun `a folder moves with everything in it`() = runTest {
        val from = series("B", "Ch 1/001.png" to "page")
        val to = series("A")

        moveLocalFiles(context, listOf(FileCopy(uni(File(from, "Ch 1")), uni(to), null, "Vol 2"))) {}

        File(to, "Vol 2/001.png").readText() shouldBe "page"
    }

    @Test
    fun `a commit that fails leaves the originals and removes the copies`() = runTest {
        val from = series("B", "Ch 1.cbz" to "one")
        val to = series("A")

        val result = moveLocalFiles(context, listOf(FileCopy(uni(File(from, "Ch 1.cbz")), uni(to), null, "Ch 1.cbz"))) {
            error("database refused")
        }

        result.shouldBeInstanceOf<LocalMoveResult.Failed>()
        (File(from, "Ch 1.cbz").exists() to File(to, "Ch 1.cbz").exists()) shouldBe (true to false)
    }

    @Test
    fun `a name already taken at the new place fails before anything moves`() = runTest {
        val from = series("B", "Ch 1.cbz" to "one", "Ch 2.cbz" to "two")
        val to = series("A", "Ch 2.cbz" to "theirs")
        var committed = false

        moveLocalFiles(
            context,
            listOf(
                FileCopy(uni(File(from, "Ch 1.cbz")), uni(to), null, "Ch 1.cbz"),
                FileCopy(uni(File(from, "Ch 2.cbz")), uni(to), null, "Ch 2.cbz"),
            ),
        ) { committed = true }

        listOf(committed, File(to, "Ch 1.cbz").exists(), File(to, "Ch 2.cbz").readText() == "theirs") shouldBe
            listOf(false, false, true)
    }

    @Test
    fun `a copy into a folder it had to make takes the folder away again when the commit fails`() = runTest {
        val from = series("A", "Ch 1.cbz" to "one")

        moveLocalFiles(context, listOf(FileCopy(uni(File(from, "Ch 1.cbz")), uni(from), "A", "Ch 1.cbz"))) {
            error("refused")
        }

        (File(from, "A").exists() to File(from, "Ch 1.cbz").exists()) shouldBe (false to true)
    }

    @Test
    fun `a hidden marker file in a folder is left behind with the original`() = runTest {
        val from = series("B", "Vol 1/Ch 1.cbz" to "one", "Vol 1/.nomedia" to "")
        val to = series("A")

        moveLocalFiles(context, listOf(FileCopy(uni(File(from, "Vol 1")), uni(to), null, "Vol 1"))) {}

        (File(to, "Vol 1/Ch 1.cbz").exists() to File(to, "Vol 1/.nomedia").exists()) shouldBe (true to false)
    }
}
