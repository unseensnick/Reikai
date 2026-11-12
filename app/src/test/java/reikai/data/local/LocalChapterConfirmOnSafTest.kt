package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import reikai.data.chapter.LocalChapterTombstonesImpl
import reikai.domain.chapter.LocalChapterStillPresentException
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.SyncedChapter
import reikai.domain.library.ContentType
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import reikai.storage.FakeSafProvider
import reikai.storage.FakeSafProvider.ChildListingFault
import reikai.storage.FolderUnreadableException
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * A local refresh confirms a chapter gone before it drops the row, for both local sources, from a listing of the
 * chapter's folder it can trust whole: never from the file's own query, which a provider hiccup answers "absent".
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LocalChapterConfirmOnSafTest(private val type: Type) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var tombstones: LocalChapterTombstonesImpl

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        root = tmp.newFolder("Reikai", "local")
        val storage = mockk<StorageManager> {
            every { getLocalSourceDirectory() } answers { picked(root) }
            every { getLocalNovelsDirectory() } answers { picked(root) }
        }
        tombstones = LocalChapterTombstonesImpl(
            mockk(),
            LocalChapterFiles(context, storage),
            NoLocalVolumes,
        )
    }

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    private suspend fun confirm(urls: List<String>, listed: List<String> = emptyList()) =
        tombstones.confirm(
            type.contentType,
            isLocal = true,
            urls.mapIndexed { i, url ->
                SyncedChapter(
                    i.toLong(),
                    url,
                    i + 1.0,
                )
            },
            listed,
        )

    @Test
    fun `a chapter whose file is there is kept though its own query fails`() = runTest {
        type.chapter(root, 1)
        FakeSafProvider.failingDocuments += type.file(root, 1)

        shouldThrow<LocalChapterStillPresentException> { confirm(listOf(type.url(1))) }
    }

    @Test
    fun `a chapter whose file is gone is confirmed gone`() = runTest {
        type.chapter(root, 1)
        type.file(root, 1).deleteRecursively()

        confirm(listOf(type.url(1)))
    }

    @Test
    fun `a chapter is not confirmed gone past a folder listing that cannot be trusted`() = runTest {
        type.chapter(root, 1)
        type.file(root, 1).deleteRecursively()
        // Something else in the folder, so the failed listing (empty) and the count disagree
        File(type.file(root, 1).parentFile, "other").writeText("x")
        FakeSafProvider.faultedFolder = type.file(root, 1).parentFile
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        shouldThrow<FolderUnreadableException> { confirm(listOf(type.url(1))) }
    }

    /** A volume of a thousand chapters deleted outside the app is confirmed from one listing per folder level. */
    @Test
    fun `a thousand removed chapters are confirmed with a handful of queries`() = runTest {
        (1..1000).forEach { type.chapter(root, it) }
        type.file(root, 1).parentFile!!.deleteRecursively()
        val before = FakeSafProvider.queryCount

        confirm((1..1000).map(type::url))

        FakeSafProvider.queryCount - before shouldBeLessThanOrEqual 8
    }

    @Test
    fun `a chapter inside a container the refresh still reads is confirmed without a query`() = runTest {
        type.chapter(root, 1)
        val before = FakeSafProvider.queryCount

        confirm(listOf(type.containedUrl()), listed = listOf(type.url(2)))

        FakeSafProvider.queryCount - before shouldBe 0
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun types() = Type.entries.map { arrayOf(it) }
    }

    /** Where each type keeps chapter [i] under the local root, and the url its row carries. */
    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA) {
            override fun url(i: Int) = "Series/Vol 1/Ch $i"
            override fun containedUrl() = "Series/Vol 1/"
            override fun file(root: File, i: Int) = File(root, "Series/Vol 1/Ch $i")
            override fun chapter(root: File, i: Int) {
                File(file(root, i), "001.jpg").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
            }
        },
        NOVELS(ContentType.NOVELS) {
            override fun url(
                i: Int,
            ) = LocalNovelSource.chapterPath("Series", "Vol 1.epub", EpubChapterKey("ch$i.xhtml"))
            override fun containedUrl() = url(1)
            override fun file(root: File, i: Int) = File(root, "Series/Vol 1.epub")
            override fun chapter(root: File, i: Int) {
                file(root, i).apply { parentFile!!.mkdirs() }.takeUnless { it.exists() }?.writeText("book")
            }
        },
        ;

        abstract fun url(i: Int): String

        /** A removed chapter inside a container: a volume folder's Extras, or a chapter of a book another is listed from. */
        abstract fun containedUrl(): String

        /** The file the chapter at [i] is read from: a manga chapter's folder, a novel chapter's book. */
        abstract fun file(root: File, i: Int): File

        abstract fun chapter(root: File, i: Int)
    }
}
