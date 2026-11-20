package reikai.presentation.browse.extension.details

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.install.LnPluginHarness
import reikai.presentation.recents.EmittingPreferenceStore
import kotlin.time.Duration.Companion.seconds

/** An uninstall confirmed on the page is a delete, so leaving the page must not cut it off. */
class NovelPluginDetailsUninstallTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
        coEvery { it.host.inspectPlugin(any(), any(), any(), any(), any()) } returns LnPluginHarness.info(ID)
    }

    @Test
    fun `an uninstall waiting behind an update still runs after the page closes`() = runTest {
        val downloading = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { harness.loader.download(URL) } coAnswers {
            downloading.complete(Unit)
            gate.await()
            "new script"
        }
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

        val installed = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5.seconds) { harness.prefs.installedPluginUrls().changes().first { URL !in it } }
        } ?: harness.prefs.installedPluginUrls().get()
        installed shouldNotContain URL
    }

    private companion object {
        const val ID = "novelbin"
        const val URL = "https://repo.test/novelbin.js"
    }
}
