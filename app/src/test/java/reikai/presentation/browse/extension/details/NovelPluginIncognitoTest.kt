package reikai.presentation.browse.extension.details

import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.source.SourceKey
import reikai.novel.host.LnPluginInfo
import reikai.novel.install.LnPluginHarness
import reikai.presentation.recents.EmittingPreferenceStore

/** A plugin's page carries its own incognito switch, as an extension's page does, under the plugin's key. */
class NovelPluginIncognitoTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
        coEvery { it.host.inspectPlugin(any(), any(), any(), any(), any()) } returns
            LnPluginInfo(id = ID, name = ID, site = "https://novelbin.test/")
    }
    private val sourcePreferences = SourcePreferences(EmittingPreferenceStore())
    private val getIncognitoState = mockk<GetIncognitoState> {
        coEvery { incognitoKey(SourceKey.Novel(ID)) } returns SourceKey.Novel(ID).serialize()
    }

    @Test
    fun `switching incognito on shows on the page`() = runTest {
        harness.installer.ensureLoaded()
        val model = NovelPluginDetailsViewModel(
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
        )
        backgroundScope.launch { model.state.collect {} }
        val before = model.state.first { it is NovelPluginDetailsViewModel.State.Success }

        model.setIncognito(true)

        // Any change, so a switch that lands wrong fails here rather than hanging.
        (model.state.first { it != before } as NovelPluginDetailsViewModel.State.Success).isIncognito shouldBe true
    }

    private companion object {
        const val ID = "novelbin"
        const val URL = "https://repo.test/novelbin.js"
    }
}
