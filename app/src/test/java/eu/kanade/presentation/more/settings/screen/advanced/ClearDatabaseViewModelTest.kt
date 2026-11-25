package eu.kanade.presentation.more.settings.screen.advanced

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryManga
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.SourceWithCount

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
            appScope = testAppScope(),
            mangaRepository = mockk(),
            historyRepository = mockk(),
            novelHistoryRepository = mockk(),
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

    @Test
    fun `an uninstalled plugin keeps the icon it was last seen with`() = runTest {
        val model = ClearDatabaseViewModel(
            appScope = testAppScope(),
            mangaRepository = mockk(),
            historyRepository = mockk(),
            novelHistoryRepository = mockk(),
            getSourcesWithNonLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
            novelRepository = mockk<NovelRepository> {
                every { getSourcesWithNonLibraryNovelAsFlow() } returns flowOf(listOf("p1" to 2L))
            },
            novelSourceManager = manager(
                seen = mapOf("p1" to LnSourceIdentity(name = "Old Site", iconUrl = "https://i/p1.png")),
            ),
        )

        val ready = model.state.filterIsInstance<ClearDatabaseViewModel.State.Ready>().first()
        model.viewModelScope.cancel()

        ready.novelItems.single().iconUrl shouldBe "https://i/p1.png"
    }

    /** Its remembered icon stays in the state; the row draws the missing-source icon over it, as for a manga stub. */
    @Test
    fun `an uninstalled plugin is marked not installed`() = runTest {
        val model = ClearDatabaseViewModel(
            appScope = testAppScope(),
            mangaRepository = mockk(),
            historyRepository = mockk(),
            novelHistoryRepository = mockk(),
            getSourcesWithNonLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
            novelRepository = mockk<NovelRepository> {
                every { getSourcesWithNonLibraryNovelAsFlow() } returns flowOf(listOf("p1" to 2L))
            },
            novelSourceManager = manager(
                seen = mapOf("p1" to LnSourceIdentity(name = "Old Site", iconUrl = "https://i/p1.png")),
            ),
        )

        val ready = model.state.filterIsInstance<ClearDatabaseViewModel.State.Ready>().first()
        model.viewModelScope.cancel()

        ready.novelItems.single().isInstalled shouldBe false
    }

    @Test
    fun `an uninstalled plugin keeps the language it was last seen with`() = runTest {
        val model = ClearDatabaseViewModel(
            appScope = testAppScope(),
            mangaRepository = mockk(),
            historyRepository = mockk(),
            novelHistoryRepository = mockk(),
            getSourcesWithNonLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
            novelRepository = mockk<NovelRepository> {
                every { getSourcesWithNonLibraryNovelAsFlow() } returns flowOf(listOf("p1" to 2L))
            },
            novelSourceManager = manager(seen = mapOf("p1" to LnSourceIdentity(name = "Old Site", lang = "ja"))),
        )

        val ready = model.state.filterIsInstance<ClearDatabaseViewModel.State.Ready>().first()
        model.viewModelScope.cancel()

        ready.novelItems.single().lang shouldBe "ja"
    }

    @Test
    fun `a tap on a manga source selects it`() = runTest {
        val source = Source(id = 1L, lang = "en", name = "Site", supportsLatest = false, isStub = false)
        val model = readyModel(manga = listOf(SourceWithCount(source, 2L)))

        model.toggleSelection(source)
        model.viewModelScope.cancel()

        (model.state.value as ClearDatabaseViewModel.State.Ready).selection shouldBe listOf(1L)
    }

    @Test
    fun `a tap on a novel source selects it`() = runTest {
        val model = readyModel(novels = listOf("p1" to 2L))

        model.toggleNovelSelection("p1")
        model.viewModelScope.cancel()

        (model.state.value as ClearDatabaseViewModel.State.Ready).novelSelection shouldBe listOf("p1")
    }

    @Test
    fun `a second tap on a manga source deselects it`() = runTest {
        val source = Source(id = 1L, lang = "en", name = "Site", supportsLatest = false, isStub = false)
        val model = readyModel(manga = listOf(SourceWithCount(source, 2L)))

        repeat(2) { model.toggleSelection(source) }
        model.viewModelScope.cancel()

        (model.state.value as ClearDatabaseViewModel.State.Ready).selection shouldBe emptyList()
    }

    @Test
    fun `a second tap on a novel source deselects it`() = runTest {
        val model = readyModel(novels = listOf("p1" to 2L))

        repeat(2) { model.toggleNovelSelection("p1") }
        model.viewModelScope.cancel()

        (model.state.value as ClearDatabaseViewModel.State.Ready).novelSelection shouldBe emptyList()
    }

    /** Clear database purges reset history for both types, whichever sources were picked. */
    @Test
    fun `a clear removes reset novel history`() = runTest {
        var novelResetsRemoved = 0
        val model = ClearDatabaseViewModel(
            appScope = testAppScope(),
            mangaRepository = mockk(),
            historyRepository = mockk(relaxed = true),
            novelHistoryRepository = mockk<NovelHistoryRepository> {
                coEvery { deleteResetNovelHistory() } answers { novelResetsRemoved += 1 }
            },
            getSourcesWithNonLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
            novelRepository = mockk<NovelRepository> {
                every { getSourcesWithNonLibraryNovelAsFlow() } returns flowOf(emptyList())
            },
            novelSourceManager = manager(seen = emptyMap()),
        )
        model.state.filterIsInstance<ClearDatabaseViewModel.State.Ready>().first()

        model.removeMangaBySourceId(keepReadManga = false)
        model.viewModelScope.cancel()

        novelResetsRemoved shouldBe 1
    }

    private suspend fun readyModel(
        manga: List<SourceWithCount> = emptyList(),
        novels: List<Pair<String, Long>> = emptyList(),
    ) = ClearDatabaseViewModel(
        appScope = testAppScope(),
        mangaRepository = mockk(),
        historyRepository = mockk(),
        novelHistoryRepository = mockk(),
        getSourcesWithNonLibraryManga = mockk { every { subscribe() } returns flowOf(manga) },
        novelRepository = mockk<NovelRepository> {
            every { getSourcesWithNonLibraryNovelAsFlow() } returns flowOf(novels)
        },
        novelSourceManager = manager(seen = emptyMap()),
    ).also { model -> model.state.filterIsInstance<ClearDatabaseViewModel.State.Ready>().first() }

    private fun manager(seen: Map<String, LnSourceIdentity>) = NovelSourceManager(
        scope = testAppScope(),
        installer = { mockk<LnPluginInstaller>(relaxed = true) },
        extensionManager = mockk<ExtensionManager> {
            every { loadedNovelExtensionsFlow } returns
                MutableStateFlow(emptyList())
        },
        prefs = NovelPreferences(EmittingPreferenceStore()).apply { seenNovelSources().set(seen) },
    )
}
