package reikai.presentation.browse.migrate

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.novel.NovelRepository
import reikai.novel.install.LnPluginHarness
import reikai.presentation.MainDispatcherExtension

class MigrateNovelSourcesViewModelTest {

    private val harness = LnPluginHarness(setOf("https://repo.test/plugin.js"))

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    /** Before the plugins load, the registry is empty, which drew every installed source as a stub. */
    @Test
    fun `an installed plugin's source is never listed as not installed`() = runTest {
        coEvery { harness.host.loadPlugin(any(), any(), any(), any()) } returns LnPluginHarness.info("plugin")
        val repository = mockk<NovelRepository> {
            every { getSourcesWithLibraryNovelAsFlow() } returns flowOf(listOf("plugin" to 1L))
        }
        val model = main.track(MigrateNovelSourcesViewModel(repository, harness.manager, harness.prefs))

        val rows = model.sources.filterNotNull().first()

        rows.single().isInstalled shouldBe true
    }
}
