package reikai.domain.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.SourceTitlesRepository
import reikai.novel.download.NovelDownloadProvider
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.stubUniFileStatics
import reikai.util.unstubUniFileStatics
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * Two sources of one name and language read one download folder, on both types. A rename of one moves its own
 * titles to its new folder and leaves the shared folder, and the other source's titles, where they are.
 */
class SharedSourceFolderRenameConformanceTest {

    @TempDir
    lateinit var root: File

    @BeforeEach
    fun stubStatics() {
        stubUniFileStatics()
        mockkObject(DiskUtil)
        every { DiskUtil.getAvailableStorageSpace(any<UniFile>()) } returns Long.MAX_VALUE
    }

    @AfterEach
    fun restoreStatics() {
        unstubUniFileStatics()
        unmockkObject(DiskUtil)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a renamed source moves only its own titles out of a folder it shares`(half: SharedFolderRenameHalf) =
        runTest {
            File(root, "Foo (EN)/Mine/c1").apply { parentFile!!.mkdirs() }.writeText("1")
            File(root, "Foo (EN)/Theirs/c2").apply { parentFile!!.mkdirs() }.writeText("2")
            val ownTitles = object : SourceTitlesRepository {
                override suspend fun otherMangaTitles(sourceIds: Collection<Long>, mangaId: Long) =
                    if (1L in sourceIds) listOf("Mine") else emptyList()
                override suspend fun otherNovelTitles(sources: Collection<String>, novelId: Long) =
                    if ("tachiyomi:1" in sources) listOf("Mine") else emptyList()
            }

            with(half) { renameFooToFoo2(UniFile.fromFile(root)!!, ownTitles) }

            listOf("Foo2 (EN)/Mine/c1", "Foo (EN)/Theirs/c2").map { File(root, it).isFile } shouldBe listOf(true, true)
        }

    companion object {
        @JvmStatic
        fun halves() = listOf(MangaSharedFolderRenameHalf(), NovelSharedFolderRenameHalf())
    }
}

interface SharedFolderRenameHalf {
    /** Renames source 1 from "Foo" to "Foo2"; source 2, also "Foo" in English, keeps its name. */
    suspend fun TestScope.renameFooToFoo2(downloads: UniFile, ownTitles: SourceTitlesRepository)
}

class MangaSharedFolderRenameHalf : SharedFolderRenameHalf {
    override fun toString() = "manga"

    override suspend fun TestScope.renameFooToFoo2(downloads: UniFile, ownTitles: SourceTitlesRepository) {
        val old = StubSource(id = 1L, lang = "en", name = "Foo")
        val renamed = StubSource(id = 1L, lang = "en", name = "Foo2")
        val manager = DownloadManager(
            context = mockk(relaxed = true),
            provider = mockk {
                coEvery { findSourceDir(old) } returns downloads.findFile("Foo (EN)")
                every { getSourceDirName(any()) } answers { firstArg<StubSource>().toString() }
                every { getMangaDirName(any()) } answers { firstArg() }
            },
            cache = mockk(relaxed = true),
            getCategories = mockk(),
            sourceManager = mockk {
                coEvery { getAll() } returns listOf(renamed, StubSource(id = 2L, lang = "en", name = "Foo"))
                coEvery { getStubSources() } returns emptyList()
            },
            downloadPreferences = mockk(),
            getManga = mockk(),
            getChapter = mockk(),
            downloader = mockk { every { queueState } returns MutableStateFlow(emptyList()) },
            pendingDeleter = mockk(),
            sourceTitles = ownTitles,
            getChaptersByMangaId = mockk(),
        )

        manager.renameSource(old, renamed)
    }
}

class NovelSharedFolderRenameHalf : SharedFolderRenameHalf {
    override fun toString() = "novel"

    override suspend fun TestScope.renameFooToFoo2(downloads: UniFile, ownTitles: SourceTitlesRepository) {
        val libraryPreferences = LibraryPreferences(EmittingPreferenceStore())
        val novelPreferences = NovelPreferences(EmittingPreferenceStore())
        fun seen(name: String) = novelPreferences.seenNovelSources().set(
            mapOf(
                "tachiyomi:1" to LnSourceIdentity(name = name, lang = "en"),
                "tachiyomi:2" to LnSourceIdentity(name = "Foo", lang = "en"),
            ),
        )
        seen("Foo")
        NovelDownloadProvider(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            context = mockk(relaxed = true),
            storageManager = mockk<StorageManager> {
                every { getNovelDownloadsDirectory() } returns downloads
                every { changes } returns MutableSharedFlow()
            },
            downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
            libraryPreferences = libraryPreferences,
            novelPreferences = novelPreferences,
            sourceTitles = ownTitles,
        )
        advanceUntilIdle()

        seen("Foo2")
        advanceUntilIdle()
    }
}
