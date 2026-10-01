package reikai.novel.install

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata

/**
 * A load pass and an install, update or uninstall of the same plugin never publish over each other:
 * whichever runs second sees what the first one did.
 */
class LnPluginInstallerRaceTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID, version = "1")))
        coEvery { it.loader.download(any()) } returns NEW_SCRIPT
    }
    private val installer get() = harness.installer

    private val passEntered = CompletableDeferred<Unit>()
    private val passGate = CompletableDeferred<Unit>()

    /** The pass loads the stored script and waits on [passGate]; an install loads the new one at once. */
    private fun holdThePass() {
        coEvery { harness.host.loadPlugin(any(), any(), any(), any()) } coAnswers {
            if (secondArg<String>() == NEW_SCRIPT) {
                LnPluginHarness.info(ID, version = "2")
            } else {
                passEntered.complete(Unit)
                passGate.await()
                LnPluginHarness.info(ID, version = "1")
            }
        }
    }

    private suspend fun registered() = harness.manager.sources.first().map { it.id to it.version }

    @Test
    fun `an uninstall while a load pass runs leaves the plugin uninstalled`() = runTest {
        holdThePass()
        val pass = launch { installer.ensureLoaded() }
        passEntered.await()

        val uninstall = launch { installer.uninstall(ID) }
        advanceUntilIdle()
        release(pass, uninstall)

        registered() shouldBe emptyList()
    }

    @Test
    fun `an update while a load pass runs keeps the new version`() = runTest {
        holdThePass()
        val pass = launch { installer.ensureLoaded() }
        passEntered.await()

        val update = launch { installer.installFromUrl(URL, LnInstalledPluginMetadata(pluginId = ID, version = "2")) }
        advanceUntilIdle()
        release(pass, update)

        harness.prefs.installedPluginMetadata().get()[URL]?.version shouldBe "2"
    }

    @Test
    fun `a load pass that waited behind an uninstall does not bring the plugin back`() = runTest {
        passBehindAnUninstall()

        registered() shouldBe emptyList()
    }

    /** Its script went with the uninstall, so loading it would have fetched and stored it again. */
    @Test
    fun `a load pass that waited behind an uninstall fetches nothing for it`() = runTest {
        coEvery { harness.loader.installed(URL) } returns null

        passBehindAnUninstall()

        coVerify(exactly = 1) { harness.loader.download(URL) }
    }

    /**
     * The pass reads the plugin as installed, then waits for its lock behind a reinstall and an uninstall
     * tapped after it; the reinstall's download is the one fetch either test expects.
     */
    private suspend fun TestScope.passBehindAnUninstall() {
        val installGate = CompletableDeferred<Unit>()
        coEvery { harness.loader.download(any()) } coAnswers {
            installGate.await()
            NEW_SCRIPT
        }
        holdThePass()
        passGate.complete(Unit)
        val reinstall = launch { installer.installFromUrl(URL, LnInstalledPluginMetadata(pluginId = ID)) }
        advanceUntilIdle()
        val uninstall = launch { installer.uninstall(ID) }
        advanceUntilIdle()
        val pass = launch { installer.ensureLoaded() }
        advanceUntilIdle()

        installGate.complete(Unit)
        listOf(reinstall, uninstall, pass).forEach { it.join() }
    }

    /** An install from another address replaces this one, so the pass loading this one publishes nothing. */
    @Test
    fun `a load pass of an address an install replaced does not publish the old version`() = runTest {
        holdThePass()
        val pass = launch { installer.ensureLoaded() }
        passEntered.await()

        val install = launch { installer.installFromUrl(OTHER_URL, LnInstalledPluginMetadata(pluginId = ID)) }
        advanceUntilIdle()
        release(pass, install)

        registered() shouldBe listOf(ID to "2")
    }

    private suspend fun release(vararg jobs: Job) {
        passGate.complete(Unit)
        jobs.forEach { it.join() }
    }

    private companion object {
        const val ID = "plugin"
        const val URL = "https://repo.test/plugin.js"
        const val OTHER_URL = "https://other.test/plugin.js"
        const val NEW_SCRIPT = "new plugin source"
    }
}
