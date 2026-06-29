package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A browser-based Cloudflare solver (Byparr/Camoufox = Firefox, FlareSolverr = Chrome) renders a
 * JSON-API response inside the browser's JSON/plaintext viewer, so a JSON source receives HTML and
 * throws. These pin [unwrapBrowserJsonViewer]: the wrapper is unwrapped to raw JSON, real HTML
 * pages (the case for HTML sources) are left untouched.
 */
class FlareSolverrJsonViewerTest {

    @Test
    fun `Firefox plaintext viewer is unwrapped to raw json`() {
        val wrapped = "<html><head><link rel=\"stylesheet\" href=\"resource://content-accessible/" +
            "plaintext.css\"></head><body><pre>{\"data\":[{\"id\":1,\"title\":\"X\"}]}</pre></body></html>"
        unwrapBrowserJsonViewer(wrapped) shouldBe "{\"data\":[{\"id\":1,\"title\":\"X\"}]}"
    }

    @Test
    fun `Chrome json-formatter viewer is unwrapped to raw json`() {
        val wrapped = "<html><head><meta name=\"color-scheme\" content=\"light dark\">" +
            "<meta charset=\"utf-8\"></head><body><pre>{\"a\":1}</pre>" +
            "<div class=\"json-formatter-container\"></div></body></html>"
        unwrapBrowserJsonViewer(wrapped) shouldBe "{\"a\":1}"
    }

    @Test
    fun `html entities inside the json are decoded back to their characters`() {
        val wrapped = "<html><body><pre>{\"d\":\"a &lt;b&gt; &amp; c\"}</pre></body></html>"
        unwrapBrowserJsonViewer(wrapped) shouldBe "{\"d\":\"a <b> & c\"}"
    }

    @Test
    fun `a top-level json array is unwrapped`() {
        unwrapBrowserJsonViewer("<html><body><pre>[1,2,3]</pre></body></html>") shouldBe "[1,2,3]"
    }

    @Test
    fun `a raw json body that is not wrapped is left as-is`() {
        unwrapBrowserJsonViewer("{\"a\":1}") shouldBe null
    }

    @Test
    fun `a real html page without a json pre is left as-is`() {
        unwrapBrowserJsonViewer("<html><body><div class=\"story\">Result</div></body></html>") shouldBe null
    }

    @Test
    fun `a pre holding non-json text is left as-is`() {
        unwrapBrowserJsonViewer("<html><body><pre>not json</pre></body></html>") shouldBe null
    }

    @Test
    fun `an unsolved cloudflare challenge page is left as-is`() {
        val challenge = "<html lang=\"en-US\" dir=\"ltr\"><head><title>Just a moment...</title></head>" +
            "<body><div id=\"cf-please-wait\"></div></body></html>"
        unwrapBrowserJsonViewer(challenge) shouldBe null
    }
}
