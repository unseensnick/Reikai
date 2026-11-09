package reikai.data.local

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun uni(file: File) = UniFile.fromFile(file)!!

    @Test
    fun `a committed move leaves the files only at their new place`() = runTest {
        val from = series("B", "Ch 1.cbz" to "one")
        val to = series("A")

        moveLocalFiles(listOf(FileCopy(uni(File(from, "Ch 1.cbz")), uni(to), null, "Ch 1.cbz"))) {}

        (File(to, "Ch 1.cbz").readText() to File(from, "Ch 1.cbz").exists()) shouldBe ("one" to false)
    }

    @Test
    fun `a folder moves with everything in it`() = runTest {
        val from = series("B", "Ch 1/001.png" to "page")
        val to = series("A")

        moveLocalFiles(listOf(FileCopy(uni(File(from, "Ch 1")), uni(to), null, "Vol 2"))) {}

        File(to, "Vol 2/001.png").readText() shouldBe "page"
    }

    @Test
    fun `a commit that fails leaves the originals and removes the copies`() = runTest {
        val from = series("B", "Ch 1.cbz" to "one")
        val to = series("A")

        val result = moveLocalFiles(listOf(FileCopy(uni(File(from, "Ch 1.cbz")), uni(to), null, "Ch 1.cbz"))) {
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

        moveLocalFiles(listOf(FileCopy(uni(File(from, "Ch 1.cbz")), uni(from), "A", "Ch 1.cbz"))) { error("refused") }

        (File(from, "A").exists() to File(from, "Ch 1.cbz").exists()) shouldBe (false to true)
    }
}
