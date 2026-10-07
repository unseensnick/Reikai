package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import android.text.TextUtils
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadProvider
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * Both types name a download folder by title within its source, so a title change moves the entry's folder only while
 * it is the entry's own. A folder another entry on the source is named onto holds that entry's downloads too.
 */
class TitleRenameFolderConformanceTest {

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a title change leaves a folder another entry on the source is named onto`(half: TitleRenameHalf) = runTest {
        File(root, "src/Old").mkdirs()

        half.rename(UniFile.fromFile(root)!!, otherTitles = listOf("Old"))

        File(root, "src").list()!!.toSet() shouldBe setOf("Old")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a title change moves a folder only its own entry is named onto`(half: TitleRenameHalf) = runTest {
        File(root, "src/Old").mkdirs()

        half.rename(UniFile.fromFile(root)!!, otherTitles = listOf("Other"))

        File(root, "src").list()!!.toSet() shouldBe setOf("New")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a change of letter case alone renames the folder on a case-blind disk`(half: TitleRenameHalf) = runTest {
        val disk = CaseBlindSourceFolder("Old")

        half.rename(disk.root, otherTitles = emptyList(), newTitle = "OLD")

        disk.names() shouldBe listOf("OLD")
    }

    companion object {
        @JvmStatic
        fun halves() = listOf(MangaTitleRenameHalf(), NovelTitleRenameHalf())
    }
}

interface TitleRenameHalf {
    /** Retitles an entry stored as "Old", whose folder sits at `src/Old` under [downloads], to [newTitle]. */
    suspend fun rename(downloads: UniFile, otherTitles: List<String>, newTitle: String = "New")
}

class MangaTitleRenameHalf : TitleRenameHalf {
    override fun toString() = "manga"

    override suspend fun rename(downloads: UniFile, otherTitles: List<String>, newTitle: String) {
        val manga = Manga.create().copy(id = 1L, source = 1L, title = "Old")
        val sourceFolder = downloads.findFile("src")!!
        val manager = DownloadManager(
            context = mockk(relaxed = true),
            provider = mockk {
                every { findMangaDir("Old", any()) } answers { sourceFolder.findFile("Old") }
                every { getMangaDirName(any()) } answers { firstArg() }
            },
            cache = mockk(relaxed = true),
            getCategories = mockk(),
            sourceManager = mockk { coEvery { getOrStub(1L) } returns mockk(relaxed = true) },
            downloadPreferences = mockk(),
            getManga = mockk(),
            getChapter = mockk(),
            downloader = mockk { every { queueState } returns MutableStateFlow(emptyList()) },
            pendingDeleter = mockk(),
            sourceTitles = mockk { coEvery { otherMangaTitles(1L, 1L) } returns otherTitles },
        )

        manager.renameManga(manga, newTitle)
    }
}

class NovelTitleRenameHalf : TitleRenameHalf {
    override fun toString() = "novel"

    override suspend fun rename(downloads: UniFile, otherTitles: List<String>, newTitle: String) {
        val novel = Novel.create().copy(id = 1L, source = "src", url = "/n", title = "Old")
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val manager = NovelDownloadManager(
            context = mockk(relaxed = true) {
                every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
                every { getSystemService(NotificationManager::class.java) } returns mockk(relaxed = true)
            },
            provider = NovelDownloadProvider(
                storageManager = mockk<StorageManager> {
                    every { getNovelDownloadsDirectory() } returns downloads
                    every { changes } returns MutableSharedFlow()
                },
                downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
                libraryPreferences = libraryPreferences,
            ),
            cache = mockk(relaxed = true),
            chapterRepo = mockk(),
            novelRepo = mockk(),
            sourceManager = mockk(),
            installer = mockk(),
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            saver = mockk(),
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk(),
            sourceTitles = mockk { coEvery { otherNovelTitles("src", 1L) } returns otherTitles },
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
        )

        manager.renameNovel(novel, newTitle)
    }
}
