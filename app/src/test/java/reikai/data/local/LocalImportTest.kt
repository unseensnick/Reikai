package reikai.data.local

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
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

    private var storageRoot: UniFile = UniFile.fromFile(root)!!

    // Storage, the source's own parse and the database are the boundary; each add's favorite write is refused.
    private val localImport = LocalImport(
        context = context,
        storageManager = mockk<StorageManager> {
            every { getLocalSourceDirectory() } answers { storageRoot }
            every { getLocalNovelsDirectory() } answers { storageRoot }
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
        journal = mockk(relaxed = true),
        localTombstones = NoChapterTombstones,
        novelAdder = mockk<NovelLibraryAdder> { coEvery { addToBatch(any(), any(), any()) } returns AddOutcome.Failed },
        mangaAdder = mockk<MangaLibraryAdder> { coEvery { addToBatch(any(), any()) } returns AddOutcome.Failed },
        networkToLocalManga = mockk {
            coEvery { this@mockk.invoke(any<Manga>()) } returns Manga.create().copy(id = 7L)
        },
        updateMangaFromRemote = mockk {
            coEvery { this@mockk.invoke(any<Manga>(), any(), any(), any(), any()) } returns Result.success(mockk())
        },
        seriesLocks = LocalSeriesLocks().apply { open() },
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
    fun `a newer copy the user chose to replace takes the old book's name and place`() = runTest {
        File(root, "Foo").mkdirs()
        File(root, "Foo/Old.cbz").writeText("old version")
        val group = localImport.inspect(group("Foo")).copy(
            newerCopies = mapOf(ImportDocuments.uri("1") to "Old.cbz"),
            onNewerCopy = DuplicateChoice.Replace,
        )

        localImport.import(listOf(group), addToLibrary = false, categoryIds = emptyList())

        (File(root, "Foo").list()!!.toList() to File(root, "Foo/Old.cbz").readText()) shouldBe
            (listOf("Old.cbz") to "abc")
    }

    @Test
    fun `a series folder on picked storage that cannot be listed whole takes no file`() = runTest {
        File(root, "Foo").mkdirs()
        File(root, "Foo/Old.cbz").writeText("old version")
        // No provider answers this picked folder's count, so its listing cannot be told whole.
        val picked = spyk(UniFile.fromFile(File(root, "Foo"))!!) {
            every { uri } returns Uri.parse("$PICKED/document/primary%3AManga%2FFoo")
        }
        storageRoot = spyk(UniFile.fromFile(root)!!) { every { findFile("Foo") } returns picked }

        localImport.import(listOf(group("Foo").copy(existing = "Foo")), addToLibrary = false, categoryIds = emptyList())

        File(root, "Foo").list()!!.toList() shouldBe listOf("Old.cbz")
    }

    @Test
    fun `an import waits on the user while a newer copy is unanswered`() {
        group("Foo").copy(existing = "Foo", newerCopies = mapOf(ImportDocuments.uri("1") to "Old.cbz"))
            .asksAboutNewerCopies shouldBe true
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
        private const val PICKED = "content://com.android.externalstorage.documents/tree/primary%3AManga"

        // A book takes the series and its volume; a comic keeps its own name, which names its chapter.
        private val NEW_NAME = mapOf(ContentType.MANGA to "a.cbz", ContentType.NOVELS to "Foo - Vol. 1.epub")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = listOf(ContentType.MANGA, ContentType.NOVELS)
    }
}
