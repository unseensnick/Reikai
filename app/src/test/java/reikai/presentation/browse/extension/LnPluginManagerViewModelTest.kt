package reikai.presentation.browse.extension

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import java.util.concurrent.atomic.AtomicInteger

/** The plugin manager fetches the repos only while the screen watches it, and once per reason to. */
class LnPluginManagerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val prefs = NovelPreferences(EmittingPreferenceStore())
    private val installer = mockk<LnPluginInstaller>(relaxed = true) {
        every { failures } returns MutableStateFlow(emptyMap())
        coEvery { fetchRepo(REPO) } returns listOf(ENTRY)
        coEvery { fetchRepo(OTHER_REPO) } returns emptyList()
    }
    private val manager = mockk<NovelSourceManager> { every { sources } returns flowOf(emptyList()) }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs.addedRepoUrls().set(setOf(REPO))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is fetched while no screen watches the model`() = runTest(dispatcher) {
        LnPluginManagerViewModel(manager, installer, prefs)

        advanceUntilIdle()

        coVerify(exactly = 0) { installer.fetchRepo(any()) }
    }

    @Test
    fun `a screen watching the model fetches each repo once`() = runTest(dispatcher) {
        val model = LnPluginManagerViewModel(manager, installer, prefs)

        val watching = launch { model.state.collect {} }
        advanceUntilIdle()
        watching.cancel()

        coVerify(exactly = 1) { installer.fetchRepo(REPO) }
    }

    @Test
    fun `the fetched plugins reach the state`() = runTest(dispatcher) {
        val model = LnPluginManagerViewModel(manager, installer, prefs)

        val watching = launch { model.state.collect {} }
        advanceUntilIdle()
        watching.cancel()

        model.state.value.available shouldBe listOf(ENTRY)
    }

    @Test
    fun `changing the added repos fetches the new set`() = runTest(dispatcher) {
        val model = LnPluginManagerViewModel(manager, installer, prefs)
        val watching = launch { model.state.collect {} }
        advanceUntilIdle()

        prefs.addedRepoUrls().set(setOf(OTHER_REPO))
        advanceUntilIdle()
        watching.cancel()

        coVerify(exactly = 1) { installer.fetchRepo(OTHER_REPO) }
    }

    /**
     * Two installs of one plugin share a temp file, so the second rename threw a spurious error. The
     * installs run on the IO dispatcher, so the test joins the jobs the taps started instead of
     * advancing the scheduler.
     */
    @Test
    fun `a second reinstall while the first runs does not start another install`() = runTest(dispatcher) {
        val installing = CompletableDeferred<Unit>()
        val installs = AtomicInteger()
        coEvery { installer.installFromUrl(any(), any()) } coAnswers {
            installs.incrementAndGet()
            installing.await()
            mockk()
        }
        val model = LnPluginManagerViewModel(manager, installer, prefs)
        val before = model.viewModelScope.coroutineContext.job.children.toSet()

        model.reinstall(FAILURE)
        model.reinstall(FAILURE)
        installing.complete(Unit)
        (model.viewModelScope.coroutineContext.job.children.toSet() - before).joinAll()

        installs.get() shouldBe 1
    }

    private companion object {
        const val REPO = "https://example.org/plugins.json"
        const val OTHER_REPO = "https://example.net/plugins.json"
        val ENTRY = LnRegistryEntry(
            id = "novelbin",
            name = "NovelBin",
            version = "1.0.0",
            site = "https://novelbin.example",
            lang = "English",
            url = "https://example.org/novelbin.js",
        )
        val FAILURE = LnPluginLoadFailure(
            url = ENTRY.url,
            pluginId = ENTRY.id,
            name = ENTRY.name,
            iconUrl = null,
            lang = null,
            version = ENTRY.version,
            reason = LnPluginLoadFailure.Reason.Missing,
        )
    }
}
