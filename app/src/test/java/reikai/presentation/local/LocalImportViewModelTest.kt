package reikai.presentation.local

import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.local.ImportFile
import reikai.data.local.ImportGroup
import reikai.data.local.ImportProblem
import reikai.data.local.ImportResult
import reikai.data.local.LocalImport
import reikai.domain.library.ContentType
import reikai.presentation.browse.BatchCategories
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.storage.FolderUnreadableException
import reikai.util.testAppScope

class LocalImportViewModelTest {

    private val first = mockk<Uri>()
    private val second = mockk<Uri>()
    private val broken = mockk<Uri>()
    private val importing = CompletableDeferred<Unit>()

    // The file system and the database are the boundary: previews answer from a table, imports wait on a gate.
    private val localImport = mockk<LocalImport> {
        coEvery { inspect(any(), any()) } answers { firstArg() }
        coEvery { preview(listOf(first)) } returns (listOf(group("a", first)) to emptyList())
        coEvery { preview(listOf(second)) } returns (listOf(group("b", second)) to emptyList())
        coEvery { preview(listOf(broken)) } returns
            (emptyList<ImportGroup>() to listOf(ImportProblem("bad.epub", ImportProblem.Reason.Protected, broken)))
        coEvery { import(any(), any(), any()) } coAnswers {
            importing.await()
            ImportResult(emptyList(), emptyList())
        }
    }
    private val categoriesRead = CompletableDeferred<BatchCategories>()
    private val mangaAdder = mockk<MangaLibraryAdder> {
        coEvery { resolveBatchCategories() } coAnswers
            { categoriesRead.await() }
    }

    private fun model() = LocalImportViewModel(
        initialUris = emptyList(),
        localImport = localImport,
        combineLocalVolumes = mockk(),
        novelAdder = mockk<NovelLibraryAdder>(),
        mangaAdder = mangaAdder,
        appScope = testAppScope(),
        handOff = ImportHandOff(),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Import turns off as it is tapped, before the category read answers`() = runTest {
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }

        model.startImport()
        val isImporting = model.state.value.isImporting
        model.viewModelScope.cancel()

        isImporting shouldBe true
    }

    @Test
    fun `files added while an import runs stay once it finishes`() = runTest {
        val model = model()
        model.setAddToLibrary(false)
        model.add(listOf(first))
        model.state.first { !it.isReading }
        model.startImport()
        model.state.first { it.isImporting }
        model.add(listOf(second))
        model.state.first { !it.isReading }

        importing.complete(Unit)
        val after = model.state.first { it.result != null }
        model.viewModelScope.cancel()

        after.groups.map { it.key } shouldBe listOf("b")
    }

    @Test
    fun `a file that failed once is listed once when picked again`() = runTest {
        val model = model()
        model.add(listOf(broken))
        model.state.first { !it.isReading }
        model.add(listOf(broken))
        val after = model.state.first { !it.isReading }
        model.viewModelScope.cancel()

        after.problems.size shouldBe 1
    }

    @Test
    fun `files handed over while the screen is open join its picks`() = runTest {
        val handOff = ImportHandOff()
        val model = LocalImportViewModel(
            initialUris = emptyList(),
            localImport = localImport,
            combineLocalVolumes = mockk(),
            novelAdder = mockk(),
            mangaAdder = mangaAdder,
            appScope = testAppScope(),
            handOff = handOff,
        )
        model.add(listOf(first))
        model.state.first { !it.isReading }

        handOff.send(listOf(second))
        val after = model.state.first { it.groups.size == 2 }
        model.viewModelScope.cancel()

        after.groups.map { it.key } shouldBe listOf("a", "b")
    }

    /** A rename finds the series again, and a folder that cannot be read whole must not leave the old answer. */
    @Test
    fun `a group whose folder cannot be read on a second look leaves as a storage problem`() = runTest {
        coEvery { localImport.inspect(any(), any()) } answers { firstArg() } andThenThrows
            FolderUnreadableException("Foo")
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }

        model.rename("a", "Foo")
        val after = model.state.first { it.groups.isEmpty() }
        model.viewModelScope.cancel()

        after.problems shouldBe listOf(ImportProblem("Foo", ImportProblem.Reason.NoStorage))
    }

    private fun group(key: String, uri: Uri) = ImportGroup(
        key = key,
        type = ContentType.MANGA,
        title = key,
        author = null,
        cover = null,
        files = listOf(ImportFile(uri, "$key.cbz", 1L)),
        canBeEitherType = false,
    )
}
