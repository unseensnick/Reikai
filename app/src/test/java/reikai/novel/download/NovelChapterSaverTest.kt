package reikai.novel.download

import android.text.TextUtils
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.source.NovelSource
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * Saves on a real disk with the chapter name hash off, where two chapters of one name share one file. Manga's
 * downloader keeps the first folder written under a name, and a novel's first saved copy is kept the same way.
 */
class NovelChapterSaverTest {

    @TempDir
    lateinit var root: File

    // UniFile's file-backed implementation asks android.text.TextUtils, which the JVM test jar stubs out.
    @BeforeEach
    fun stubTextUtils() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    }

    @AfterEach
    fun restoreTextUtils() {
        unmockkStatic(TextUtils::class)
    }

    private val libraryPreferences = LibraryPreferences(EmittingPreferenceStore())

    private val provider by lazy {
        NovelDownloadProvider(
            storageManager = mockk<StorageManager> {
                every { getNovelDownloadsDirectory() } returns UniFile.fromFile(root)
                every { changes } returns MutableSharedFlow()
            },
            downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
            libraryPreferences = libraryPreferences,
        )
    }

    private val novel = Novel.create().copy(id = 1L, source = "src", url = "/n", title = "Title")
    private val first = chapter(id = 7L, url = "/c/1")
    private val second = chapter(id = 8L, url = "/c/2")

    private val saver by lazy {
        NovelChapterSaver(
            provider = provider,
            cache = mockk(relaxed = true),
            imageRequests = mockk { coEvery { forSource(any()) } returns mockk() },
            chapterRepo = mockk<NovelChapterRepository> { coEvery { getByNovelId(1L) } returns listOf(first, second) },
        )
    }
    private val source = mockk<NovelSource> {
        every { id } returns "src"
        every { site } returns "https://example.org"
    }

    @Test
    fun `a chapter sharing a downloaded chapter's name leaves that download alone`() = runTest {
        saver.save(novel, first, source, "<p>first</p>")

        saver.save(novel, second, source, "<p>second</p>")

        provider.readChapter(novel, first) shouldBe "<p>first</p>"
    }

    @Test
    fun `a chapter sharing a downloaded chapter's name reports the name taken`() = runTest {
        saver.save(novel, first, source, "<p>first</p>")

        saver.save(novel, second, source, "<p>second</p>") shouldBe NovelChapterSaver.SaveResult.NAME_TAKEN
    }

    /** A page fetch replaces a chapter's own text, which only a name no other chapter has can say is its own. */
    @Test
    fun `a chapter whose name no other chapter has replaces its own download`() = runTest {
        val alone = second.copy(name = "Another name")
        saver.save(novel, alone, source, "<p>old</p>")

        saver.save(novel, alone, source, "<p>new</p>")

        provider.readChapter(novel, alone) shouldBe "<p>new</p>"
    }

    private fun chapter(id: Long, url: String) = NovelChapter(
        id = id, novelId = 1L, url = url, name = "Chapter", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = 0L, dateUpload = 0L, page = "",
    )
}
