package reikai.presentation.browse.extension.details

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.install.LnPluginHarness
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers
import kotlin.time.Duration.Companion.seconds

/** An uninstall confirmed on the page is a delete, so leaving the page must not cut it off. */
class NovelPluginDetailsUninstallTest {

    // Built on first use, after setUp, so its app scope takes the virtual IO.
    private val harness by lazy {
        LnPluginHarness(setOf(URL)).also {
            it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
            coEvery { it.host.inspectPlugin(any(), any(), any(), any(), any()) } returns LnPluginHarness.info(ID)
        }
    }

    // The install and the uninstall run on IO, which this puts on the test scheduler.
    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `an uninstall waiting behind an update still runs after the page closes`() = runTest {
        val downloading = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val loader = harness.loader
        coEvery { loader.download(URL) } coAnswers {
            downloading.complete(Unit)
            gate.await()
            "new script"
        }
        restubDefaultDispatcher()
        backgroundScope.launch { harness.installer.installFromUrl(URL) }
        downloading.await()

        val store = ViewModelStore()
        val model =
            NovelPluginDetailsViewModel(
                ID,
                harness.manager,
                mockk(relaxed = true),
                harness.prefs,
                harness.installer,
                backgroundScope,
                mockk(),
                mockk(relaxed = true),
                SourcePreferences(EmittingPreferenceStore()),
                mockk(relaxed = true),
            )
        store.put("details", model)
        model.uninstall()
        store.clear()
        gate.complete(Unit)

        val installed = withTimeoutOrNull(5.seconds) {
            harness.prefs.installedPluginUrls().changes().first { URL !in it }
        } ?: harness.prefs.installedPluginUrls().get()
        installed shouldNotContain URL
    }

    private companion object {
        const val ID = "novelbin"
        const val URL = "https://repo.test/novelbin.js"
    }
}
