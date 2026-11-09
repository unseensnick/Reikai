package reikai.novel.install

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo
import reikai.novel.host.LnPluginLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope

/**
 * A real installer and source manager over a mocked plugin host and script store, with every script
 * [installedUrls] names already stored. The host is left for each test to answer, so one can fail a
 * load, count loads or hold one on a gate.
 */
class LnPluginHarness(installedUrls: Set<String>, networkHelper: NetworkHelper = mockk()) {

    val prefs = NovelPreferences(EmittingPreferenceStore()).also { it.installedPluginUrls().set(installedUrls) }

    /** The script the host holds for each plugin id, as the installer last retained it. */
    val retained = mutableMapOf<String, String>()

    val host = mockk<LnPluginHost> {
        every { forgetWebStorage(any()) } just Runs
        every { retainPlugin(any(), any()) } answers { retained[firstArg()] = secondArg() }
    }

    val loader = mockk<LnPluginLoader>(relaxed = true) {
        coEvery { installed(any()) } returns "plugin source"
        coEvery { installedStylesheet(any()) } returns null
    }

    lateinit var installer: LnPluginInstaller
        private set

    val manager: NovelSourceManager = NovelSourceManager(
        testAppScope(),
        { installer },
        mockk<ExtensionManager> { every { loadedNovelExtensionsFlow } returns flowOf(emptyList()) },
        prefs,
    ).also { installer = LnPluginInstaller(networkHelper, loader, it, prefs, host) }

    companion object {
        fun info(id: String, version: String? = null) = LnPluginInfo(id = id, name = id, version = version)
    }
}
