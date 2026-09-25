package reikai.novel.host

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** How a page's storage, as the in-app browser reads it, becomes what a plugin's `localStorage.get()` returns. */
class WebStorageSnapshotTest {

    // evaluateJavascript hands back the script's string result JSON-encoded a second time.
    private fun evaluated(payload: String) = "\"" + payload.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `both storages are kept as the JSON object of their items`() {
        parseWebStorage(evaluated("""{"local":{"auth":"{\"t\":1}"},"session":{"s":"x"}}""")) shouldBe
            WebStorageSnapshot(local = """{"auth":"{\"t\":1}"}""", session = """{"s":"x"}""")
    }

    @Test
    fun `a page that may not read its storage leaves nothing to keep`() {
        parseWebStorage("null") shouldBe null
    }

    @Test
    fun `a page with no session storage keeps an empty one`() {
        parseWebStorage(evaluated("""{"local":{}}""")) shouldBe WebStorageSnapshot(local = "{}", session = "{}")
    }
}
