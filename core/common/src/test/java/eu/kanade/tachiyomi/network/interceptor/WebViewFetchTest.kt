package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.jupiter.api.Test
import java.util.Base64

class WebViewFetchTest {

    private fun get(url: String = "https://www.example.com/page") = Request.Builder().url(url)

    private fun headersSent(request: Request): List<Pair<String, String>> =
        webViewFetchMessage("id", request, manualRedirect = false)!!["headers"]!!.jsonArray.map {
            it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content
        }

    @Test
    fun `a method outside the fixed set never reaches the page`() {
        webViewFetchMessage("id", get().method("GET'});alert(1)//", null).build(), false).shouldBeNull()
    }

    @Test
    fun `a header value reaches the page as data, quotes and all`() {
        val value = "x'});navigator.serviceWorker.register('/sw.js');//"

        headersSent(get().header("X-Token", value).build()) shouldBe listOf("X-Token" to value)
    }

    @Test
    fun `headers the WebView supplies itself or a page may not set stay behind`() {
        val request = get()
            .header("Cookie", "a=b").header("User-Agent", "ua").header("Sec-Fetch-Mode", "cors")
            .header("Proxy-Authorization", "p").header("Authorization", "Bearer t")
            .build()

        headersSent(request) shouldBe listOf("Authorization" to "Bearer t")
    }

    @Test
    fun `a posted form goes as its bytes with its multipart type`() {
        val body = MultipartBody.Builder("b").setType(MultipartBody.FORM).addFormDataPart("action", "x").build()
        val message = webViewFetchMessage("id", get().post(body).build(), false)!!

        message["contentType"]!!.jsonPrimitive.content shouldBe "multipart/form-data; boundary=b"
    }

    @Test
    fun `a body that can only be sent once is not carried`() {
        val oneShot = object : okhttp3.RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: BufferedSink) = Unit
            override fun isOneShot() = true
        }

        webViewFetchMessage("id", get().post(oneShot).build(), false).shouldBeNull()
    }

    @Test
    fun `a posted body round-trips through base64`() {
        val message = webViewFetchMessage("id", get().post("a=1&b=2".toRequestBody()).build(), false)!!

        String(Base64.getDecoder().decode(message["body"]!!.jsonPrimitive.content)) shouldBe "a=1&b=2"
    }

    @Test
    fun `an opaque answer is not a response`() {
        webViewFetchResponse(get().build(), 0, "", emptyList(), null, Buffer()).shouldBeNull()
    }

    @Test
    fun `the decoded body does not keep the encoding the server sent`() {
        val headers = listOf("Content-Encoding" to "gzip", "Content-Length" to "5", "Content-Type" to "text/html")
        val response = webViewFetchResponse(get().build(), 200, "OK", headers, null, Buffer().writeUtf8("hello"))!!

        response.headers.names() shouldBe setOf("Content-Type")
    }

    @Test
    fun `a final URL on the same site becomes the response's URL`() {
        val response = webViewFetchResponse(
            get().build(),
            200,
            "OK",
            emptyList(),
            "https://www.example.com/moved",
            Buffer(),
        )!!

        response.request.url.encodedPath shouldBe "/moved"
    }

    @Test
    fun `a final URL on another site is not taken`() {
        val response = webViewFetchResponse(
            get().build(),
            200,
            "OK",
            emptyList(),
            "https://evil.example.net/",
            Buffer(),
        )!!

        response.request.url.host shouldBe "www.example.com"
    }

    @Test
    fun `a challenge to the WebView is recognised`() {
        isWebViewFetchChallenged(403, listOf("cf-mitigated" to "challenge")) shouldBe true
    }

    @Test
    fun `a plain 403 is an answer, not a challenge`() {
        isWebViewFetchChallenged(403, listOf("server" to "cloudflare")) shouldBe false
    }

    @Test
    fun `a redirect to another site drops the credentials`() {
        val request = get().header("Authorization", "Bearer t").build()

        webViewFetchFollowUp(
            request,
            "https://other.example.net/x",
            true,
            true,
        )!!.header("Authorization").shouldBeNull()
    }

    @Test
    fun `a redirect on the same site keeps the credentials`() {
        val request = get().header("Authorization", "Bearer t").build()

        webViewFetchFollowUp(request, "/next", true, true)!!.header("Authorization") shouldBe "Bearer t"
    }

    @Test
    fun `a redirected post is not replayed`() {
        webViewFetchFollowUp(get().post("a".toRequestBody()).build(), "/next", true, true).shouldBeNull()
    }

    @Test
    fun `a redirect off the web is not followed`() {
        webViewFetchFollowUp(get().build(), "file:///data/data/app/secret", true, true).shouldBeNull()
    }

    @Test
    fun `a redirect from https to http follows the client's setting`() {
        webViewFetchFollowUp(get().build(), "http://www.example.com/", true, followSslRedirects = false).shouldBeNull()
    }

    @Test
    fun `a client that follows no redirects gets none here either`() {
        webViewFetchFollowUp(get().build(), "/next", followRedirects = false, followSslRedirects = true).shouldBeNull()
    }

    @Test
    fun `a custom port is a different page`() {
        webViewFetchOrigin("https://h.example.com:8443/x".toHttpUrl()) shouldBe "https://h.example.com:8443"
    }
}
