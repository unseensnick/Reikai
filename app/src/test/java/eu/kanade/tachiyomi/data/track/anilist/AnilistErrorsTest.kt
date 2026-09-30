package eu.kanade.tachiyomi.data.track.anilist

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.exception.ApolloHttpException
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import mihon.graphql.anilist.AniListGetCurrentUserQuery
import okio.Buffer
import org.junit.jupiter.api.Test
import reikai.data.track.TrackerSignedOutException
import java.util.UUID

/** AniList's GraphQL errors reach the user as a readable failure, not as an empty result. */
class AnilistErrorsTest {

    @Test
    fun `an invalid token asks to sign in again`() {
        shouldThrow<TrackerSignedOutException> {
            response(Error.Builder("Invalid token").build()).throwOnAniListError()
        }
    }

    @Test
    fun `any other error carries AniList's own message`() {
        shouldThrow<Exception> { response(Error.Builder("Too Many Requests.").build()).throwOnAniListError() }
            .message shouldBe "Too Many Requests."
    }

    // AniList's real answer to a revoked token: HTTP 400 with plain application/json, which Apollo
    // leaves undecoded in an ApolloHttpException.
    @Test
    fun `an invalid token sent with an HTTP error asks to sign in again`() {
        shouldThrow<TrackerSignedOutException> {
            httpFailure(400, """{"data":null,"errors":[{"message":"Invalid token","status":400}]}""")
                .throwOnAniListError()
        }
    }

    @Test
    fun `an HTTP 401 asks to sign in again whatever its body`() {
        shouldThrow<TrackerSignedOutException> { httpFailure(401, "Unauthorized").throwOnAniListError() }
    }

    @Test
    fun `any other HTTP error keeps its status code`() {
        shouldThrow<HttpException> {
            httpFailure(429, """{"data":null,"errors":[{"message":"Too Many Requests.","status":429}]}""")
                .throwOnAniListError()
        }.code shouldBe 429
    }

    private fun response(error: Error) =
        ApolloResponse.Builder(AniListGetCurrentUserQuery(), UUID.randomUUID())
            .errors(listOf(error))
            .build()

    private fun httpFailure(code: Int, body: String) =
        ApolloResponse.Builder(AniListGetCurrentUserQuery(), UUID.randomUUID())
            .exception(ApolloHttpException(code, emptyList(), Buffer().writeUtf8(body), "HTTP $code"))
            .build()
}
