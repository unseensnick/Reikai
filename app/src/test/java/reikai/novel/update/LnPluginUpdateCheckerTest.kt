package reikai.novel.update

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRegistryFetcher
import reikai.presentation.recents.EmittingPreferenceStore

class LnPluginUpdateCheckerTest {

    private val pluginUrl = "https://example.org/novelbin.js"

    private val prefs = NovelPreferences(EmittingPreferenceStore()).apply {
        addedRepoUrls().set(setOf("https://example.org/plugins.json"))
        installedPluginUrls().set(setOf(pluginUrl))
        installedPluginMetadata().set(mapOf(pluginUrl to LnInstalledPluginMetadata("novelbin", version = "1.0.0")))
    }

    private val newerListed = LnRegistryFetcher {
        listOf(LnRegistryEntry("novelbin", "NovelBin", "1.0.1", "https://novelbin.com", "en", pluginUrl))
    }

    // A pending count above zero never touches the Context, which only clears the notice at zero.
    private fun checker(fetcher: LnRegistryFetcher) =
        LnPluginUpdateChecker(fetcher, prefs, LnPluginUpdateNotifier(mockk(), mockk(), prefs))

    /** A check cancelled mid-fetch used to carry on as if the repo were merely down. */
    @Test
    fun `a cancelled registry fetch cancels the update check`() = runTest {
        val cancelled = LnRegistryFetcher { throw CancellationException("gone") }

        shouldThrow<CancellationException> { checker(cancelled).check() }
    }

    @Test
    fun `a recorded check sets the Browse badge count`() = runTest {
        checker(newerListed).checkAndRecord()

        prefs.pluginUpdatesCount().get() shouldBe 1
    }

    @Test
    fun `a recorded check stamps the last check so the in-app check skips until stale`() = runTest {
        checker(newerListed).checkAndRecord()

        prefs.lastLnPluginCheck().get() shouldBeGreaterThan 0L
    }
}
