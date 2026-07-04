@file:Suppress("PropertyName")

package exh.md.utils

// Auth endpoints (baseAuthUrl, login, logout, token, userInfo) arrive with OAuth in Phase 3.
object MdApi {
    const val baseUrl = "https://api.mangadex.org"
    const val manga = "$baseUrl/manga"
    const val cover = "$baseUrl/cover"
    const val chapter = "$baseUrl/chapter"
    const val group = "$baseUrl/group"
    const val author = "$baseUrl/author"
    const val rating = "$baseUrl/rating"
    const val statistics = "$baseUrl/statistics/manga"
    const val chapterImageServer = "$baseUrl/at-home/server"
    const val userFollows = "$baseUrl/user/follows/manga"
    const val readingStatusForAllManga = "$baseUrl/manga/status"
    const val atHomeServer = "$baseUrl/at-home/server"

    const val legacyMapping = "$baseUrl/legacy/mapping"
}
