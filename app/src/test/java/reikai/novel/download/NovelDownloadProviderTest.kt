package reikai.novel.download

import android.text.TextUtils
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/** A novel's downloads on a real disk, named as the manga provider names them. */
class NovelDownloadProviderTest {

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

    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())

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

    private val novel = Novel.create().copy(id = 1L, source = "src", url = "/n", title = "Old Title")
    private val chapter = NovelChapter(
        id = 7L, novelId = 1L, url = "/c/1", name = "Chapter 1", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = 0L, dateUpload = 0L, page = "",
    )

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

    /** A case-only rename goes through a temporary name, which a case-insensitive file system needs. */
    @Test
    fun `a novel renamed only in letter case keeps its downloaded chapters`() {
        provider.writeChapter(novel, chapter, "<p>text</p>")

        provider.renameNovel(novel, "OLD TITLE")

        provider.readChapter(novel.copy(title = "OLD TITLE"), chapter) shouldBe "<p>text</p>"
    }
}
