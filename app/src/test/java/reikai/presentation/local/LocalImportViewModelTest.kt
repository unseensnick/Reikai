package reikai.presentation.local

import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

class LocalImportViewModelTest {

    private val first = mockk<Uri>()
    private val second = mockk<Uri>()
    private val broken = mockk<Uri>()
    private val importing = CompletableDeferred<Unit>()
    private val importStarted = CompletableDeferred<Unit>()

    // Counted here rather than with coVerify, which records the stubbed Dispatchers getters with the call.
    private var looks = 0

    // The file system and the database are the boundary: previews answer from a table, imports wait on a gate.
    private val localImport = mockk<LocalImport> {
        coEvery { inspect(any(), any()) } answers {
            looks++
            firstArg()
        }
        coEvery { preview(listOf(first)) } returns (listOf(group("a", first)) to emptyList())
        coEvery { preview(listOf(second)) } returns (listOf(group("b", second)) to emptyList())
        coEvery { preview(listOf(broken)) } returns
            (emptyList<ImportGroup>() to listOf(ImportProblem("bad.epub", ImportProblem.Reason.Protected, broken)))
        coEvery { import(any(), any(), any()) } coAnswers {
            importStarted.complete(Unit)
            importing.await()
            ImportResult(emptyList(), emptyList())
        }
    }
    private val categoriesRead = CompletableDeferred<BatchCategories>()
    private val mangaAdder = mockk<MangaLibraryAdder> {
        coEvery { resolveBatchCategories() } coAnswers
            { categoriesRead.await() }
    }

    private val novelAdder = mockk<NovelLibraryAdder> {
        coEvery { resolveBatchCategories() } returns BatchCategories.Default(listOf(7L))
    }

    private lateinit var appScope: CoroutineScope

    // The app scope is the test's background scope, so the test both drives and ends what runs there.
    private fun TestScope.model(): LocalImportViewModel {
        // The tests stub with coEvery, which leaves Default answering a bare mock.
        restubDefaultDispatcher()
        appScope = backgroundScope
        return LocalImportViewModel(
            initialUris = emptyList(),
            localImport = localImport,
            combineLocalVolumes = mockk(),
            novelAdder = novelAdder,
            mangaAdder = mangaAdder,
            appScope = appScope,
        )
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        resetVirtualDispatchers()
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
        // The import, not just the tap: what it copies is chosen once reads settle, after the tap.
        importStarted.await()
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
    fun `files added while the screen is open join its picks`() = runTest {
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }

        model.add(listOf(second))
        val after = model.state.first { it.groups.size == 2 }
        model.viewModelScope.cancel()

        after.groups.map { it.key } shouldBe listOf("a", "b")
    }

    @Test
    fun `a slower look at the old type does not write over the series found for the new type`() = runTest {
        val slowLook = CompletableDeferred<Unit>()
        val slowGate = CompletableDeferred<Unit>()
        coEvery { localImport.preview(listOf(first)) } returns
            (listOf(group("a", first).copy(canBeEitherType = true)) to emptyList())
        coEvery { localImport.inspect(any(), any()) } coAnswers {
            val g = firstArg<ImportGroup>()
            if (g.type == ContentType.MANGA) {
                slowLook.complete(Unit)
                slowGate.await()
                g.copy(existing = "manga a")
            } else {
                g.copy(existing = "novel a")
            }
        }
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }
        model.rename("a", "a")
        slowLook.await()

        model.setType("a", ContentType.NOVELS)
        model.state.first { s -> s.groups.single().existing == "novel a" }
        slowGate.complete(Unit)
        model.settle()

        model.state.value.groups.single().existing shouldBe "novel a"
    }

    @Test
    fun `confirming categories does not copy a group shared in that still asks about a newer copy`() = runTest {
        val imported = Collections.synchronizedList(mutableListOf<ImportGroup>())
        coEvery { localImport.preview(listOf(second)) } returns
            (listOf(group("b", second).copy(existing = "b", newerCopies = mapOf(second to "old.cbz"))) to emptyList())
        coEvery { localImport.import(any(), any(), any()) } answers {
            imported += firstArg<List<ImportGroup>>()
            ImportResult(emptyList(), emptyList())
        }
        categoriesRead.complete(BatchCategories.Ask(emptyList()))
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }
        model.startImport()
        model.state.first { it.pickCategories != null }
        model.add(listOf(second))
        model.state.first { s -> s.groups.any { it.asksAboutNewerCopies } }

        model.confirmCategories(ContentType.MANGA, emptyList())
        model.settle()

        imported.filter { it.asksAboutNewerCopies } shouldBe emptyList()
    }

    /** The second look at a group a share grew is what finds its newer copy, so confirming waits for it. */
    @Test
    fun `confirming categories waits for a grown group's second look before copying it`() = runTest {
        val more = mockk<Uri>()
        val look = CompletableDeferred<Unit>()
        val imported = Collections.synchronizedList(mutableListOf<ImportGroup>())
        coEvery { localImport.preview(listOf(more)) } returns (listOf(group("a", more)) to emptyList())
        coEvery { localImport.inspect(any(), any()) } coAnswers {
            look.await()
            firstArg<ImportGroup>().copy(existing = "a", newerCopies = mapOf(more to "old.cbz"))
        }
        coEvery { localImport.import(any(), any(), any()) } answers {
            imported += firstArg<List<ImportGroup>>()
            ImportResult(emptyList(), emptyList())
        }
        categoriesRead.complete(BatchCategories.Ask(emptyList()))
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }
        model.startImport()
        model.state.first { it.pickCategories != null }
        model.add(listOf(more))
        model.state.first { s -> s.groups.single().files.size == 2 }

        model.confirmCategories(ContentType.MANGA, emptyList())
        // Long enough for a confirm that does not wait to copy the group before its second look answers.
        withTimeoutOrNull(1.seconds) { model.state.first { !it.isImporting } }
        look.complete(Unit)
        model.settle()

        imported shouldBe emptyList()
    }

    /** The types to ask were picked before the share landed, so the novel had no rule asked for it. */
    @Test
    fun `a content type shared in while the categories are read is imported under its own rule`() = runTest {
        val categories = Collections.synchronizedMap(mutableMapOf<ContentType, List<Long>>())
        coEvery { localImport.preview(listOf(second)) } returns
            (listOf(group("b", second).copy(type = ContentType.NOVELS)) to emptyList())
        coEvery { localImport.import(any(), any(), any()) } answers {
            categories[firstArg<List<ImportGroup>>().first().type] = thirdArg()
            ImportResult(emptyList(), emptyList())
        }
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }
        model.startImport()
        model.add(listOf(second))
        model.state.first { it.groups.size == 2 && !it.isReading }

        categoriesRead.complete(BatchCategories.Default(emptyList()))
        model.state.first { it.result != null }
        model.settle()

        categories[ContentType.NOVELS] shouldBe listOf(7L)
    }

    /** Import is offered while a read runs, so a tap then waits for the read rather than doing nothing. */
    @Test
    fun `an import tapped while a file is still being read starts once the read finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { localImport.preview(listOf(second)) } coAnswers {
            gate.await()
            listOf(group("b", second)) to emptyList()
        }
        importing.complete(Unit)
        val model = model()
        model.setAddToLibrary(false)
        model.add(listOf(first))
        model.state.first { !it.isReading }
        model.add(listOf(second))

        model.startImport()
        gate.complete(Unit)
        val after = withTimeoutOrNull(5.seconds) { model.state.first { it.result != null } }
        model.settle()

        (after != null) shouldBe true
    }

    @Test
    fun `a read still running keeps the screen reading when an earlier one finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { localImport.preview(listOf(second)) } coAnswers {
            gate.await()
            listOf(group("b", second)) to emptyList()
        }
        val model = model()
        model.add(listOf(second))
        model.add(listOf(first))
        model.state.first { it.groups.isNotEmpty() }
        val reading = model.state.value.isReading
        gate.complete(Unit)
        model.settle()

        reading shouldBe true
    }

    @Test
    fun `a series the preview just looked at is not looked at again`() = runTest {
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }
        model.settle()

        looks shouldBe 0
    }

    @Test
    fun `a series a second pick grew is looked at again with all its files`() = runTest {
        val more = mockk<Uri>()
        coEvery { localImport.preview(listOf(more)) } returns (listOf(group("a", more)) to emptyList())
        coEvery { localImport.inspect(any(), any()) } answers { firstArg<ImportGroup>().copy(existing = "a") }
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }

        model.add(listOf(more))
        val after = model.state.first { s -> s.groups.single().existing == "a" }
        model.settle()

        after.groups.single().files.map { it.uri } shouldBe listOf(first, more)
    }

    /** A rename finds the series again, and a folder that cannot be read whole must not leave the old answer. */
    @Test
    fun `a group whose folder cannot be read on a second look leaves as a storage problem`() = runTest {
        val model = model()
        model.add(listOf(first))
        model.state.first { !it.isReading }
        coEvery { localImport.inspect(any(), any()) } throws FolderUnreadableException("Foo")

        model.rename("a", "Foo")
        val after = model.state.first { it.groups.isEmpty() }
        model.viewModelScope.cancel()

        after.problems shouldBe listOf(ImportProblem("Foo", ImportProblem.Reason.NoStorage))
    }

    /** Waits out everything the model launched, then ends it. */
    private suspend fun LocalImportViewModel.settle() {
        viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        appScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        viewModelScope.cancel()
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
