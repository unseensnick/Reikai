package reikai.novel.host

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Plugins hand the host a JSON `FetchInit`. When strict decoding fails (a field has a shape the
 * serializer rejects), the bridge falls back to picking out the fields it understands rather than
 * aborting the whole fetch. These pin that lenient fallback.
 */
class LnHostBridgeTest {

    private val bridge = LnHostBridge(preferenceStore = mockk(relaxed = true), client = mockk(relaxed = true))

    @Test
    fun `blank options decode to defaults`() {
        bridge.parseFetchOpts("") shouldBe LnHostBridge.FetchOpts()
    }

    @Test
    fun `well-formed options keep their headers`() {
        bridge.parseFetchOpts("""{"method":"GET","headers":{"A":"1"}}""").headers shouldBe mapOf("A" to "1")
    }

    @Test
    fun `a malformed headers shape drops headers but keeps method and body`() {
        // headers is an array, not an object, so strict decode fails and the manual fallback runs:
        // the bad headers become null while method and body survive.
        val opts = bridge.parseFetchOpts("""{"method":"POST","headers":["bad"],"body":"data"}""")

        Triple(opts.method, opts.headers, opts.body) shouldBe Triple("POST", null, "data")
    }
}
