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
import java.io.File

/** The storage gateway's one-document move and its capability check, over the fake picked-folder provider. */
@RunWith(RobolectricTestRunner::class)
class LocalFilesMoveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        root = tmp.newFolder("Reikai", "local")
        File(root, "A/Vol 1/Ch 1").mkdirs()
        File(root, "A/Vol 1/Ch 1/001.jpg").writeText("page")
        File(root, "B").mkdirs()
    }

    private fun tree(): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(root))!!

    @Test
    fun `a move takes the document and all it holds into the target under its own name`() {
        val a = tree().findFile("A")!!
        val moved = a.findFile("Vol 1")!!.moveTo(context, a, tree().findFile("B")!!)

        (moved?.name to File(root, "B/Vol 1/Ch 1/001.jpg").readText()) shouldBe ("Vol 1" to "page")
    }

    @Test
    fun `a move onto a name the target holds is refused and leaves both where they were`() {
        File(root, "B/Vol 1").mkdirs()
        val a = tree().findFile("A")!!

        val moved = a.findFile("Vol 1")!!.moveTo(context, a, tree().findFile("B")!!)

        (moved to File(root, "A/Vol 1/Ch 1/001.jpg").exists()) shouldBe (null to true)
    }

    /** Nothing could find it under the dodged name, so it goes back to where it was. */
    @Test
    fun `a move a provider landed under another name is moved back and answers null`() {
        FakeSafProvider.moveDodgesTakenName = true
        File(root, "B/Vol 1").mkdirs()
        val a = tree().findFile("A")!!

        val moved = a.findFile("Vol 1")!!.moveTo(context, a, tree().findFile("B")!!)

        (moved to File(root, "A/Vol 1/Ch 1/001.jpg").exists() to File(root, "B").list()!!.toList()) shouldBe
            (null to true to listOf("Vol 1"))
    }

    @Test
    fun `the tree root is never moved`() {
        val inner = UniFile.fromUri(context, FakeSafProvider.treeUri(File(root, "A")))!!

        inner.moveTo(context, tree(), tree().findFile("B")!!) shouldBe null
    }

    @Test
    fun `a provider without moves is read as one`() {
        FakeSafProvider.supportsMove = false

        tree().findFile("A")!!.canMoveAndRename(context) shouldBe false
    }

    @Test
    fun `a provider with moves and renames is read as one`() {
        tree().findFile("A")!!.canMoveAndRename(context) shouldBe true
    }
}
