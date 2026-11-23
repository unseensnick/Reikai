@file:Suppress("PropertyName")

package exh.md.utils

import androidx.core.net.toUri
import kotlin.time.Duration.Companion.minutes

object MdConstants {
    const val baseUrl = "https://mangadex.org"
    const val cdnUrl = "https://uploads.mangadex.org"

    // Page size for the follows listing. The request limit and the paging offset step must match, so
    // fetchFollows uses this for both (unlike Komikku, which requested 100 but stepped by 20).
    const val followsPageLimit = 100

    object Types {
        const val author = "author"
        const val artist = "artist"
        const val coverArt = "cover_art"
        const val manga = "manga"
        const val scanlator = "scanlation_group"
    }

    val mdAtHomeTokenLifespan = 5.minutes.inWholeMilliseconds

    // OAuth2 PKCE login. Reuses TachiyomiSY's grandfathered public client + redirect: MangaDex has
    // closed new public-client registration, so a fresh mihon:// client can't be whitelisted. The
    // tachiyomisy://mangadex-auth redirect is caught by MangaDexLoginActivity's manifest entry.
    object Login {
        const val redirectUri = "tachiyomisy://mangadex-auth"
        const val clientId = "tachiyomisy"
        const val authorizationCode = "authorization_code"
        const val refreshToken = "refresh_token"

        /** [codeChallenge] is the S256 challenge of the verifier the code exchange will send. */
        fun authUrl(codeChallenge: String): String {
            return (MdApi.baseAuthUrl + MdApi.login).toUri().buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build().toString()
        }
    }
}
