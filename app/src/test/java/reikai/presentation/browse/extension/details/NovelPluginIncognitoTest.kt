package reikai.presentation.browse.extension.details

import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.host.LnPluginInfo
import reikai.novel.install.LnPluginHarness
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import kotlin.time.Duration.Companion.seconds

/** A plugin's page carries its own incognito switch, as an extension's page does, under the plugin's key. */
class NovelPluginIncognitoTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
        coEvery { it.host.inspectPlugin(any(), any(), any(), any(), any()) } returns
            LnPluginInfo(id = ID, name = ID, site = "https://novelbin.test/")
    }
    private val sourcePreferences = SourcePreferences(EmittingPreferenceStore())

    // A plugin's key needs neither: the global switch is not read here and no app package is looked up.
    private val getIncognitoState = GetIncognitoState(mockk(relaxed = true), sourcePreferences, mockk(relaxed = true))

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @Test
    fun `switching incognito on shows on the page`() = runTest {
        harness.installer.ensureLoaded()
        val model = main.track(
            NovelPluginDetailsViewModel(
                ID,
                harness.manager,
                mockk(relaxed = true),
                harness.prefs,
                harness.installer,
                backgroundScope,
                mockk(),
                getIncognitoState,
                sourcePreferences,
                ToggleIncognito(sourcePreferences),
            ),
        )
        backgroundScope.launch { model.state.collect {} }
        model.state.first { it is NovelPluginDetailsViewModel.State.Success }

        model.setIncognito(true)
        // The save runs on IO, off the test clock, so only it is waited for, in real time; the page then must
        // already show it, so a switch that lands wrong fails on the assertion rather than a timeout.
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { sourcePreferences.incognitoExtensions.changes().first { it.isNotEmpty() } }
        }
        advanceUntilIdle()

        (model.state.value as NovelPluginDetailsViewModel.State.Success).isIncognito shouldBe true
    }

    private companion object {
        const val ID = "novelbin"
        const val URL = "https://repo.test/novelbin.js"
    }
}
