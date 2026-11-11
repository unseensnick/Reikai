package reikai.data.dedupe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.storage.FakeSafProvider
import reikai.storage.FakeSafProvider.ChildListingFault
import java.io.File

/** The folder merge over a picked folder, where a listing can come back short without failing. */
@RunWith(RobolectricTestRunner::class)
class DownloadFolderMergeOnSafTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var from: File
    private lateinit var into: File

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        mockkObject(DiskUtil)
        every { DiskUtil.getAvailableStorageSpace(any<UniFile>()) } returns Long.MAX_VALUE
        from = tmp.newFolder("Reikai", "Old")
        into = tmp.newFolder("Reikai", "New")
        File(from, "Ch 1/001.jpg").apply { parentFile!!.mkdirs() }.writeText("one")
        File(from, "Ch 1/002.jpg").writeText("two")
    }

    @After
    fun tearDown() = unmockkObject(DiskUtil)

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    /** A short listing of the chapter fails the copy; the chapter is never deleted behind a short copy. */
    @Test
    fun `a chapter whose folder lists short is left in place, not deleted behind a short copy`() {
        FakeSafProvider.faultedFolder = File(from, "Ch 1")
        FakeSafProvider.childListingFaults +=
            listOf(ChildListingFault.Healthy, ChildListingFault.Healthy, ChildListingFault.Short(1))

        val carry = DownloadFolderMerge.merge(context, picked(from), picked(into))

        (carry.finished to File(from, "Ch 1").list()!!.sorted() to into.list()!!.toList()) shouldBe
            (false to listOf("001.jpg", "002.jpg") to emptyList())
    }

    /** A downloaded chapter can be the only copy left, and a provider can hide the same page at every reading. */
    @Test
    fun `a page hidden from every listing of a merged chapter stays on disk, set aside`() {
        FakeSafProvider.listingFault = { ChildListingFault.Without("002.jpg") }

        DownloadFolderMerge.merge(context, picked(from), picked(into))

        val hidden = tmp.root.walk().single { it.name == "002.jpg" }
        listOf(File(into, "Ch 1").list()!!.toList(), hidden.readText(), hidden.parentFile!!.name) shouldBe
            listOf(listOf("001.jpg"), "two", ".moved-Ch 1")
    }

    @Test
    fun `a chapter listed whole moves with every page`() {
        val carry = DownloadFolderMerge.merge(context, picked(from), picked(into))

        (carry.finished to File(into, "Ch 1").list()!!.sorted() to from.walk().filter { it.isFile }.count()) shouldBe
            (true to listOf("001.jpg", "002.jpg") to 0)
    }
}
