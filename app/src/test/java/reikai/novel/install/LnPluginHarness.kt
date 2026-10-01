package reikai.novel.install

import eu.kanade.tachiyomi.extension.ExtensionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo
import reikai.novel.host.LnPluginLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * A real installer and source manager over a mocked plugin host and script store, with every script
 * [installedUrls] names already stored. The host is left for each test to answer, so one can fail a
 * load, count loads or hold one on a gate.
 */
class LnPluginHarness(installedUrls: Set<String>) {

    val prefs = NovelPreferences(EmittingPreferenceStore()).also { it.installedPluginUrls().set(installedUrls) }

    val host = mockk<LnPluginHost>()

    val loader = mockk<LnPluginLoader>(relaxed = true) {
        coEvery { installed(any()) } returns "plugin source"
        coEvery { installedStylesheet(any()) } returns null
    }

    lateinit var installer: LnPluginInstaller
        private set

    val manager: NovelSourceManager = NovelSourceManager(
        { installer },
        mockk<ExtensionManager> { every { loadedNovelExtensionsFlow } returns flowOf(emptyList()) },
        prefs,
    ).also { installer = LnPluginInstaller(mockk(), loader, it, prefs, host) }

    companion object {
        fun info(id: String, version: String? = null) = LnPluginInfo(id = id, name = id, version = version)
    }
}
