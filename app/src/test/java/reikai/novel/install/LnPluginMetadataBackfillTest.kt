package reikai.novel.install

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.maps.shouldNotContainKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata

/** A load pass fills a legacy record's icon and language from the repos, and only for a plugin still installed. */
class LnPluginMetadataBackfillTest {

    private lateinit var harness: LnPluginHarness

    // The repo answers only once the plugin was uninstalled, as a tap during a slow fetch does.
    private val network = mockk<NetworkHelper> {
        every { client } returns OkHttpClient.Builder().addInterceptor { chain ->
            runBlocking { harness.installer.uninstall(ID) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                .body(REGISTRY.toResponseBody()).build()
        }.build()
    }

    @Test
    fun `a plugin uninstalled while the repos are read gets no record back`() = runTest {
        harness = LnPluginHarness(setOf(URL), network).also {
            it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
            it.prefs.addedRepoUrls().set(setOf(REPO))
            coEvery { it.host.inspectPlugin(any(), any(), any(), any(), any()) } returns LnPluginHarness.info(ID)
        }

        harness.installer.ensureLoaded()

        harness.prefs.installedPluginMetadata().get() shouldNotContainKey URL
    }

    private companion object {
        const val ID = "plugin"
        const val URL = "https://repo.test/plugin.js"
        const val REPO = "https://repo.test/plugins.min.json"
        val REGISTRY = """[{"id":"$ID","name":"P","version":"1","site":"https://p.test","lang":"English",""" +
            """"url":"$URL","iconUrl":"https://repo.test/icon.png"}]"""
    }
}
