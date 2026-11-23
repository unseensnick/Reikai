package reikai.presentation.browse.source

import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.ToggleNovelSource
import reikai.novel.install.LnPluginInstaller
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.novelApp
import reikai.novel.source.novelCatalogue
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.virtualDispatchers

/** Both novel source lists open with the installed apps' catalogues, not empty until the app scan lands. */
class NovelSourceListsLoadTest {

    private val scanned = CompletableDeferred<Unit>()
    private val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // The registry and the models work on IO, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    // Main stays set, so the extension can stop the models on it before it resets Main.
    @AfterEach
    fun tearDown() = unmockkStatic(Dispatchers::class)

    // The manager's app scope is the test's background scope, so the test both drives and ends it.
    private fun TestScope.manager() = NovelSourceManager(
        scope = backgroundScope,
        installer = { mockk<LnPluginInstaller>(relaxed = true) },
        extensionManager = mockk<ExtensionManager> {
            every { loadedNovelExtensionsFlow } returns flow {
                scanned.await()
                emit(listOf(novelApp(novelCatalogue(7L))))
            }
        },
        prefs = NovelPreferences(EmittingPreferenceStore()),
    )

    @Test
    fun `the Sources list opens with the novel apps' catalogues`() = runTest {
        val model = main.track(NovelSourcesViewModel(manager(), preferences, ToggleNovelSource(preferences)))

        firstAfterScan { model.sources.filterNotNull().first() }.map { it.source.id } shouldBe listOf("tachiyomi:7")
    }

    @Test
    fun `the Sources filter opens with the novel apps' catalogues`() = runTest {
        val model = main.track(
            NovelSourcesFilterViewModel(
                manager(),
                preferences,
                ToggleNovelSource(preferences),
                registries = mockk<LnRepoRegistries> { every { results } returns flowOf(emptyMap()) },
                extensionManager = mockk<ExtensionManager> {
                    every { availableNovelExtensionsFlow } returns MutableStateFlow(emptyList())
                },
            ),
        )

        firstAfterScan { model.state.filterIsInstance<NovelSourcesFilterViewModel.State.Success>().first() }
            .items.flatMap { it.second }.map { it.id } shouldBe listOf("tachiyomi:7")
    }

    // The scan lands only after every other queued task has run, so a list not waiting for it has
    // already drawn without the apps.
    private suspend fun <T> firstAfterScan(block: suspend () -> T): T = coroutineScope {
        launch {
            delay(SCAN_DELAY_MS)
            scanned.complete(Unit)
        }
        block()
    }

    private companion object {
        const val SCAN_DELAY_MS = 200L
    }
}
