package reikai.presentation.browse.source

import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.ToggleNovelSource
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.novelApp
import reikai.novel.source.novelCatalogue
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference
import kotlin.time.Duration.Companion.seconds

/** Both novel source lists open with the installed apps' catalogues, not empty until the app scan lands. */
class NovelSourceListsLoadTest {

    private val scanned = CompletableDeferred<Unit>()
    private val seen = mockk<Preference<Map<String, LnSourceIdentity>>>(relaxed = true) {
        every { get() } returns emptyMap()
    }
    private val manager = NovelSourceManager(
        installer = { mockk<LnPluginInstaller>(relaxed = true) },
        extensionManager = mockk<ExtensionManager> {
            every { loadedNovelExtensionsFlow } returns flow {
                scanned.await()
                emit(listOf(novelApp(novelCatalogue(7L))))
            }
        },
        prefs = mockk<NovelPreferences> { every { seenNovelSources() } returns seen },
    )
    private val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @Test
    fun `the Sources list opens with the novel apps' catalogues`() = runTest {
        val model = main.track(NovelSourcesViewModel(manager, preferences, ToggleNovelSource(preferences)))

        firstAfterScan { model.sources.filterNotNull().first() }.map { it.source.id } shouldBe listOf("tachiyomi:7")
    }

    @Test
    fun `the Sources filter opens with the novel apps' catalogues`() = runTest {
        val model = main.track(NovelSourcesFilterViewModel(manager, preferences, ToggleNovelSource(preferences)))

        firstAfterScan { model.state.filterIsInstance<NovelSourcesFilterViewModel.State.Success>().first() }
            .items.flatMap { it.second }.map { it.id } shouldBe listOf("tachiyomi:7")
    }

    // The registry and the models work on the IO dispatcher, so this runs in real time. The scan lands
    // late enough that a list not waiting for it has already drawn without the apps.
    private suspend fun <T> firstAfterScan(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        launch {
            delay(SCAN_DELAY_MS)
            scanned.complete(Unit)
        }
        withTimeout(5.seconds) { block() }
    }

    private companion object {
        const val SCAN_DELAY_MS = 200L
    }
}
