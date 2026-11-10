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
import reikai.domain.chapter.InMemoryChapterCopyMarks
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
        chapterCopyMarks = InMemoryChapterCopyMarks(),
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

        localImport.import(
            listOf(localImport.inspect(group("foo-bar"))),
            addToLibrary = false,
            categoryIds = emptyList(),
        )

        root.list()!!.toList() shouldBe listOf("Foo Bar")
    }

    @Test
    fun `a series kept separate gets a folder of its own beside the one it matched`() = runTest {
        File(root, "Foo Bar").mkdirs()
        val separate = localImport.inspect(group("foo-bar")).copy(separate = true)

        localImport.import(listOf(separate), addToLibrary = false, categoryIds = emptyList())

        root.list()!!.sorted() shouldBe listOf("Foo Bar", "foo-bar")
    }

    @Test
    fun `a new series names its files as Rename files would`() = runTest {
        localImport.import(listOf(group("Foo")), addToLibrary = false, categoryIds = emptyList())

        File(root, "Foo").list()!!.toList() shouldBe listOf(NEW_NAME.getValue(type))
    }

    @Test
    fun `a file the series already holds is skipped when asked`() = runTest {
        File(root, "Foo").mkdirs()
        File(root, "Foo/old").writeText("xyz")
        val skipping = localImport.inspect(group("Foo")).copy(onDuplicate = DuplicateChoice.Skip)

        localImport.import(listOf(skipping), addToLibrary = false, categoryIds = emptyList())

        File(root, "Foo").list()!!.toList() shouldBe listOf("old")
    }

    @Test
    fun `a series whose every file was skipped is not reported as imported`() = runTest {
        File(root, "Foo").mkdirs()
        File(root, "Foo/old").writeText("xyz")
        val skipping = localImport.inspect(group("Foo")).copy(onDuplicate = DuplicateChoice.Skip)

        val result = localImport.import(listOf(skipping), addToLibrary = false, categoryIds = emptyList())

        result.series shouldBe emptyList()
    }

    @Test
    fun `a replaced file keeps its name and takes the new contents`() = runTest {
        File(root, "Foo").mkdirs()
        File(root, "Foo/old").writeText("xyz")
        val replacing = localImport.inspect(group("Foo")).copy(onDuplicate = DuplicateChoice.Replace)

        localImport.import(listOf(replacing), addToLibrary = false, categoryIds = emptyList())

        (File(root, "Foo").list()!!.toList() to File(root, "Foo/old").readText()) shouldBe (listOf("old") to "abc")
    }

    @Test
    fun `a file kept beside its duplicate is copied too`() = runTest {
        File(root, "Foo").mkdirs()
        File(root, "Foo/old").writeText("xyz")

        localImport.import(listOf(localImport.inspect(group("Foo"))), addToLibrary = false, categoryIds = emptyList())

        File(root, "Foo").list()!!.size shouldBe 2
    }

    @Test
    fun `a file that arrives shorter than its size fails its copy`() = runTest {
        ImportDocuments.documents["1"] = ImportDocuments.Document("a.cbz", 3L, null, "ab".toByteArray())

        localImport.import(listOf(group("Foo")), addToLibrary = false, categoryIds = emptyList())
            .problems.map { it.reason } shouldBe listOf(ImportProblem.Reason.CopyFailed)
    }

    @Test
    fun `a loose book picked to join is never deleted`() = runTest {
        File(root, "Book.epub").writeText("book")

        localImport.import(
            listOf(localImport.inspect(group("Other"), target = "Book.epub")),
            addToLibrary = false,
            categoryIds = emptyList(),
        )

        File(root, "Book.epub").readText() shouldBe "book"
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
        // A book takes the series and its volume; a comic keeps its own name, which names its chapter.
        private val NEW_NAME = mapOf(ContentType.MANGA to "a.cbz", ContentType.NOVELS to "Foo - Vol. 1.epub")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
