package reikai.novel.install

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.maps.shouldNotContainKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers

/** A load pass fills a legacy record's icon and language from the repos, and only for a plugin still installed. */
class LnPluginMetadataBackfillTest {

    private lateinit var harness: LnPluginHarness

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    // The repo answers only once the plugin was uninstalled, as a tap during a slow fetch does.
    private fun network(scope: CoroutineScope) = mockk<NetworkHelper> {
        every { client } returns mockk<OkHttpClient> {
            every { newCall(any()) } answers {
                val request = firstArg<Request>()
                mockk<Call> {
                    every { enqueue(any()) } answers {
                        val call = self as Call
                        scope.launch {
                            harness.installer.uninstall(ID)
                            firstArg<Callback>().onResponse(call, response(request))
                        }
                    }
                    every { cancel() } answers {}
                }
            }
        }
    }

    private fun response(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("").body(REGISTRY.toResponseBody()).build()

    @Test
    fun `a plugin uninstalled while the repos are read gets no record back`() = runTest {
        harness = LnPluginHarness(setOf(URL), network(this)).also {
            it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
            it.prefs.addedRepoUrls().set(setOf(REPO))
            coEvery { it.host.inspectPlugin(any(), any(), any(), any(), any()) } returns LnPluginHarness.info(ID)
        }
        restubDefaultDispatcher()

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
