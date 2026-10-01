package eu.kanade.presentation.more.settings.screen.advanced

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryManga

class ClearDatabaseViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A manga stub keeps the name it was stored with, so an uninstalled novel source keeps its last seen one. */
    @Test
    fun `an uninstalled novel source is listed by the name it was last seen with`() = runTest {
        val model = ClearDatabaseViewModel(
            mangaRepository = mockk(),
            historyRepository = mockk(),
            getSourcesWithNonLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
            novelRepository = mockk<NovelRepository> {
                every { getSourcesWithNonLibraryNovelAsFlow() } returns flowOf(listOf("p1" to 2L))
            },
            novelSourceManager = manager(seen = mapOf("p1" to LnSourceIdentity(name = "Old Site"))),
        )

        val ready = model.state.filterIsInstance<ClearDatabaseViewModel.State.Ready>().first()
        model.viewModelScope.cancel()

        ready.novelItems.single().name shouldBe "Old Site"
    }

    private fun manager(seen: Map<String, LnSourceIdentity>) = NovelSourceManager(
        installer = { mockk<LnPluginInstaller>(relaxed = true) },
        extensionManager = mockk<ExtensionManager> {
            every { loadedNovelExtensionsFlow } returns
                MutableStateFlow(emptyList())
        },
        prefs = mockk<NovelPreferences> {
            every { seenNovelSources() } returns mockk<Preference<Map<String, LnSourceIdentity>>> {
                every { get() } returns seen
            }
        },
    )
}
