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
import java.io.File

/** The local-file rules over the picked-folder path itself: no listing fault is trusted. */
@RunWith(RobolectricTestRunner::class)
class LocalFilesOnSafTest {

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

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

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
}
