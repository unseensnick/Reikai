package eu.kanade.tachiyomi.data.track.novelupdates

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration.Companion.minutes

/**
 * NovelUpdates has no API, so every call fetches a page and hands it to [NovelUpdatesParser].
 *
 * Cookies come from the shared jar, which is the WebView's own store, so the sign-in session and
 * the Cloudflare clearance both ride along without being resent by hand. Nothing here sets a
 * User-Agent either: the clearance is bound to the one the app already sends.
 */
class NovelUpdatesApi(client: OkHttpClient) {

    private val json: Json by injectLazy()

    // Ours, not theirs: the site publishes no limit, and a scraper is heavier on it than an API is.
    private val client = client.newBuilder()
        .rateLimit(permits = 20, period = 1.minutes)
        .build()

    private val headers = Headers.headersOf("Referer", "$BASE_URL/")

    suspend fun search(query: String): List<NovelUpdatesSeries> {
        val url = "$BASE_URL/series-finder/?sf=1&sh=${query.replace(" ", "+")}&sort=sdate&order=desc"
        return parseSearch(get(url))
    }

    suspend fun findNovelId(seriesUrl: String): String? = parseNovelId(get(seriesUrl))

    suspend fun findListId(novelId: String): Long? = parseListId(get("$BASE_URL/series/?p=$novelId"))

    suspend fun readingLists(): List<Pair<String, String>> = parseReadingLists(get("$BASE_URL/reading-list/"))

    /** Both come off the reading-list page, so signing in costs one request rather than two. */
    suspend fun account(): NovelUpdatesAccount {
        val page = get("$BASE_URL/reading-list/")
        return NovelUpdatesAccount(username = parseUsername(page), lists = parseReadingLists(page))
    }

    /** Null when the response does not parse, which the caller must treat as "do not write". */
    suspend fun readNotes(novelId: String): NovelUpdatesNotes? {
        val body = FormBody.Builder()
            .add("action", "wi_notestagsfic")
            .add("strSID", novelId)
            .build()
        val text = client.newCall(POST(AJAX_URL, headers, body)).awaitSuccess().body.string()
        return parseNotesPayload(text, json)
    }

    suspend fun writeNotes(novelId: String, notes: String, tags: String) {
        val body = FormBody.Builder()
            .add("action", "wi_rlnotes")
            .add("strSID", novelId)
            .add("strNotes", notes)
            .add("strTags", tags)
            .build()
        client.newCall(POST(AJAX_URL, headers, body)).awaitSuccess().close()
    }

    suspend fun moveToList(novelId: String, listId: Long) {
        client.newCall(GET("$BASE_URL/updatelist.php?sid=$novelId&lid=$listId&act=move", headers))
            .awaitSuccess()
            .close()
    }

    private suspend fun get(url: String) = client.newCall(GET(url, headers)).awaitSuccess().asJsoup()

    companion object {
        const val BASE_URL = "https://www.novelupdates.com"

        private const val AJAX_URL = "$BASE_URL/wp-admin/admin-ajax.php"

        fun seriesUrl(slug: String): String = "$BASE_URL/series/$slug/"
    }
}
