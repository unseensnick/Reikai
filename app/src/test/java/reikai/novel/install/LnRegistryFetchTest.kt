package reikai.novel.install

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

/** A repo index is fetched the way Mihon fetches an extension store index (ExtensionStoreService.fetch). */
class LnRegistryFetchTest {

    private val repo = "https://repo.test/plugins.min.json"
    private val sent = mutableListOf<Request>()

    private fun installerAnswering(code: Int, body: String = "[]"): LnPluginInstaller {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()
        val network = mockk<NetworkHelper> { every { this@mockk.client } returns client }
        return LnPluginInstaller(network, mockk(), mockk(), mockk(), mockk())
    }

    @Test
    fun `a repo the server refuses fails with the server's status code`() = runTest {
        val installer = installerAnswering(code = 404)

        shouldThrow<HttpException> { installer.fetchRepo(repo) }.code shouldBe 404
    }

    @Test
    fun `a repo index may be answered from the cache for ten minutes`() = runTest {
        installerAnswering(code = 200).fetchRepo(repo)

        sent.single().cacheControl.maxAgeSeconds shouldBe 600
    }
}
