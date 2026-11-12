package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadProvider
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.storage.FakeSafProvider
import reikai.storage.FakeSafProvider.ChildListingFault
import reikai.util.realTimeSourceContacts
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import kotlin.time.Instant

/**
 * Both download engines drop a series folder once its last chapter goes, and a source folder once its last
 * series goes, and a picked folder's failed listing reads as empty. Neither may take that word for it: the
 * folder goes only on a listing trusted whole, or a provider hiccup deletes every other download beside it.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class EmptiedFolderDeleteConformanceTest(private val half: Half) {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var downloads: File

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        downloads = tmp.newFolder("Reikai", "downloads")
    }

    private fun picked(): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(downloads))!!

    @Test
    fun `deleting a chapter while the series folder lists short keeps the series' other chapter`() = runTest {
        half.chapter(downloads, "Ch 1")
        half.chapter(downloads, "Ch 2")
        FakeSafProvider.faultedFolder = File(downloads, "src (EN)/Title")
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        half.deleteChapter(context, picked(), "Ch 1")

        (half.hasChapter(downloads, "Ch 1") to half.hasChapter(downloads, "Ch 2")) shouldBe (false to true)
    }

    @Test
    fun `deleting the last chapter takes the series folder with it`() = runTest {
        half.chapter(downloads, "Ch 1")

        half.deleteChapter(context, picked(), "Ch 1")

        File(downloads, "src (EN)/Title").exists() shouldBe false
    }

    @Test
    fun `deleting a series while the source folder lists short keeps the source's other series`() = runTest {
        half.chapter(downloads, "Ch 1")
        File(downloads, "src (EN)/Other/Ch 1").mkdirs()
        FakeSafProvider.faultedFolder = File(downloads, "src (EN)")
        FakeSafProvider.childListingFaults += ChildListingFault.Throw

        half.deleteSeries(context, picked())

        (File(downloads, "src (EN)/Title").exists() to File(downloads, "src (EN)/Other").exists()) shouldBe
            (false to true)
    }

    @Test
    fun `deleting the last series takes the source folder with it`() = runTest {
        half.chapter(downloads, "Ch 1")

        half.deleteSeries(context, picked())

        File(downloads, "src (EN)").exists() shouldBe false
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun halves() = listOf(arrayOf(MangaHalf()), arrayOf(NovelHalf()))
    }

    /** One engine over the folder `src (EN)/Title` under the picked downloads root. */
    interface Half {
        fun chapter(downloads: File, name: String)
        fun hasChapter(downloads: File, name: String): Boolean
        suspend fun deleteChapter(context: Context, downloads: UniFile, name: String)
        suspend fun deleteSeries(context: Context, downloads: UniFile)
    }

    class MangaHalf : Half {
        override fun toString() = "manga"

        private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Title")
        private val source: Source = StubSource(id = 1L, lang = "en", name = "src")

        override fun chapter(downloads: File, name: String) {
            File(downloads, "src (EN)/Title/$name/001.jpg").apply { parentFile!!.mkdirs() }.writeText("page")
        }

        override fun hasChapter(downloads: File, name: String) = File(downloads, "src (EN)/Title/$name").exists()

        override suspend fun deleteChapter(context: Context, downloads: UniFile, name: String) {
            manager(context, downloads).deleteChapters(listOf(chapterRow(name)), manga, source)
        }

        override suspend fun deleteSeries(context: Context, downloads: UniFile) {
            manager(context, downloads).deleteManga(manga, source)
        }

        private fun chapterRow(name: String) = Chapter.create().copy(
            id = name.hashCode().toLong(),
            name = name,
            url = name,
        )

        private fun manager(context: Context, downloads: UniFile): DownloadManager {
            val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
            val storage = mockk<StorageManager> {
                every { getDownloadsDirectory() } returns downloads
                every { changes } returns MutableSharedFlow()
            }
            return DownloadManager(
                context = context,
                provider = DownloadProvider(context, storage, libraryPreferences),
                cache = mockk(relaxed = true),
                getCategories = mockk(),
                sourceManager = mockk { coEvery { getOrStub(1L) } returns source },
                downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
                getManga = mockk(),
                getChapter = mockk(),
                downloader = mockk(relaxed = true) { every { queueState } returns MutableStateFlow(emptyList()) },
                pendingDeleter = mockk(),
                sourceTitles = mockk(),
            )
        }
    }

    class NovelHalf : Half {
        override fun toString() = "novel"

        private val novel = Novel.create().copy(id = 1L, source = "tachiyomi:1", url = "/n", title = "Title")

        // Unconfined, so the launch deleteChapters makes runs to its end inside the call: nothing in it suspends.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        override fun chapter(downloads: File, name: String) {
            File(downloads, "src (EN)/Title/$name.html").apply { parentFile!!.mkdirs() }.writeText("<p>text</p>")
        }

        override fun hasChapter(downloads: File, name: String) = File(downloads, "src (EN)/Title/$name.html").exists()

        override suspend fun deleteChapter(context: Context, downloads: UniFile, name: String) {
            manager(context, downloads).deleteChapters(listOf(chapterRow(name)))
        }

        override suspend fun deleteSeries(context: Context, downloads: UniFile) {
            manager(context, downloads).awaitDeleteNovel(novel)
        }

        private fun chapterRow(name: String) = NovelChapter(
            id = name.hashCode().toLong(), novelId = 1L, url = "/c/$name", name = name, read = false, bookmark = false,
            lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L,
            dateFetch = Instant.fromEpochMilliseconds(0L), dateUpload = null, page = "",
        )

        private fun manager(context: Context, downloads: UniFile): NovelDownloadManager {
            val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
            val novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply {
                seenNovelSources().set(mapOf("tachiyomi:1" to LnSourceIdentity(name = "src", lang = "en")))
            }
            val appContext = mockk<Context>(relaxed = true) {
                every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
                every { getSystemService(NotificationManager::class.java) } returns mockk(relaxed = true)
            }
            return NovelDownloadManager(
                scope = scope,
                context = appContext,
                provider = NovelDownloadProvider(
                    scope = testAppScope(),
                    context = context,
                    storageManager = mockk<StorageManager> {
                        every { getNovelDownloadsDirectory() } returns downloads
                        every { changes } returns MutableSharedFlow()
                    },
                    downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
                    libraryPreferences = libraryPreferences,
                    novelPreferences = novelPreferences,
                ),
                cache = mockk(relaxed = true),
                chapterRepo = mockk(),
                novelRepo = mockk<NovelRepository> { coEvery { getById(1L) } returns novel },
                sourceManager = mockk(),
                installer = mockk(),
                downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
                sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
                novelPreferences = novelPreferences,
                saver = mockk(),
                securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
                adultChecker = mockk(),
                sourceTitles = mockk(),
                getEntryCustomInfo = mockk(),
                sourceContacts = realTimeSourceContacts(),
            )
        }
    }
}
