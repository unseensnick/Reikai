package eu.kanade.tachiyomi.data.track.novellist

import eu.kanade.tachiyomi.data.track.novellist.NovelListApi.Companion.DEFAULT_API_URL
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Every authenticated call carries the sign-in token, so the address the user types must be https. */
class NovelListApiUrlTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("addresses")
    fun `the API address takes an override only over https`(override: String, expected: String) {
        NovelListApi.novelListApiUrl(override) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun addresses() = listOf(
            Arguments.of("", DEFAULT_API_URL),
            Arguments.of("http://novellist-be.example/api", DEFAULT_API_URL),
            Arguments.of("novellist-be.example/api", DEFAULT_API_URL),
            Arguments.of(" https://novellist-be.example/api/ ", "https://novellist-be.example/api"),
        )
    }
}
