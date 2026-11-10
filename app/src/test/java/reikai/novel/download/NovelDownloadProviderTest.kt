package reikai.novel.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.download.CaseBlindSourceFolder
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.stubUniFileStatics
import reikai.util.testAppScope
import reikai.util.unstubUniFileStatics
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import kotlin.time.Instant

/** A novel's downloads on a real disk, named as the manga provider names them. */
class NovelDownloadProviderTest {

    @TempDir
    lateinit var root: File

    @BeforeEach
    fun stubStatics() = stubUniFileStatics()

    @AfterEach
    fun restoreStatics() = unstubUniFileStatics()

    private val libraryPreferences = LibraryPreferences(EmittingPreferenceStore())
    private val novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply {
        seenNovelSources().set(
            mapOf(
                "tachiyomi:123" to LnSourceIdentity(name = "Novel Site", lang = "en"),
                "plugin" to LnSourceIdentity(name = "Plugin Site", lang = "en"),
            ),
        )
    }

    private val provider by lazy { provider(UniFile.fromFile(root)) }

    private fun provider(downloads: UniFile?) = NovelDownloadProvider(
        scope = testAppScope(),
        context = mockk(),
        storageManager = mockk<StorageManager> {
            every { getNovelDownloadsDirectory() } returns downloads
            every { changes } returns MutableSharedFlow()
        },
        downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
        libraryPreferences = libraryPreferences,
        novelPreferences = novelPreferences,
    )

    @Test
    fun `an app catalogue's folder is named by its name and language, as manga's is`() {
        provider.sourceDirName("tachiyomi:123") shouldBe "Novel Site (EN)"
    }

    /** The id is what a plugin update cannot change; the name beside it tells the folders apart. */
    @Test
    fun `a plugin's folder is its id with its name`() {
        provider.sourceDirName("plugin") shouldBe "plugin (Plugin Site)"
    }

    @Test
    fun `a source never recorded keeps its id`() {
        provider.sourceDirName("unknown") shouldBe "unknown"
    }

    /** What the upgrade does: a folder named by id moves to the name the source has now, downloads and all. */
    @Test
    fun `renaming a source folder moves its downloads`() {
        File(root, "plugin/Old Title").mkdirs()

        provider.renameSourceFolders(listOf("plugin"), from = { it }, to = provider::sourceFolderLabel)

        File(root, "plugin (Plugin Site)/Old Title").isDirectory shouldBe true
    }

    /** A case-blind disk finds the folder itself at its new name, which is no reason to leave it. */
    @Test
    fun `a source folder whose label changes only in letter case is renamed`() {
        val disk = CaseBlindSourceFolder("plugin (plugin site)")

        provider(disk.folder).renameSourceFolders(
            listOf("plugin"),
            from = { "plugin (plugin site)" },
            to = provider::sourceFolderLabel,
        )

        disk.names() shouldBe listOf("plugin (Plugin Site)")
    }

    private val novel = Novel.create().copy(id = 1L, source = "src", url = "/n", title = "Old Title")
    private val chapter = NovelChapter(
        id = 7L, novelId = 1L, url = "/c/1", name = "Chapter 1", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L,
        dateFetch = Instant.fromEpochMilliseconds(
            0L,
        ),
        dateUpload = null, page = "",
    )

    @Test
    fun `a chapter downloaded again replaces its earlier file and leaves nothing beside it`() {
        provider.writeChapter(novel, chapter, "<p>old</p>")

        provider.writeChapter(novel, chapter, "<p>new</p>")

        (provider.readChapter(novel, chapter) to File(root, "src/Old Title").list()!!.size) shouldBe ("<p>new</p>" to 1)
    }

    @Test
    fun `a renamed novel keeps its downloaded chapters`() {
        provider.writeChapter(novel, chapter, "<p>text</p>")

        provider.renameNovel(novel, "New Title")

        provider.readChapter(novel.copy(title = "New Title"), chapter) shouldBe "<p>text</p>"
    }

    @Test
    fun `a renamed novel leaves no folder under its old title`() {
        provider.writeChapter(novel, chapter, "<p>text</p>")

        provider.renameNovel(novel, "New Title")

        File(root, "src/Old Title").exists() shouldBe false
    }

    @Test
    fun `deleting chapters removes each one's file`() {
        val chapters = (1L..3L).map { chapter.copy(id = it, url = "/c/$it", name = "Chapter $it") }
        chapters.forEach { provider.writeChapter(novel, it, "<p>text</p>") }

        provider.deleteChapters(novel, chapters)

        chapters.mapNotNull { provider.readChapter(novel, it) } shouldBe emptyList()
    }

    @Test
    fun `deleting chapters leaves the novel's other chapters`() {
        val other = chapter.copy(id = 8L, url = "/c/8", name = "Chapter 8")
        provider.writeChapter(novel, chapter, "<p>text</p>")
        provider.writeChapter(novel, other, "<p>kept</p>")

        provider.deleteChapters(novel, listOf(chapter))

        provider.readChapter(novel, other) shouldBe "<p>kept</p>"
    }

    @Test
    fun `deleting a chapter saved before the non-ASCII setting flipped removes it`() {
        val accented = chapter.copy(name = "Chapitre \u00e9t\u00e9")
        provider.writeChapter(novel, accented, "<p>text</p>")
        libraryPreferences.disallowNonAsciiFilenames.set(true)

        provider.deleteChapters(novel, listOf(accented))

        provider.readChapter(novel, accented) shouldBe null
    }

    /** Each file-name setting flipped after the download: the name it was written under is still tried. */
    @ParameterizedTest(name = "written with non-ASCII disallowed {0} and the hash {1}")
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `a download is found after both file name settings change`(disallowNonAscii: Boolean, hash: Boolean) {
        libraryPreferences.disallowNonAsciiFilenames.set(disallowNonAscii)
        libraryPreferences.enableChapterNameHash.set(hash)
        val accented = chapter.copy(name = "Chapitre é 1")
        provider.writeChapter(novel, accented, "<p>text</p>")

        libraryPreferences.disallowNonAsciiFilenames.set(!disallowNonAscii)
        libraryPreferences.enableChapterNameHash.set(!hash)

        provider.readChapter(novel, accented) shouldBe "<p>text</p>"
    }

    /** The old copy stays on disk under the name it was written with, so the current name has to win. */
    @Test
    fun `a chapter downloaded again after a setting change reads the new copy`() {
        provider.writeChapter(novel, chapter, "<p>old</p>")
        libraryPreferences.enableChapterNameHash.set(true)

        provider.writeChapter(novel, chapter, "<p>new</p>")

        provider.readChapter(novel, chapter) shouldBe "<p>new</p>"
    }
}
