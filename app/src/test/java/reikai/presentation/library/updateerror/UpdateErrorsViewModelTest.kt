package reikai.presentation.library.updateerror

import android.content.Context
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
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
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.library.ContentType

class UpdateErrorsViewModelTest {

    private val workManager = mockk<WorkManager>()
    private val context = mockk<Context>()
    private var novelStarted = false

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(WORK_MANAGER_EXTENSIONS)
        every { context.workManager } returns workManager
        mockkObject(LibraryUpdateJob.Companion, NovelUpdateJob.Companion)
        every { NovelUpdateJob.startNow(workManager, null) } answers {
            novelStarted = true
            true
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(LibraryUpdateJob.Companion, NovelUpdateJob.Companion)
        unmockkStatic(WORK_MANAGER_EXTENSIONS)
        Dispatchers.resetMain()
    }

    @Test
    fun `an already running manga update still reports the novel one started`() = runTest {
        every { LibraryUpdateJob.startNow(workManager, null) } returns false

        viewModel(ContentType.ALL).retry(context) shouldBe true
    }

    @Test
    fun `a started manga update does not skip the novel one`() = runTest {
        every { LibraryUpdateJob.startNow(workManager, null) } returns true

        viewModel(ContentType.ALL).retry(context)

        novelStarted shouldBe true
    }

    @Test
    fun `updates refused on both libraries report nothing started`() = runTest {
        every { LibraryUpdateJob.startNow(workManager, null) } returns false
        every { NovelUpdateJob.startNow(workManager, null) } returns false

        viewModel(ContentType.ALL).retry(context) shouldBe false
    }

    @Test
    fun `the Manga chip leaves the novel update alone`() = runTest {
        every { LibraryUpdateJob.startNow(workManager, null) } returns true
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

        viewModel.selected() shouldBe setOf("m1")
    }

    private suspend fun selectingOne(chip: ContentType) = viewModel(chip).also {
        it.state.first { state -> state is UpdateErrorsScreenState.Success }
        it.toggleSelection("m1")
    }

    private fun UpdateErrorsViewModel.selected() = (state.value as UpdateErrorsScreenState.Success).selected

    private fun viewModel(chip: ContentType) = UpdateErrorsViewModel(
        initialContentType = chip,
        getLibraryUpdateErrors = mockk { every { subscribeAll() } returns flowOf(emptyList()) },
        deleteLibraryUpdateErrors = mockk(relaxed = true),
        getNovelUpdateErrors = mockk { every { subscribeAll() } returns flowOf(emptyList()) },
        deleteNovelUpdateErrors = mockk(relaxed = true),
        sourceManager = mockk(relaxed = true),
        novelSourceManager = mockk(relaxed = true),
    )

    private companion object {
        const val WORK_MANAGER_EXTENSIONS = "eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt"
    }
}
