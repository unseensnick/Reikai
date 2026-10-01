package mihon.core.migration.migrations

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
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.dedupe.MergedDuplicateRepository
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * Old-scheme novel downloads (`<novel id>/<chapter id>.html`) on a real disk. The upgrade's dedupe (51.sqm) runs
 * before this re-key and deletes the rows of the copies it merges, so their ids resolve only through its record.
 */
class NovelDownloadRekeyMigrationTest {

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
            storageManager = storageManager,
            downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
            libraryPreferences = libraryPreferences,
        )
    }
    private val storageManager by lazy {
        mockk<StorageManager> {
            every { getNovelDownloadsDirectory() } returns UniFile.fromFile(root)
            every { changes } returns MutableSharedFlow()
        }
    }

    private val survivor = Novel.create().copy(id = SURVIVOR, source = "src", url = "/n", title = "Kept Title")
    private val chapter = chapter(id = CHAPTER, url = "/c/1")
    private val keptChapter = chapter(id = KEPT_CHAPTER, url = "/c/2")

    private val novels = mockk<NovelRepository> {
        coEvery { getById(any()) } returns null
        coEvery { getById(SURVIVOR) } returns survivor
    }
    private val chapters = mockk<NovelChapterRepository> {
        coEvery { getById(any()) } returns null
        coEvery { getById(CHAPTER) } returns chapter
        coEvery { getById(KEPT_CHAPTER) } returns keptChapter
    }

    @Test
    fun `a merged-away novel's old-scheme chapter moves to the survivor`() = runTest {
        oldFile(DISCARDED, CHAPTER).writeText("old")

        run(record(MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR, "Old Title")))

        provider.readChapter(survivor, chapter) shouldBe "old"
    }

    @Test
    fun `a merged-away chapter's old-scheme file moves to the chapter it merged into`() = runTest {
        oldFile(SURVIVOR, MERGED_CHAPTER).writeText("old")

        run(record(chapters = listOf(MergedDuplicateChapter(ContentType.NOVELS, MERGED_CHAPTER, KEPT_CHAPTER))))

        provider.readChapter(survivor, keptChapter) shouldBe "old"
    }

    @Test
    fun `a chapter the survivor has keeps the survivor's own download`() = runTest {
        oldFile(SURVIVOR, KEPT_CHAPTER).writeText("kept")
        oldFile(DISCARDED, MERGED_CHAPTER).writeText("old")

        run(mergedPair())

        provider.readChapter(survivor, keptChapter) shouldBe "kept"
    }

    /** Nothing is overwritten, and a file not carried is never deleted. */
    @Test
    fun `a merged-away copy of a chapter the survivor has stays in place`() = runTest {
        oldFile(SURVIVOR, KEPT_CHAPTER).writeText("kept")
        oldFile(DISCARDED, MERGED_CHAPTER).writeText("old")

        run(mergedPair())

        oldFile(DISCARDED, MERGED_CHAPTER).exists() shouldBe true
    }

    @Test
    fun `a merged-away manga's id leaves a novel folder of that number alone`() = runTest {
        oldFile(DISCARDED, CHAPTER).writeText("old")

        run(record(MergedDuplicate(ContentType.MANGA, DISCARDED, SURVIVOR, "Old Title")))

        oldFile(DISCARDED, CHAPTER).exists() shouldBe true
    }

    private suspend fun run(record: MergedDuplicateRepository) {
        NovelDownloadRekeyMigration(storageManager, novels, chapters, provider, record)
            .invoke(MigrationContext(dryrun = false, previousVersion = 181))
    }

    private fun mergedPair() = record(
        MergedDuplicate(ContentType.NOVELS, DISCARDED, SURVIVOR, "Old Title"),
        chapters = listOf(MergedDuplicateChapter(ContentType.NOVELS, MERGED_CHAPTER, KEPT_CHAPTER)),
    )

    private fun record(vararg duplicates: MergedDuplicate, chapters: List<MergedDuplicateChapter> = emptyList()) =
        mockk<MergedDuplicateRepository> {
            coEvery { getAll() } returns duplicates.toList()
            coEvery { getChapters() } returns chapters
        }

    private fun oldFile(novelId: Long, chapterId: Long) = File(root, "$novelId/$chapterId.html").apply {
        parentFile?.mkdirs()
    }

    private fun chapter(id: Long, url: String) = NovelChapter(
        id = id, novelId = SURVIVOR, url = url, name = "Chapter $id", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = 0L, dateUpload = 0L, page = "",
    )

    private companion object {
        const val DISCARDED = 3L
        const val SURVIVOR = 7L
        const val CHAPTER = 11L
        const val MERGED_CHAPTER = 12L
        const val KEPT_CHAPTER = 20L
    }
}
