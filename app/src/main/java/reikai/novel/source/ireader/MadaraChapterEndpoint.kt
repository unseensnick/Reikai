package reikai.novel.source.ireader

import eu.kanade.tachiyomi.network.POST
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Reaches a Madara site's chapter list where the theme IReader's extensions compile in no longer can. It
 * asks `admin-ajax.php` for `manga_get_chapters`, which current Madara sites refuse, then the site root's
 * `ajax/chapters/`, which does not exist; the list is at the novel's own `ajax/chapters/`. A refused ask is
 * answered from there, the novel found through WordPress's `?p=<post id>` redirect, and the source parses
 * it as before. Anything else, and an ask the site still answers, is left alone.
 */
internal object MadaraChapterEndpoint : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val postId = chapterListPostId(request) ?: return chain.proceed(request)
        val response = chain.proceed(request)
        if (response.isSuccessful) return response
        // Held in memory so its connection is free for the asks below; a refusal is a byte or two.
        val refused = response.newBuilder().body(
            response.body.bytes().toResponseBody(response.body.contentType()),
        ).build()

        // The refused ask's body headers describe a body these asks do not send, and a site drops such a request.
        val headers = request.headers.newBuilder().removeAll("Content-Length").removeAll("Content-Type").build()
        // WordPress's root, which a site in a subfolder has below its domain.
        val root = request.url.encodedPath.substringBefore("wp-admin/admin-ajax.php").substringBefore("ajax/chapters/")
        val permalink = Request.Builder()
            .url(request.url.newBuilder().encodedPath(root).query(null).addQueryParameter("p", postId).build())
            .headers(headers)
            .head()
            .build()
        // Only a redirect names the novel, read from where it points whether or not the client followed it;
        // a site answering `?p=` in place gives no page to ask.
        val novelUrl = chain.proceed(permalink).use { response ->
            when {
                response.isRedirect -> response.header("Location")?.let { permalink.url.resolve(it) }
                response.isSuccessful -> response.request.url
                else -> null
            }
        }
            ?.takeIf { it != permalink.url }
            ?: return refused
        val chapters = chain.proceed(
            POST(
                novelUrl.newBuilder().addPathSegments("ajax/chapters/").build().toString(),
                headers.newBuilder().set("X-Requested-With", "XMLHttpRequest").build(),
            ),
        )
        if (!chapters.isSuccessful) {
            chapters.close()
            return refused
        }
        return chapters
    }

    /** The post id a Madara chapter-list ask names, or null for any other request. */
    fun chapterListPostId(request: Request): String? {
        if (request.method != "POST") return null
        val path = request.url.encodedPath
        if (!path.endsWith("/wp-admin/admin-ajax.php") && path != "/ajax/chapters/") return null
        val body = request.body?.takeUnless { it.isOneShot() }?.let { Buffer().also(it::writeTo).readUtf8() }
            ?: return null
        val form = body.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        if (form["action"] != "manga_get_chapters") return null
        return form["manga"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}
