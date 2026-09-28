package eu.kanade.tachiyomi.data.track.anilist

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import mihon.graphql.anilist.AniListGetCurrentUserQuery
import org.junit.jupiter.api.Test
import java.util.UUID

/** AniList's GraphQL errors reach the user as the raw client worded them, not as an empty result. */
class AnilistErrorsTest {

    @Test
    fun `an invalid token asks to log in again`() {
        shouldThrow<Exception> { response(Error.Builder("Invalid token").build()).throwOnAniListError() }
            .message shouldBe "AniList token expired, please login again"
    }

    @Test
    fun `any other error carries AniList's own message`() {
        shouldThrow<Exception> { response(Error.Builder("Too Many Requests.").build()).throwOnAniListError() }
            .message shouldBe "Too Many Requests."
    }

    private fun response(error: Error) =
        ApolloResponse.Builder(AniListGetCurrentUserQuery(), UUID.randomUUID())
            .errors(listOf(error))
            .build()
}
