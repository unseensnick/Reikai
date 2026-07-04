@file:Suppress("PropertyName")

package exh.md.utils

import kotlin.time.Duration.Companion.minutes

// The OAuth `Login` object (redirect uri, client id, PKCE auth-url builder) arrives in Phase 3.
object MdConstants {
    const val baseUrl = "https://mangadex.org"
    const val cdnUrl = "https://uploads.mangadex.org"
    const val atHomeReportUrl = "https://api.mangadex.network/report"

    object Types {
        const val author = "author"
        const val artist = "artist"
        const val coverArt = "cover_art"
        const val manga = "manga"
        const val scanlator = "scanlation_group"
    }

    val mdAtHomeTokenLifespan = 5.minutes.inWholeMilliseconds
}
