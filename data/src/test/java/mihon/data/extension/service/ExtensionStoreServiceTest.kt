package mihon.data.extension.service

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.domain.extension.model.ExtensionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import reikai.domain.extension.StoreKeyChangedException

/** The network is the boundary, so the client answers every request with one canned index. */
class ExtensionStoreServiceTest {

    @Test
    fun `an index signed by another key is refused rather than listed under the saved store`() = runTest {
        val result = service(index(signingKey = "other")).getExtensions(store(signingKey = "key"))

        (result.exceptionOrNull() as? StoreKeyChangedException)?.servedName shouldBe "Other"
    }

    @Test
    fun `an index signed by the saved key is listed`() = runTest {
        val result = service(index(signingKey = "key")).getExtensions(store(signingKey = "key"))

        result.isSuccess shouldBe true
    }

    private fun service(body: String): ExtensionStoreService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val network = mockk<NetworkHelper> { every { this@mockk.client } returns client }
        return ExtensionStoreService(network, Json { ignoreUnknownKeys = true }, ProtoBuf)
    }

    private fun index(signingKey: String) =
        """{"name":"Other","badgeLabel":"Other","signingKey":"$signingKey",""" +
            """"contact":{"website":"https://example.org","discord":null},""" +
            """"extensionList":{"extensions":[]},"extensionListUrl":null}"""

    private fun store(signingKey: String) = ExtensionStore(
        indexUrl = "https://example.org/index.pb",
        name = "Store",
        badgeLabel = "Store",
        signingKey = signingKey,
        contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )
}
