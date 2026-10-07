package reikai.presentation.library.updateerror

import android.content.Context
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.util.system.workManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.novel.update.NovelUpdateWorker
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.updateerror.LibraryUpdateError
import reikai.domain.novel.updateerror.NovelUpdateError

class UpdateErrorsViewModelTest {

    private val workManager = mockk<WorkManager>()
    private val context = mockk<Context>()
    private var novelStarted = false

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(WORK_MANAGER_EXTENSIONS)
        every { context.workManager } returns workManager
        mockkObject(LibraryUpdateWorker.Companion, NovelUpdateWorker.Companion)
        every { NovelUpdateWorker.startNow(workManager, null) } answers {
            novelStarted = true
            true
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(LibraryUpdateWorker.Companion, NovelUpdateWorker.Companion)
        unmockkStatic(WORK_MANAGER_EXTENSIONS)
        Dispatchers.resetMain()
    }

    @Test
    fun `an already running manga update still reports the novel one started`() = runTest {
        every { LibraryUpdateWorker.startNow(workManager, null) } returns false

        viewModel(ContentType.ALL).retry(context) shouldBe true
    }

    @Test
    fun `a started manga update does not skip the novel one`() = runTest {
        every { LibraryUpdateWorker.startNow(workManager, null) } returns true

        viewModel(ContentType.ALL).retry(context)

        novelStarted shouldBe true
    }

    @Test
    fun `updates refused on both libraries report nothing started`() = runTest {
        every { LibraryUpdateWorker.startNow(workManager, null) } returns false
        every { NovelUpdateWorker.startNow(workManager, null) } returns false

        viewModel(ContentType.ALL).retry(context) shouldBe false
    }

    @Test
    fun `the Manga chip leaves the novel update alone`() = runTest {
        every { LibraryUpdateWorker.startNow(workManager, null) } returns true
        val viewModel = viewModel(ContentType.MANGA)
        viewModel.state.first { it is UpdateErrorsScreenState.Success }

        viewModel.retry(context)

        novelStarted shouldBe false
    }

    @Test
    fun `flipping the chip drops the selection`() = runTest {
        val viewModel = selectingOne(ContentType.ALL)

        viewModel.setContentType(ContentType.NOVELS)

        viewModel.selected() shouldBe emptySet()
    }

    /** The tab row fires again on the chip already shown, which is not a flip. */
    @Test
    fun `re-selecting the chip shown keeps the selection`() = runTest {
        val viewModel = selectingOne(ContentType.ALL)

        viewModel.setContentType(ContentType.ALL)

        viewModel.selected() shouldBe setOf(EntryId.Manga(1L))
    }

    /** Rows are drawn under their message headers, so the range runs in that order, not the feed's. */
    @Test
    fun `a long press selects every row drawn between the last touched one and it`() = runTest {
        val viewModel = viewModel(
            ContentType.ALL,
            manga = listOf(mangaError(1L, "timeout"), mangaError(2L, "404")),
            novels = listOf(novelError(1L, "404"), novelError(2L, "timeout")),
        )
        viewModel.state.first { it is UpdateErrorsScreenState.Success }

        viewModel.toggleSelection(EntryId.Manga(1L))
        viewModel.rangeSelection(EntryId.Manga(2L))

        viewModel.selected() shouldBe setOf(EntryId.Manga(1L), EntryId.Novel(2L), EntryId.Manga(2L))
    }

    private suspend fun selectingOne(chip: ContentType) = viewModel(chip).also {
        it.state.first { state -> state is UpdateErrorsScreenState.Success }
        it.toggleSelection(EntryId.Manga(1L))
    }

    private fun UpdateErrorsViewModel.selected() =
        (state.value as UpdateErrorsScreenState.Success).selection.selection

    private fun viewModel(
        chip: ContentType,
        manga: List<LibraryUpdateError> = emptyList(),
        novels: List<NovelUpdateError> = emptyList(),
    ) = UpdateErrorsViewModel(
        initialContentType = chip,
        getLibraryUpdateErrors = mockk { every { subscribeAll() } returns flowOf(manga) },
        deleteLibraryUpdateErrors = mockk(relaxed = true),
        getNovelUpdateErrors = mockk { every { subscribeAll() } returns flowOf(novels) },
        deleteNovelUpdateErrors = mockk(relaxed = true),
        sourceManager = mockk(relaxed = true),
        novelSourceManager = mockk(relaxed = true),
    )

    private fun mangaError(id: Long, message: String) = LibraryUpdateError(
        errorId = id,
        mangaId = id,
        mangaTitle = "m$id",
        sourceId = 0L,
        thumbnailUrl = null,
        coverLastModified = 0L,
        message = message,
        lastUpdate = 0L,
    )

    private fun novelError(id: Long, message: String) = NovelUpdateError(
        errorId = id,
        novelId = id,
        novelTitle = "n$id",
        source = "src",
        novelUrl = "/n$id",
        thumbnailUrl = null,
        coverLastModified = 0L,
        message = message,
        lastUpdate = 0L,
    )

    private companion object {
        const val WORK_MANAGER_EXTENSIONS = "eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt"
    }
}
