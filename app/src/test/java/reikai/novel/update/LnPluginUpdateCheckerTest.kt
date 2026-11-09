package reikai.novel.update

import android.app.NotificationManager
import android.content.Context
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRegistryFetcher
import reikai.novel.registry.LnRepoRegistries
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference

class LnPluginUpdateCheckerTest {

    private val pluginUrl = "https://example.org/novelbin.js"
    private val homeRepo = "https://example.org/plugins.json"
    private val otherRepo = "https://other.example/plugins.json"

    private val prefs = NovelPreferences(EmittingPreferenceStore()).apply {
        addedRepoUrls().set(setOf(homeRepo))
        installedPluginUrls().set(setOf(pluginUrl))
        installedPluginMetadata().set(mapOf(pluginUrl to LnInstalledPluginMetadata("novelbin", version = "1.0.0")))
    }

    private val atInstalledUrl = entry(version = "1.0.1", url = pluginUrl)
    private val elsewhere = entry(version = "1.3.0", url = "https://other.example/novelbin.js")

    private val newerListed = LnRegistryFetcher { listOf(atInstalledUrl) }

    // The Context only clears the notice once nothing is pending.
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(any<String>()) } returns mockk<NotificationManager>(relaxed = true)
    }

    private fun checker(fetcher: LnRegistryFetcher) =
        LnPluginUpdateChecker(
            fetcher,
            LnRepoRegistries(fetcher, prefs),
            LnPluginInstaller(mockk(), mockk(), mockk(relaxed = true), prefs, mockk()),
            prefs,
            LnPluginUpdateNotifier(context, mockk(), prefs),
        )

    private fun repos(vararg listings: Pair<String, List<LnRegistryEntry>?>): LnRegistryFetcher {
        prefs.addedRepoUrls().set(listings.map { it.first }.toSet())
        val byRepo = listings.toMap()
        return LnRegistryFetcher { repo -> byRepo[repo] ?: error("down") }
    }

    /** A check cancelled mid-fetch used to carry on as if the repo were merely down. */
    @Test
    fun `a cancelled registry fetch cancels the update check`() = runTest {
        val cancelled = LnRegistryFetcher { throw CancellationException("gone") }

        shouldThrow<CancellationException> { checker(cancelled).checkAndRecord() }
    }

    @Test
    fun `a recorded check sets the Browse badge count`() = runTest {
        checker(newerListed).checkAndRecord()

        prefs.pendingPluginUpdates().get() shouldBe setOf("novelbin")
    }

    @Test
    fun `a recorded check stamps the last check so the in-app check skips until stale`() = runTest {
        checker(newerListed).checkAndRecord()

        prefs.lastLnPluginCheck().get() shouldBeGreaterThan 0L
    }

    /** Offline, every repo fails: that says nothing about the updates already found. */
    @Test
    fun `a check no repo answered keeps the pending updates`() = runTest {
        prefs.pendingPluginUpdates().set(setOf("novelbin"))

        checker(repos(homeRepo to null)).checkAndRecord()

        prefs.pendingPluginUpdates().get() shouldBe setOf("novelbin")
    }

    @Test
    fun `a check no repo answered is not stamped, so the next launch checks again`() = runTest {
        checker(repos(homeRepo to null)).checkAndRecord()

        prefs.lastLnPluginCheck().get() shouldBe 0L
    }

    @Test
    fun `a plugin whose own repo is down keeps its pending update while another repo answers`() = runTest {
        prefs.pendingPluginUpdates().set(setOf("novelbin"))

        checker(repos(homeRepo to null, otherRepo to emptyList())).checkAndRecord()

        prefs.pendingPluginUpdates().get() shouldBe setOf("novelbin")
    }

    @Test
    fun `a plugin its own repo settles drops its pending update while another repo is down`() = runTest {
        prefs.pendingPluginUpdates().set(setOf("novelbin"))

        checker(repos(homeRepo to listOf(entry(version = "1.0.0", url = pluginUrl)), otherRepo to null))
            .checkAndRecord()

        prefs.pendingPluginUpdates().get().shouldBeEmpty()
    }

    /** The installed repo may only be down, and taking another repo's entry would move the plugin. */
    @Test
    fun `with the installed repo down another repo's newer version is not offered`() = runTest {
        checker(repos(homeRepo to null, otherRepo to listOf(elsewhere))).checkAndRecord().shouldBeEmpty()
    }

    @Test
    fun `an update at the installed URL is offered while an unrelated repo is down`() = runTest {
        checker(repos(homeRepo to listOf(atInstalledUrl), otherRepo to null)).checkAndRecord()
            .map { it.entry } shouldContainExactly listOf(atInstalledUrl)
    }

    @Test
    fun `a check records the repo listing an installed plugin`() = runTest {
        checker(repos(homeRepo to listOf(atInstalledUrl), otherRepo to emptyList())).checkAndRecord()

        prefs.installedPluginMetadata().get().getValue(pluginUrl).repoUrl shouldBe homeRepo
    }

    /** As an apk waits out only the stores it can come from (KindListing). */
    @Test
    fun `a plugin its own repo dropped clears its badge while an unrelated repo is down`() = runTest {
        checker(repos(homeRepo to listOf(atInstalledUrl), otherRepo to emptyList())).checkAndRecord()

        checker(repos(homeRepo to emptyList(), otherRepo to null)).checkAndRecord()

        prefs.pendingPluginUpdates().get().shouldBeEmpty()
    }

    // A backup leaves out app-state keys; a restored check time skipped this device's next check.
    @Test
    fun `the check time is not backed up`() {
        Preference.isAppState(prefs.lastLnPluginCheck().key()) shouldBe true
    }

    private fun entry(version: String, url: String) =
        LnRegistryEntry("novelbin", "NovelBin", version, "https://novelbin.com", "en", url)
}
