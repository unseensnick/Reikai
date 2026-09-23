package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FlareSolverrCommandTest {

    private fun cookie(name: String, value: String = "v") =
        Cookie.Builder().name(name).value(value).domain("www.novelupdates.com").build()

    private fun cookiesSent(cookies: List<Cookie>): List<Pair<String, String>>? =
        flareSolverrCommand("https://www.novelupdates.com/reading-list/", false, null, null, cookies)["cookies"]
            ?.jsonArray
            ?.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content }

    @Test
    fun `the site's cookies go to the solver, so a signed-in page comes back signed in`() {
        cookiesSent(listOf(cookie("wordpress_logged_in_abc", "session"))) shouldBe
            listOf("wordpress_logged_in_abc" to "session")
    }

    @Test
    fun `Cloudflare's own cookies stay behind`() {
        cookiesSent(listOf(cookie("cf_clearance"), cookie("__cf_bm"), cookie("site"))) shouldBe listOf("site" to "v")
    }

    @Test
    fun `a cookie the source set on the request goes when the jar has none, as OkHttp sends it`() {
        cookiesToForward(emptyList(), "session=abc; lang=en", "https://www.example.com/".toHttpUrl())
            .map { it.name to it.value } shouldBe listOf("session" to "abc", "lang" to "en")
    }

    @Test
    fun `the jar's cookies win over a Cookie header, as OkHttp sends them`() {
        cookiesToForward(listOf(cookie("site", "jar")), "site=header", "https://www.example.com/".toHttpUrl())
            .map { it.value } shouldBe listOf("jar")
    }

    @Test
    fun `a jar with nothing for the site sends no cookies at all`() {
        cookiesSent(emptyList()).shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://solver.example.com",
            "http://10.10.1.13:8192",
            "http://192.168.1.5:8191",
            "http://172.20.0.2:8191",
            "http://100.101.1.1:8191",
            "http://localhost:8191",
            "http://nas:8191",
            "http://solver.lan:8191",
            "http://[fd12::1]:8191",
        ],
    )
    fun `secrets go to a solver over https or on the user's own network`(url: String) {
        isPrivateChannel(url) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://solver.example.com:8191",
            "http://203.0.113.7:8191",
            "not a url",
            // Public neighbours of the private ranges, which share their first octet.
            "http://172.64.1.1:8191",
            "http://172.15.0.1:8191",
            "http://100.128.0.1:8191",
            "http://100.63.0.1:8191",
            "http://[2001:db8::5]:8191",
        ],
    )
    fun `secrets never cross the internet in the clear`(url: String) {
        isPrivateChannel(url) shouldBe false
    }

    @Test
    fun `a cookie the solver only echoed back is not stored again`() {
        val echoed = FlareSolverrCookie(name = "wordpress_logged_in_abc", value = "session")
        val earned = FlareSolverrCookie(name = "cf_clearance", value = "new")

        cookiesToKeep(listOf(echoed, earned), listOf(cookie("wordpress_logged_in_abc", "session"))) shouldBe
            listOf(earned)
    }

    @Test
    fun `a plugin's FormData post reaches the solver as the form fields it holds`() {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("action", "nd_getchapters")
            .addFormDataPart("mypostid", "197357")
            .build()

        flareSolverrPostData(body) shouldBe "action=nd_getchapters&mypostid=197357"
    }

    @Test
    fun `a space reaches the solver as a space, since it keeps a plus literal`() {
        val body = FormBody.Builder().add("q", "bald guy+").build()

        flareSolverrPostData(body) shouldBe "q=bald%20guy%2B"
    }

    @Test
    fun `a multipart post with a file goes as its raw text`() {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("f", "a.txt", "x".toRequestBody())
            .build()

        flareSolverrPostData(body) shouldStartWith "--"
    }

    @Test
    fun `a string body goes to the solver unchanged`() {
        flareSolverrPostData("a=1&b=2".toRequestBody()) shouldBe "a=1&b=2"
    }

    @Test
    fun `a cookie the site changed while the solver was there is stored`() {
        val changed = FlareSolverrCookie(name = "wordpress_logged_in_abc", value = "renewed")

        cookiesToKeep(listOf(changed), listOf(cookie("wordpress_logged_in_abc", "session"))) shouldBe listOf(changed)
    }
}
