package reikai.novel.source.ireader

import io.kotest.matchers.shouldBe
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

/** A Madara chapter-list ask the site refuses is answered from the novel's own chapter address. */
class MadaraChapterEndpointTest {

    @Test
    fun `a refused chapter list is answered from the novel's own address`() {
        val site = Site()
        call(site, ask()).body.string() shouldBe CHAPTERS
    }

    @Test
    fun `a redirect the client leaves unfollowed still names the novel`() {
        val site = Site(followed = false)
        call(site, ask()).body.string() shouldBe CHAPTERS
    }

    @Test
    fun `a chapter list the site answers is left alone`() {
        val site = Site(adminAjaxCode = 200)
        call(site, ask())
        site.asked shouldBe listOf("POST https://site.example/wp-admin/admin-ajax.php")
    }

    @Test
    fun `another ajax request is not touched`() {
        val site = Site()
        call(site, ask(action = "something_else"))
        site.asked shouldBe listOf("POST https://site.example/wp-admin/admin-ajax.php")
    }

    @Test
    fun `a repair that fails hands back the refusal`() {
        val site = Site(novelAjaxCode = 404)
        call(site, ask()).code shouldBe 400
    }

    @Test
    fun `a post id the site does not redirect names no novel to ask`() {
        val site = Site(redirects = false)
        call(site, ask())
        site.asked.size shouldBe 2
    }

    @Test
    fun `a site in a subfolder is asked for the post below it`() {
        val site = Site(root = "/blog/")
        call(site, ask(url = "https://site.example/blog/wp-admin/admin-ajax.php"))
        site.asked[1] shouldBe "HEAD https://site.example/blog/?p=42"
    }

    private fun call(site: Site, request: Request): Response =
        OkHttpClient.Builder().addInterceptor(MadaraChapterEndpoint).addInterceptor(site).build()
            .newCall(request).execute()

    private fun ask(
        url: String = "https://site.example/wp-admin/admin-ajax.php",
        action: String = "manga_get_chapters",
    ): Request {
        val form = FormBody.Builder().add("action", action).add("manga", "42").build()
        return Request.Builder()
            .url(url)
            .post(form)
            // Ktor lists the body's headers on the request, which OkHttp's own form body would not.
            .header("Content-Length", form.contentLength().toString())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
    }

    /** The site, as current Madara sites answer: the old ask refused, the novel's own address serving. */
    private class Site(
        val adminAjaxCode: Int = 400,
        val novelAjaxCode: Int = 200,
        val redirects: Boolean = true,
        // Whether the client followed the redirect, or handed the 301 itself back.
        val followed: Boolean = true,
        val root: String = "/",
    ) : Interceptor {
        val asked = mutableListOf<String>()

        // WordPress answers a refused ajax action with "0".
        private val adminAjaxBody = if (adminAjaxCode == 200) CHAPTERS else "0"

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            asked += "${request.method} ${request.url}"
            val path = request.url.encodedPath
            return when {
                // A request claiming a body it does not carry is dropped, as the live sites drop it.
                request.header("Content-Length")?.let { it != "${request.body?.contentLength() ?: 0}" } == true ->
                    respond(request, 400, "")
                path.endsWith("admin-ajax.php") -> respond(request, adminAjaxCode, adminAjaxBody)
                request.url.queryParameter("p") == "42" && redirects && !followed ->
                    respond(request, 301, "").newBuilder().header("Location", "${root}novel/a-novel/").build()
                request.url.queryParameter("p") == "42" && redirects ->
                    respond(request.newBuilder().url("https://site.example${root}novel/a-novel/").build(), 200, "")
                request.url.queryParameter("p") == "42" -> respond(request, 200, "")
                path == "${root}novel/a-novel/ajax/chapters/" -> respond(request, novelAjaxCode, CHAPTERS)
                else -> respond(request, 404, "")
            }
        }

        private fun respond(request: Request, code: Int, body: String) = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody())
            .build()
    }

    private companion object {
        const val CHAPTERS = """<li class="wp-manga-chapter"><a href="x">Chapter 1</a></li>"""
    }
}
