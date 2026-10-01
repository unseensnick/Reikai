package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginException
import reikai.novel.install.LnPluginHarness

/**
 * A lookup awaits one first load, as Mihon's source manager awaits its first extension scan. Only a
 * novel screen opening, or an update or download run starting, retries a plugin that failed, so a
 * broken plugin costs one load per screen or run rather than one per novel or chapter.
 */
class NovelSourceLookupTest {

    private var loads = 0

    private val harness = LnPluginHarness(setOf("https://repo.test/broken.js"))
    private val manager = harness.manager

    private fun failEveryLoad() {
        coEvery { harness.host.loadPlugin(any(), any(), any(), any()) } answers {
            loads++
            throw LnPluginException("broken plugin")
        }
    }

    @Test
    fun `lookups after a failed first load do not load the plugin again`() = runTest {
        failEveryLoad()

        manager.get("broken")
        manager.get("broken")

        loads shouldBe 1
    }

    @Test
    fun `a screen opening retries a plugin whose first load failed`() = runTest {
        failEveryLoad()

        manager.get("broken")
        manager.ensureLoaded()

        loads shouldBe 2
    }

    /** The raw registry starts empty, which a screen showing install state reads as every plugin missing. */
    @Test
    fun `the loaded sources hold the installed plugins from their first emission`() = runTest {
        coEvery { harness.host.loadPlugin(any(), any(), any(), any()) } returns LnPluginHarness.info("broken")

        manager.loadedSources().first().map { it.id } shouldBe listOf("broken")
    }
}
