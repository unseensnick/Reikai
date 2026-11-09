package reikai.novel.install

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata

/**
 * A plugin whose stored script is gone is fetched again from its URL, which serves the repo's newest
 * script, so it is taken back only at the version recorded as installed.
 */
class LnPluginMissingScriptTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        coEvery { it.loader.installed(URL) } returns null
        coEvery { it.loader.download(URL) } returns SCRIPT
    }

    private fun recorded(version: String?) =
        harness.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(ID, version = version)))

    // Checked or loaded, the served script reports its own version.
    private fun repoServes(version: String) {
        coEvery { harness.host.inspectPlugin(SCRIPT, any(), any(), any(), any()) } returns
            LnPluginHarness.info(ID, version)
    }

    @Test
    fun `the installed version served again is stored back`() = runTest {
        recorded(INSTALLED)
        repoServes(INSTALLED)

        harness.installer.ensureLoaded()

        coVerify { harness.loader.store(URL, SCRIPT) }
    }

    @Test
    fun `the installed version served again loads the plugin`() = runTest {
        recorded(INSTALLED)
        repoServes(INSTALLED)

        harness.installer.ensureLoaded()

        harness.manager.get(ID).shouldNotBeNull()
    }

    @Test
    fun `a newer version served is not stored`() = runTest {
        recorded(INSTALLED)
        repoServes(NEWER)

        harness.installer.ensureLoaded()

        coVerify(exactly = 0) { harness.loader.store(any(), any()) }
    }

    @Test
    fun `a newer version served is never handed to the plugin's engine`() = runTest {
        recorded(INSTALLED)
        repoServes(NEWER)

        harness.installer.ensureLoaded()

        coVerify(exactly = 0) { harness.host.retainPlugin(any(), SCRIPT) }
    }

    // The version check may yet reject it, so its code must not meet the plugin's storage.
    @Test
    fun `a served script is checked without the plugin's storage`() = runTest {
        recorded(INSTALLED)
        repoServes(NEWER)

        harness.installer.ensureLoaded()

        coVerify(exactly = 0) { harness.host.inspectPlugin(SCRIPT, any(), any(), any(), ID) }
    }

    @Test
    fun `a newer version served reports the plugin missing`() = runTest {
        recorded(INSTALLED)
        repoServes(NEWER)

        harness.installer.ensureLoaded()

        harness.installer.failures.value.getValue(URL).reason.shouldBeInstanceOf<LnPluginLoadFailure.Reason.Missing>()
    }

    @Test
    fun `a newer version served leaves the recorded version alone`() = runTest {
        recorded(INSTALLED)
        repoServes(NEWER)

        harness.installer.ensureLoaded()

        harness.prefs.installedPluginMetadata().get().getValue(URL).version shouldBe INSTALLED
    }

    @Test
    fun `a plugin with no recorded version is reported missing rather than taking what the repo serves`() =
        runTest {
            recorded(null)
            repoServes(NEWER)

            harness.installer.ensureLoaded()

            harness.installer.failures.value.getValue(URL).reason
                .shouldBeInstanceOf<LnPluginLoadFailure.Reason.Missing>()
        }

    private companion object {
        const val ID = "plugin"
        const val URL = "https://repo.test/plugin.js"
        const val SCRIPT = "served script"
        const val INSTALLED = "1.0.0"
        const val NEWER = "1.1.0"
    }
}
