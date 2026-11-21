package reikai.data.backup.lnreader

import eu.kanade.tachiyomi.data.backup.BackupProtoReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An LNReader backup read and turned into Reikai backup entries, in both of LNReader's file formats. */
class LnReaderBackupTest {

    private val utc = TimeZone.UTC

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun reader(bytes: ByteArray) = LnReaderBackupReader { bytes.inputStream() }

    // Format 1, as LNReader 2.1.0 writes it: booleans as 0 and 1, plugins inside the settings dump.
    private val formatOne = zip(
        "NovelAndChapters/7.json" to """
            {"id":7,"path":"novel/a","pluginId":"novelbin","name":"A","inLibrary":1,"isLocal":0,
             "chapters":[{"path":"c1","name":"Chapter 1","unread":0,"bookmark":1,"page":"1","position":0}]}
        """,
        "Version.json" to """{"version":"2.1.0"}""",
        "Setting.json" to
            """{"INSTALL_PLUGINS":"[{\"id\":\"novelbin\",\"name\":\"NovelBin\",\"url\":\"https://r/novelbin.js\"}]"}""",
        "Category.json" to """[{"id":1,"name":"Default","novelIds":[]},{"id":3,"name":"Ongoing","novelIds":[7]}]""",
    )

    private val formatTwo = zip(
        "Version.json" to """{"appVersion":"2.2.0","formatVersion":2}""",
        "Plugins.json" to """[{"id":"royalroad","name":"Royal Road","url":"https://r/royalroad.js"}]""",
        "NovelAndChapters/1.json" to
            """{"id":1,"path":"fiction/1","pluginId":"royalroad","inLibrary":true,"chapters":[]}""",
    )

    private fun novel(
        chapters: List<LnReaderChapter> = emptyList(),
        cover: String? = null,
        id: Long = 7,
    ) = LnReaderNovel(
        id = id,
        path = "novel/a",
        pluginId = "novelbin",
        name = "A",
        cover = cover,
        inLibrary = true,
        chapters = chapters,
    )

    private fun chapter(path: String, page: String = "1", position: Int = 0) =
        LnReaderChapter(path = path, name = path, page = page, position = position)

    private val categories = listOf(
        LnReaderCategory(1, "Default", listOf(7)),
        LnReaderCategory(4, "Ongoing", listOf(7)),
        LnReaderCategory(2, "Local"),
        LnReaderCategory(5, "Finished"),
    )

    @Test
    fun `a zip with a version file and a novel folder is an LNReader backup`() {
        reader(formatOne).isLnReaderBackup() shouldBe true
    }

    private val mixedLibrary = zip(
        "Version.json" to """{"version":"2.1.0"}""",
        "NovelAndChapters/1.json" to """{"id":1,"path":"in","pluginId":"p","name":"In","inLibrary":true}""",
        "NovelAndChapters/2.json" to """{"id":2,"path":"out","pluginId":"p","name":"Out","inLibrary":false}""",
        "NovelAndChapters/3.json" to
            """{"id":3,"path":"/local/3","pluginId":"local","name":"Mine","inLibrary":true,"isLocal":true}""",
    )

    @Test
    fun `only library novels that are not local are imported`() {
        val imported = mutableListOf<String>()
        reader(mixedLibrary).forEachImported(onLocal = {}) { imported += it.path }

        imported shouldContainExactly listOf("in")
    }

    // A novel file cut short, or missing a field LNReader always writes, costs that novel and not the import.
    @Test
    fun `a novel file that does not read is skipped and the rest import`() {
        val damaged = zip(
            "Version.json" to """{"version":"2.1.0"}""",
            "NovelAndChapters/1.json" to """{"id":1,"path":"in","pluginId":"p","na""",
            "NovelAndChapters/2.json" to """{"id":2,"path":"kept","pluginId":"p","name":"Kept","inLibrary":true}""",
        )
        val imported = mutableListOf<String>()

        reader(damaged).forEachImported(onLocal = {}) { imported += it.path }

        imported shouldContainExactly listOf("kept")
    }

    @Test
    fun `a local library novel is named instead of imported`() {
        val named = mutableListOf<String>()
        reader(mixedLibrary).forEachImported(onLocal = { named += it }) {}

        named shouldContainExactly listOf("Mine")
    }

    @Test
    fun `an update time LNReader wrote as ISO UTC is read`() {
        val updated = novel().copy(lastUpdatedAt = "2026-07-28T09:00:00.000Z")

        updated.toImportedNovel(categories, utc).lastUpdate shouldBe 1785229200000L
    }

    // A crafted zip can inflate one entry past the heap, and the out-of-memory error reading it whole
    // raises is no Exception, so it would end the app instead of reaching the Restore screen's error.
    @Test
    fun `an entry larger than any backup entry is refused without being buffered`() {
        val bomb = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("Category.json"))
                val zeros = ByteArray(1 shl 16)
                var written = 0L
                while (written <= BackupProtoReader.MAX_FIELD_BYTES) {
                    zip.write(zeros)
                    written += zeros.size
                }
                zip.closeEntry()
            }
        }.toByteArray()

        shouldThrow<IOException> { reader(bomb).header() }
    }

    @Test
    fun `a file that is no zip is not an LNReader backup`() {
        reader(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0)).isLnReaderBackup() shouldBe false
    }

    @Test
    fun `format one's booleans are read from 0 and 1`() {
        val novels = mutableListOf<LnReaderNovel>()
        reader(formatOne).forEachNovel { novels += it }

        novels.single().chapters.single().let { it.unread to it.bookmark } shouldBe (false to true)
    }

    @Test
    fun `format one's plugins are read from the settings dump`() {
        reader(formatOne).header().plugins.map { it.id } shouldContainExactly listOf("novelbin")
    }

    @Test
    fun `format two's plugins are read from their own file`() {
        reader(formatTwo).header().plugins.map { it.id } shouldContainExactly listOf("royalroad")
    }

    @Test
    fun `a backup in a format newer than LNReader's 2 is refused`() {
        val formatThree = zip(
            "Version.json" to """{"appVersion":"2.3.0","formatVersion":3}""",
            "NovelAndChapters/1.json" to """{"id":1,"path":"fiction/1","pluginId":"royalroad","inLibrary":true}""",
        )

        shouldThrow<UnsupportedLnReaderFormat> { reader(formatThree).header() }.formatVersion shouldBe 3
    }

    @Test
    fun `a format two backup still imports its library novels`() {
        val reader = reader(formatTwo)
        val imported = mutableListOf<String>()

        reader.header()
        reader.forEachImported(onLocal = {}) { imported += it.path }

        imported shouldContainExactly listOf("fiction/1")
    }

    @Test
    fun `a backup that records no format is read as format one`() {
        reader(formatOne).header().categories.map { it.name } shouldContainExactly listOf("Default", "Ongoing")
    }

    @Test
    fun `chapters run in LNReader's reading order, page then position, not the order it wrote them`() {
        val written = listOf(chapter("p2a", page = "2", position = 0), chapter("p1b", position = 1), chapter("p1a"))

        novel(written).toImportedNovel(categories, utc).chapters.sortedBy {
            it.sourceOrder
        }.map { it.url } shouldContainExactly
            listOf("p1a", "p1b", "p2a")
    }

    @Test
    fun `progress in whole percent becomes hundredths`() {
        val read = chapter("c").copy(progress = 61)

        novel(listOf(read)).toImportedNovel(categories, utc).chapters.single().lastTextProgress shouldBe 6100L
    }

    @Test
    fun `read and bookmarked chapters carry across`() {
        val marked = chapter("c").copy(unread = false, bookmark = true)

        novel(listOf(marked)).toImportedNovel(categories, utc).chapters.single().let { it.read to it.bookmark } shouldBe
            (true to true)
    }

    @Test
    fun `imported chapters count as never fetched`() {
        novel(listOf(chapter("c"))).toImportedNovel(categories, utc).chapters.single().dateFetch shouldBe 0L
    }

    @Test
    fun `history comes from each chapter's read time, read in the device's zone, with its time spent`() {
        val read = chapter("c").copy(readTime = "2026-04-09 22:41:42", timeSpent = 5000)

        // Tokyo runs nine hours ahead of UTC all year, so a zone ignored moves the read nine hours later.
        novel(listOf(read)).toImportedNovel(categories, TimeZone.of("Asia/Tokyo")).history.single().let {
            Triple(it.url, it.lastRead, it.readDuration)
        } shouldBe
            Triple("c", 1775774502000L - 9 * 3_600_000L, 5000L)
    }

    @Test
    fun `LNReader's own categories are left out and the rest keep their order`() {
        importedCategories(categories).map { it.name to it.order } shouldContainExactly
            listOf("Ongoing" to 0L, "Finished" to 1L)
    }

    @Test
    fun `a novel is filed under the categories that list it`() {
        novel().toImportedNovel(categories, utc).categories shouldContainExactly listOf(0L)
    }

    // LNReader keeps a plugin's parse as it came, escaped, where Reikai decodes a plugin's text as it arrives.
    @Test
    fun `a title and chapter name LNReader kept escaped are decoded`() {
        val imported = novel(listOf(chapter("c").copy(name = "Tom &amp; Jerry"))).copy(name = "A &amp; B")
            .toImportedNovel(categories, utc)

        (imported.title to imported.chapters.single().name) shouldBe ("A & B" to "Tom & Jerry")
    }

    @Test
    fun `a cover LNReader stored itself is dropped`() {
        novel(cover = "/Novels/novelbin/7/cover.png").toImportedNovel(categories, utc).thumbnailUrl shouldBe null
    }

    @Test
    fun `a cover on the web is kept`() {
        novel(cover = "https://site/cover.jpg").toImportedNovel(categories, utc).thumbnailUrl shouldBe
            "https://site/cover.jpg"
    }

    @Test
    fun `the novel is on its plugin's source at the plugin's path`() {
        novel().toImportedNovel(categories, utc).let { it.source to it.url } shouldBe ("novelbin" to "novel/a")
    }
}
