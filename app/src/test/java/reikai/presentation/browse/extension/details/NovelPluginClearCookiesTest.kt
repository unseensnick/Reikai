package reikai.presentation.browse.extension.details

import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.host.LnPluginInfo
import reikai.novel.install.LnPluginHarness

/** A plugin's page clears its site's cookies, as an extension's page does; plugin requests share the jar. */
class NovelPluginClearCookiesTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
        coEvery { it.host.inspectPlugin(any(), any(), any(), any(), any()) } returns
            LnPluginInfo(id = ID, name = ID, site = SITE)
    }

    private val jar = mockk<AndroidCookieJar>(relaxed = true)

    @Test
    fun `clearing cookies drops the plugin site's cookies`() = runTest {
        harness.installer.ensureLoaded()
        val model = NovelPluginDetailsViewModel(
            ID,
            harness.manager,
            mockk(relaxed = true),
            harness.prefs,
            harness.installer,
            backgroundScope,
            mockk<NetworkHelper> { every { cookieJar } returns jar },
        )
        backgroundScope.launch { model.state.collect {} }
        model.state.first { it is NovelPluginDetailsViewModel.State.Success }

        model.clearCookies()

        verify { jar.remove(SITE.toHttpUrl(), any(), any()) }
    }

    private companion object {
        const val ID = "novelbin"
        const val URL = "https://repo.test/novelbin.js"
        const val SITE = "https://novelbin.test/"
    }
}
