package reikai.novel.source

import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.novel.host.LnPluginException
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginLoader
import reikai.novel.install.LnPluginInstaller
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

/**
 * A lookup awaits one first load, as Mihon's source manager awaits its first extension scan. Only a
 * novel screen opening, or an update or download run starting, retries a plugin that failed, so a
 * broken plugin costs one load per screen or run rather than one per novel or chapter.
 */
class NovelSourceLookupTest {

    private var loads = 0

    private val host = mockk<LnPluginHost> {
        coEvery { loadPlugin(any(), any(), any(), any()) } answers {
            loads++
            throw LnPluginException("broken plugin")
        }
    }

    private val manager: NovelSourceManager = run {
        val prefs = NovelPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreference(
                        NovelPreferences.INSTALLED_PLUGIN_URLS_KEY,
                        setOf("https://repo.test/broken.js"),
                        emptySet(),
                    ),
                ),
            ),
        )
        val loader = mockk<LnPluginLoader> { coEvery { installed(any()) } returns "plugin source" }
        val extensions = mockk<ExtensionManager> { every { loadedNovelExtensionsFlow } returns flowOf(emptyList()) }
        lateinit var installer: LnPluginInstaller
        NovelSourceManager({ installer }, extensions, prefs).also {
            installer = LnPluginInstaller(mockk(), loader, it, prefs, host)
        }
    }

    @Test
    fun `lookups after a failed first load do not load the plugin again`() = runTest {
        manager.get("broken")
        manager.get("broken")

        loads shouldBe 1
    }

    @Test
    fun `a screen opening retries a plugin whose first load failed`() = runTest {
        manager.get("broken")
        manager.ensureLoaded()

        loads shouldBe 2
    }
}
