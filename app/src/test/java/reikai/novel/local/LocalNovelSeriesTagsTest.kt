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
import reikai.novel.local.epub.opf
import reikai.novel.local.epub.xhtml
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * A local novel series carries every volume's tags, its own folder's and those another folder lends it, so a first
 * book with none hides nothing the others have; a details file still names them over the books.
 */
@RunWith(RobolectricTestRunner::class)
class LocalNovelSeriesTagsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val root = File(context.cacheDir, "local-novel-tags").apply {
        deleteRecursively()
        mkdirs()
    }

    // Each book's subjects, by file name; the JVM has no native archive library, so books open in memory.
    private val subjects = mutableMapOf<String, List<String>>()

    @Before
    fun openBooksInMemory() {
        mockkStatic("mihon.core.archive.UniFileExtensionsKt")
        every { any<UniFile>().archiveReader(any()) } answers {
            val metadata = "<dc:title>Book</dc:title>" +
                subjects[firstArg<UniFile>().name].orEmpty().joinToString("") { "<dc:subject>$it</dc:subject>" }
            val entries = mapOf(
                "META-INF/container.xml" to """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""",
                "OEBPS/content.opf" to opf(listOf("c1" to "c1.xhtml"), metadata = metadata),
                "OEBPS/c1.xhtml" to xhtml("<p>Text</p>"),
            ).mapValues { it.value.toByteArray() }
            mockk<ArchiveReader>(relaxUnitFun = true) {
                every { readEntries(any()) } answers { entries.filterKeys { it in firstArg<Set<String>>() } }
            }
        }
    }

    @After
    fun tearDown() {
        unmockkStatic("mihon.core.archive.UniFileExtensionsKt")
        root.deleteRecursively()
    }

    @Test
    fun `a series lists every volume's tags once, in volume order, past an untagged first volume`() = runTest {
        book("A/A 1.epub")
        book("A/A 2.epub", "Fantasy", "Action")
        book("A/A 3.epub", "action", "Romance")

        source().parseNovel("A").genres shouldBe "Fantasy,Action,Romance"
    }

    @Test
    fun `a series' details file names its tags over every volume's own`() = runTest {
        book("A/A 1.epub", "Fantasy")
        File(root, "A/details.json").writeText("""{"genre":["Mystery","Drama"]}""")

        source().parseNovel("A").genres shouldBe "Mystery,Drama"
    }

    @Test
    fun `a grouped series lists the tags of the books another folder lends it`() = runTest {
        book("A/A 1.epub", "Fantasy")
        book("B/B 1.epub", "Horror")

        source(VolumeOwner("B", VolumeOwner.WHOLE, "A")).parseNovel("A").genres shouldBe "Fantasy,Horror"
    }

    private fun book(path: String, vararg tags: String) {
        File(root, path).apply { parentFile!!.mkdirs() }.writeText("epub")
        subjects[path.substringAfterLast('/')] = tags.toList()
    }

    private fun source(vararg owners: VolumeOwner) = LocalNovelSource(
        context,
        mockk<StorageManager> { every { getLocalNovelsDirectory() } returns UniFile.fromFile(root) },
        Json,
        mockk(relaxed = true),
        object : LocalVolumeRepository by NoLocalVolumes {
            override suspend fun all(type: ContentType) = owners.toList()
        },
        LocalSeriesLocks(NoLocalVolumes).apply { open() },
    )
}
