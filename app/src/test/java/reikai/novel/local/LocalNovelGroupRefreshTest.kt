package reikai.novel.local

import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.VolumeOwner
import reikai.novel.host.SourceNovel
import reikai.novel.local.epub.opf
import reikai.novel.local.epub.xhtml
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * A grouped local novel refreshes from the folders it holds; a member folder that holds none of its books any
 * more, renamed or emptied outside the app, gives it no volume and fails nothing, as the manga source does.
 */
@RunWith(RobolectricTestRunner::class)
class LocalNovelGroupRefreshTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val root = File(context.cacheDir, "local-novel-groups").apply {
        deleteRecursively()
        mkdirs()
    }

    @After
    fun tearDown() {
        unmockkStatic("mihon.core.archive.UniFileExtensionsKt")
        root.deleteRecursively()
    }

    // The JVM has no native archive library, so every book opens as one small in-memory book.
    @Before
    fun openBooksInMemory() {
        val entries = mapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""",
            "OEBPS/content.opf" to opf(listOf("c1" to "c1.xhtml")),
            "OEBPS/c1.xhtml" to xhtml("<p>Text</p>"),
        ).mapValues { it.value.toByteArray() }
        mockkStatic("mihon.core.archive.UniFileExtensionsKt")
        every { any<UniFile>().archiveReader(any()) } answers {
            mockk<ArchiveReader>(relaxUnitFun = true) {
                every { readEntries(any()) } answers { entries.filterKeys { it in firstArg<Set<String>>() } }
            }
        }
    }

    private fun book(path: String) {
        File(root, path).apply { parentFile!!.mkdirs() }.writeText("epub")
    }

    private fun source(owners: List<VolumeOwner>) = LocalNovelSource(
        context,
        mockk<StorageManager> { every { getLocalNovelsDirectory() } returns UniFile.fromFile(root) },
        Json,
        mockk(relaxed = true),
        object : LocalVolumeRepository by NoLocalVolumes {
            override suspend fun all(type: ContentType) = owners
        },
        LocalSeriesLocks().apply { open() },
    )

    @Test
    fun `a member folder whose book was renamed outside the app gives the series no volume and fails nothing`() =
        runTest {
            book("A/A 1.epub")
            // B's book was combined into A, then renamed, so B now holds only a book of its own.
            book("B/B 1 fixed.epub")

            source(listOf(VolumeOwner("B", "B 1.epub", "A"))).parseNovel("A").volumes() shouldBe listOf("A 1.epub")
        }

    @Test
    fun `a member folder emptied of books outside the app gives the series no volume and fails nothing`() = runTest {
        book("A/A 1.epub")
        File(root, "B").mkdirs()
        File(root, "B/notes.txt").writeText("x")

        source(listOf(VolumeOwner("B", VolumeOwner.WHOLE, "A"))).parseNovel("A").volumes() shouldBe
            listOf("A 1.epub")
    }

    private fun SourceNovel.volumes() =
        chapters.orEmpty().mapNotNull { LocalNovelSource.parseChapterPath(it.path)?.second }.distinct()
}
