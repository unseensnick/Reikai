package yokai.novel.host

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.novel.registry.LnRegistry

/**
 * On-device integration test for the headless QuickJS stack (NOT a CI test: network-dependent).
 *
 * Exercises the *production* classes, not an inline spike:
 *  - [LnPluginHost] running real lnreader plugins from the registry with NO WebView and NO Activity
 *    (the prerequisite for background novel updates / downloads), across a broad sample so engine
 *    incompatibilities surface per-plugin.
 *  - [JavaScriptEngine] (the extensions-lib helper, now dokar-backed after dropping app.cash.quickjs)
 *    on representative synchronous snippets, so the manga-source path is covered too.
 *
 * Read the full per-plugin breakdown from logcat tag "HeadlessJsTest".
 */
@RunWith(AndroidJUnit4::class)
class HeadlessJsIntegrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lnPluginsRunInProductionHeadlessHost() = runBlocking {
        val client = Injekt.get<NetworkHelper>().client
        val host = LnPluginHost(context, client)
        val loader = LnPluginLoader(context, client)
        val report = StringBuilder("\n===== Production headless LN host =====\n")

        // Pull the real registry instead of hardcoding (fragile) plugin filenames. Test reinstalls
        // wipe app data, so the user's installed set is empty here; the registry is the source of truth.
        val entries = runCatching {
            client.newCall(Request.Builder().url(REGISTRY_URL).build()).execute().use { res ->
                check(res.isSuccessful) { "registry HTTP ${res.code}" }
                LnRegistry.parse(res.body?.string().orEmpty())
            }
        }.getOrElse {
            report.appendLine("registry fetch FAILED: ${it.message}")
            Log.i(TAG, report.toString())
            assertTrue("Could not fetch the lnreader registry; check connectivity.\n$report", false)
            return@runBlocking
        }

        // Anchor the sample with spike-proven, non-Cloudflare sources (searched first, so the
        // end-to-end assertion isn't hostage to which plugins happen to sort first), then add a
        // broad alphabetical slice for engine-compatibility breadth.
        // Anchors exercise specific runtime paths so regressions resurface here: novelbin →
        // `new URL(href, base)` + object-init URLSearchParams (ReadNovelFull family); wuxiaworld →
        // fetchProto (gRPC-web / protobuf). Searched first, so the end-to-end assertion is stable.
        val anchorIds = listOf("novelhall", "scribblehub", "novelbin", "wuxiaworld")
        val anchors = anchorIds.mapNotNull { id -> entries.firstOrNull { it.id == id } }
        val rest = entries
            .filter { it.lang.equals("English", ignoreCase = true) && it.id !in anchorIds }
            .take(SAMPLE_SIZE)
        val sample = anchors + rest
        report.appendLine("registry: ${entries.size} plugins; testing ${sample.size} (${anchors.size} anchors + breadth)")

        var fetchOk = 0
        var loadOk = 0
        var searchOk = 0
        var fullChain = 0
        val loadFailures = mutableListOf<String>()

        try {
            for (entry in sample) {
                val source = runCatching { loader.fetchSource(entry.url, forceRefresh = false) }
                    .getOrElse { report.appendLine("FETCH FAIL ${entry.id}: ${it.message}"); continue }
                fetchOk++

                val info = runCatching { host.loadPlugin(entry.id, source, entry.iconUrl, entry.lang) }
                    .getOrElse {
                        val msg = "${entry.id}: ${it.message}"
                        loadFailures += msg
                        report.appendLine("LOAD FAIL $msg")
                        continue
                    }
                loadOk++
                report.appendLine("loaded ${info.name} (id=${info.id} v=${info.version})")

                // searchNovels exercises the full fetch (OkHttp) + cheerio-parse path headlessly.
                // Cap network-bound calls so the suite stays bounded; site 404 / Cloudflare blocks
                // are environmental, not engine faults, so they don't fail the test.
                if (searchAttempts >= SEARCH_CAP) continue
                searchAttempts++
                val results = runCatching { host.searchNovels(info.id, "world", 1) }
                    .getOrElse { report.appendLine("  search ERROR: ${it.message}"); continue }
                report.appendLine("  search -> ${results.size} results (first='${results.firstOrNull()?.name}')")
                if (results.isEmpty()) continue
                searchOk++

                if (fullChain >= FULL_CHAIN_CAP) continue
                val path = results.first().path
                val novel = runCatching { host.parseNovel(info.id, path) }
                    .getOrElse { report.appendLine("  parseNovel ERROR: ${it.message}"); continue }
                report.appendLine("  parseNovel -> '${novel.name}' chapters=${novel.chapters?.size ?: 0}")
                val chapterPath = novel.chapters?.firstOrNull()?.path ?: continue
                val text = runCatching { host.parseChapter(info.id, chapterPath) }
                    .getOrElse { report.appendLine("  parseChapter ERROR: ${it.message}"); continue }
                report.appendLine("  parseChapter -> ${text.length} chars")
                if (text.isNotBlank()) fullChain++
            }
        } finally {
            host.destroy()
        }

        report.appendLine("--- summary ---")
        report.appendLine("fetched=$fetchOk loaded=$loadOk searched=$searchOk fullChain=$fullChain")
        if (loadFailures.isNotEmpty()) {
            report.appendLine("ENGINE LOAD FAILURES (investigate: missing polyfill / unsupported global):")
            loadFailures.forEach { report.appendLine("  - $it") }
        }
        report.appendLine("=======================================")
        Log.i(TAG, report.toString())

        // Engine compatibility (deterministic): every plugin whose source we fetched must load
        // under the headless runtime. A load failure is a real engine gap (missing polyfill /
        // unsupported global), not network flakiness, so fail on it.
        assertTrue("No plugin sources fetched; check connectivity.\n$report", fetchOk > 0)
        assertTrue("Some plugins failed to load in the headless host.\n$report", loadOk == fetchOk)
        // End-to-end proof: at least one source completed search -> parseNovel -> parseChapter
        // entirely off-thread (the whole point of the WebView -> QuickJS migration). The anchors
        // make this reliable; site Cloudflare blocks on other sources are tolerated.
        assertTrue("No plugin completed the full search->parse chain headlessly.\n$report", fullChain > 0)
    }

    /** Migrated extensions-lib JS engine (dokar-backed). Covers the manga-source path. */
    @Test
    fun javaScriptEngineEvaluatesSyncSnippets() = runBlocking {
        val js = JavaScriptEngine(context)
        assertEquals("ab", js.evaluate<String>("'a' + 'b'"))
        assertEquals("ABC", js.evaluate<String>("'abc'.toUpperCase()"))
        assertEquals("{\"x\":[1,2,3]}", js.evaluate<String>("JSON.stringify({ x: [1, 2, 3] })"))
        assertEquals("1,2,3", js.evaluate<String>("[3, 1, 2].sort().join(',')"))
        assertEquals("abc", js.evaluate<String>("'a1b2c3'.replace(/[0-9]/g, '')"))
        assertEquals("5", js.evaluate<String>("String(Math.max(1, 5, 3))"))
        assertEquals(true, js.evaluate<Boolean>("2 > 1"))
        // Representative of how manga sources historically used JavaScriptEngine (deobfuscation):
        // a self-invoking function that computes a value, bitwise/hex math, a multi-statement script
        // (completion value = last expression), Array transforms, and UTF-8 decode. These confirm
        // dokar evaluates real-world snippets the same as the previous (app.cash.quickjs) engine.
        assertEquals("ABC", js.evaluate<String>(
            "(function(){var s='';for(var i=0;i<3;i++)s+=String.fromCharCode(65+i);return s;})()"))
        assertEquals("ff", js.evaluate<String>("(255).toString(16)"))
        assertEquals("255", js.evaluate<String>("String(parseInt('ff', 16))"))
        assertEquals("6", js.evaluate<String>("var a=[1,2,3]; String(a.reduce(function(x,y){return x+y;},0))"))
        assertEquals("2,4,6", js.evaluate<String>("[1,2,3].map(function(n){return n*2;}).join(',')"))
        assertEquals("héllo", js.evaluate<String>("decodeURIComponent('h%C3%A9llo')"))
        // Number marshalling: tolerate Int/Long/Double across the JNI boundary.
        val product = js.evaluate<Any?>("6 * 7").toString()
        assertTrue("expected 42, got '$product'", product == "42" || product == "42.0")
    }

    private var searchAttempts = 0

    companion object {
        private const val TAG = "HeadlessJsTest"
        private const val REGISTRY_URL =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
        private const val SAMPLE_SIZE = 30
        private const val SEARCH_CAP = 12
        private const val FULL_CHAIN_CAP = 6
    }
}
