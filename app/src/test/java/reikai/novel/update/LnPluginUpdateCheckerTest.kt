package reikai.novel.update

import io.kotest.assertions.throwables.shouldThrow
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.novel.registry.LnRegistryFetcher
import reikai.presentation.recents.EmittingPreferenceStore

class LnPluginUpdateCheckerTest {

    private val prefs = NovelPreferences(EmittingPreferenceStore()).apply {
        addedRepoUrls().set(setOf("https://example.org/plugins.json"))
        installedPluginUrls().set(setOf("https://example.org/novelbin.js"))
    }

    /** A check cancelled mid-fetch used to carry on as if the repo were merely down. */
    @Test
    fun `a cancelled registry fetch cancels the update check`() = runTest {
        val cancelled = LnRegistryFetcher { throw CancellationException("gone") }

        shouldThrow<CancellationException> { LnPluginUpdateChecker(cancelled, prefs, mockk()).check() }
    }
}
