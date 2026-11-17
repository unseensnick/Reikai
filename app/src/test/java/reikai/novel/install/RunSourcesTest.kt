package reikai.novel.install

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager

/** A download run, a library update and a metadata refresh each load a plugin that failed to load once more. */
class RunSourcesTest {

    private val reloaded = mockk<NovelSource>()
    private val sourceManager = mockk<NovelSourceManager>().also {
        coEvery { it.get("broken") } returns null
        coEvery { it.get("gone") } returns null
    }
    private val installer = mockk<LnPluginInstaller> {
        every { failures } returns MutableStateFlow(mapOf("https://repo/broken.js" to failure()))
        coEvery { retryLoad(any()) } just runs
    }

    @Test
    fun `a plugin that failed to load is loaded again and found`() = runTest {
        coEvery { installer.retryLoad("broken") } answers { coEvery { sourceManager.get("broken") } returns reloaded }

        RunSources(installer, sourceManager).get("broken") shouldBe reloaded
    }

    @Test
    fun `a source that is not installed loads no plugin`() = runTest {
        RunSources(installer, sourceManager).get("gone")

        coVerify(exactly = 0) { installer.retryLoad(any()) }
    }

    @Test
    fun `a run loads a failed plugin once however often it asks`() = runTest {
        val sources = RunSources(installer, sourceManager)

        repeat(2) { sources.get("broken") }

        coVerify(exactly = 1) { installer.retryLoad("broken") }
    }

    private fun failure() = LnPluginLoadFailure(
        url = "https://repo/broken.js",
        pluginId = "broken",
        name = "Broken",
        iconUrl = null,
        lang = null,
        version = null,
        customCssUrl = null,
        reason = LnPluginLoadFailure.Reason.Malformed,
    )
}
