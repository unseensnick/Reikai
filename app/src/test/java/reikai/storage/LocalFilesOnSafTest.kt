package reikai.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.FileCopy
import reikai.data.local.LocalMoveResult
import reikai.data.local.moveLocalFiles
import reikai.data.local.purgeParked
import reikai.storage.FakeSafProvider.ChildListingFault
import java.io.File

/** The local-file rules over the picked-folder path itself: no listing fault is trusted, and no fault loses a file. */
@RunWith(RobolectricTestRunner::class)
class LocalFilesOnSafTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var series: File
    private lateinit var target: File

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        series = tmp.newFolder("Reikai", "Series")
        target = tmp.newFolder("Reikai", "Target")
        File(series, "a.cbz").writeText("aaa")
        File(series, "b.cbz").writeText("bbbb")
    }

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    private fun names(dir: File) = dir.list()!!.sorted()

    /** The series folder as a move reaches it: a child of the picked root, never the picked root itself. */
    private fun source() = picked(File(tmp.root, "Reikai")).findFile("Series")!!

    @Test
    fun `a whole listing is trusted`() {
        picked(series).listOrNull(context)!!.map { it.name } shouldBe listOf("a.cbz", "b.cbz")
    }

    @Test
    fun `a failed child query is not trusted`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        picked(series).listOrNull(context) shouldBe null
    }

    @Test
    fun `a short child listing is not trusted`() {
        FakeSafProvider.childListingFaults += ChildListingFault.Short(1)

        picked(series).listOrNull(context) shouldBe null
    }

    @Test
    fun `a null cursor is not trusted`() {
        FakeSafProvider.childListingFaults += ChildListingFault.NullCursor

        picked(series).listOrNull(context) shouldBe null
    }

    @Test
    fun `a folder still loading is not trusted`() {
        FakeSafProvider.childListingFaults += listOf(ChildListingFault.Loading, ChildListingFault.Loading)

        picked(series).listOrNull(context) shouldBe null
    }

    @Test
    fun `a move whose source lists short while copying fails and leaves the source whole`() = runTest {
        FakeSafProvider.faultedFolder = series
        FakeSafProvider.childListingFaults += ChildListingFault.Short(1)

        val result = moveLocalFiles(context, listOf(FileCopy(source(), picked(target), null, "Series"))) {}

        result.shouldBeInstanceOf<LocalMoveResult.Failed>()
        (names(series) to names(target)) shouldBe (listOf("a.cbz", "b.cbz") to emptyList())
    }

    @Test
    fun `a move whose source lists short while deleting keeps what was never copied, parked`() = runTest {
        FakeSafProvider.faultedFolder = series
        // The copy's two queries read the folder whole; the delete's first one comes back short.
        FakeSafProvider.childListingFaults +=
            listOf(ChildListingFault.Healthy, ChildListingFault.Healthy, ChildListingFault.Short(1))

        val result = moveLocalFiles(context, listOf(FileCopy(source(), picked(target), null, "Series"))) {}

        val parked = (result as LocalMoveResult.Moved).parked.map { it.name }
        listOf(
            result.leftBehind,
            parked,
            names(File(tmp.root, "Reikai/.moved-Series")),
            names(File(target, "Series")),
        ) shouldBe
            listOf(emptyList(), listOf(".moved-Series"), listOf("b.cbz"), listOf("a.cbz", "b.cbz"))
    }

    @Test
    fun `a healthy move copies everything, parks the source and purges it on the next reading`() = runTest {
        val result = moveLocalFiles(context, listOf(FileCopy(source(), picked(target), null, "Series"))) {}
        val parked = names(File(tmp.root, "Reikai"))
        purgeParked(context, (result as LocalMoveResult.Moved).parked)

        listOf(result.leftBehind, parked, names(File(tmp.root, "Reikai")), names(File(target, "Series"))) shouldBe
            listOf(emptyList(), listOf(".moved-Series", "Target"), listOf("Target"), listOf("a.cbz", "b.cbz"))
    }
}
