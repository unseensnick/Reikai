package reikai.domain.recommendation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import reikai.data.track.REIKAI_TRACKER_USER_AGENT

class ShikimoriRecommendationsTest {

    @Test
    fun `the recommendations request identifies as Reikai`() = runTest {
        var recorded: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            recorded = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()

        ShikimoriRecommendations(client, 1L, Json { ignoreUnknownKeys = true }).getRecsById(1L)

        recorded!!.header("User-Agent") shouldBe REIKAI_TRACKER_USER_AGENT
    }
}
