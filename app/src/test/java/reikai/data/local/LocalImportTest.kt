package reikai.data.local

import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.host.SourceNovel
import reikai.novel.local.LocalNovelSource
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/** Copying picked files into a local source folder and adding the series, for each content type. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LocalImportTest(private val type: ContentType) {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val root = File(context.cacheDir, "local-${type.name}").apply {
        deleteRecursively()
        mkdirs()
    }

    // Storage, the source's own parse and the database are the boundary; each add's favorite write is refused.
    private val localImport = LocalImport(
        context = context,
        storageManager = mockk<StorageManager> {
            every { getLocalSourceDirectory() } returns UniFile.fromFile(root)
            every { getLocalNovelsDirectory() } returns UniFile.fromFile(root)
        },
        localNovels = mockk<LocalNovelSource> { coEvery { parseNovel(any()) } answers { SourceNovel(firstArg()) } },
        novelRepository = mockk<NovelRepository> {
            coEvery { insertOrGet(any()) } returns null
            coEvery { getByUrlAndSource(any(), any()) } returns Novel.create().copy(id = 7L)
        },
        novelChapterRepository = mockk(),
        libraryPreferences = mockk(),
        chapterNumberOverrides = mockk(),
        novelAdder = mockk<NovelLibraryAdder> { coEvery { addToBatch(any(), any(), any()) } returns AddOutcome.Failed },
        mangaAdder = mockk<MangaLibraryAdder> { coEvery { addToBatch(any(), any()) } returns AddOutcome.Failed },
        networkToLocalManga = mockk {
            coEvery { this@mockk.invoke(any<Manga>()) } returns Manga.create().copy(id = 7L)
        },
        updateMangaFromRemote = mockk {
            coEvery { this@mockk.invoke(any<Manga>(), any(), any(), any(), any()) } returns Result.success(mockk())
        },
    )

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(ImportDocuments::class.java, ImportDocuments.AUTHORITY)
        ImportDocuments.documents["1"] = ImportDocuments.Document("a.cbz", 3L, null, "abc".toByteArray())
    }

    @After
    fun tearDown() {
        ImportDocuments.documents.clear()
        root.deleteRecursively()
    }

    @Test
    fun `a series the library refused to add is reported, not opened`() = runTest {
        val result = localImport.import(listOf(group("Foo")), addToLibrary = true, categoryIds = emptyList())

        (result.problems.map { it.reason } to result.series.single().entryId) shouldBe
            (listOf(ImportProblem.Reason.AddFailed) to null)
    }

    @Test
    fun `a series joins the folder a past import made under another spelling`() = runTest {
        File(root, "Foo Bar").mkdirs()

        localImport.import(listOf(group("foo-bar")), addToLibrary = false, categoryIds = emptyList())

        root.list()!!.toList() shouldBe listOf("Foo Bar")
    }

    private fun group(title: String) = ImportGroup(
        key = "$type:$title",
        type = type,
        title = title,
        author = null,
        cover = null,
        files = listOf(ImportFile(ImportDocuments.uri("1"), "a.cbz", 3L)),
        canBeEitherType = false,
    )

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
