package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelPluginRestorer.NotRestored
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.LnPluginLoadFailure
import tachiyomi.core.common.preference.Preference

/**
 * A backup carries a plugin's URL but never its script, so a restore that says nothing leaves a
 * reader with novel sources that cannot open anything. Each plugin the reload could not bring back
 * has to reach the restore log, as a manga extension does.
 */
class NovelPluginRestorerTest {

    private val urlPref = mockk<Preference<Set<String>>> {
        every { get() } returns setOf(PLUGIN_URL)
    }
    private val preferences = mockk<NovelPreferences> {
        every { installedPluginUrls() } returns urlPref
    }
    private val loadFailures = MutableStateFlow<Map<String, LnPluginLoadFailure>>(emptyMap())
    private val installer = mockk<LnPluginInstaller>()

    private val restorer = NovelPluginRestorer(installer, preferences)

    init {
        every { installer.failures } returns loadFailures
        coEvery { installer.loadInstalled() } returns emptyList()
    }

    private fun failure(reason: LnPluginLoadFailure.Reason) = LnPluginLoadFailure(
        url = PLUGIN_URL,
        pluginId = "novelfire",
        name = "Novel Fire",
        iconUrl = null,
        lang = null,
        version = null,
        reason = reason,
    )

    @Test
    fun `a plugin whose script could not be fetched again is named`() = runTest {
        loadFailures.value = mapOf(PLUGIN_URL to failure(LnPluginLoadFailure.Reason.Missing))

        restorer.restore() shouldBe listOf(NotRestored("Novel Fire", "script could not be downloaded"))
    }

    @Test
    fun `a plugin that threw is named with what it said`() = runTest {
        loadFailures.value = mapOf(
            PLUGIN_URL to failure(LnPluginLoadFailure.Reason.Failed("host unreachable", "trace")),
        )

        restorer.restore() shouldBe listOf(NotRestored("Novel Fire", "host unreachable"))
    }

    @Test
    fun `plugins that came back leave nothing to report`() = runTest {
        restorer.restore().shouldBeEmpty()
    }

    @Test
    fun `nothing is reported when no plugin was installed`() = runTest {
        every { urlPref.get() } returns emptySet()

        restorer.restore().shouldBeEmpty()
    }

    @Test
    fun `a load that outlasts its bound is reported rather than waited out`() = runTest {
        coEvery { installer.loadInstalled() } coAnswers {
            delay(10 * 60 * 1000L)
            emptyList()
        }

        restorer.restore() shouldBe listOf(NotRestored(null, "load timed out"))
    }

    private companion object {
        const val PLUGIN_URL = "https://example.com/plugins/novelfire.js"
    }
}
