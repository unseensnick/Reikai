package reikai.presentation.browse.migrate

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import reikai.novel.install.LnPluginHarness

class MigrateNovelSourcesViewModelTest {

    private val harness = LnPluginHarness(setOf("https://repo.test/plugin.js"))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Before the plugins load, the registry is empty, which drew every installed source as a stub. */
    @Test
    fun `an installed plugin's source is never listed as not installed`() = runTest {
        coEvery { harness.host.loadPlugin(any(), any(), any(), any()) } returns LnPluginHarness.info("plugin")
        val repository = mockk<NovelRepository> {
            every { getSourcesWithLibraryNovelAsFlow() } returns flowOf(listOf("plugin" to 1L))
        }
        val model = MigrateNovelSourcesViewModel(repository, harness.manager, harness.prefs)

        val rows = model.sources.filterNotNull().first()
        model.viewModelScope.cancel()

        rows.single().isInstalled shouldBe true
    }
}
