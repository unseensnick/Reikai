package reikai.novel.host

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mihon.app.di.appGraph
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.toNovel
import reikai.novel.registry.LnRegistry
import reikai.novel.source.buildOptions
import reikai.novel.source.defaultFilterValues
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import com.dokar.quickjs.QuickJs as DokarQuickJs

/**
 * On-device integration test for the headless QuickJS stack, not a CI test: it is network-dependent
 * and pulls the live plugin registry, so it runs on demand.
 *
 * Covers [LnPluginHost] running real lnreader plugins with no WebView and no Activity, which is what
 * background novel updates need, plus [JavaScriptEngine] for the manga-source path. The per-plugin
 * breakdown goes to logcat tag "HeadlessJsTest"; how to run it is in docs/dev/testing.md.
 */
@RunWith(AndroidJUnit4::class)
class HeadlessJsIntegrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // Stands in for the graph's app scope; only the hosts' idle sweeper runs on it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun lnPluginsRunInProductionHeadlessHost() = runBlocking {
        val networkHelper = Injekt.get<NetworkHelper>()
        val client = networkHelper.client
        val host = LnPluginHost(scope, context, networkHelper, context.appGraph.preferenceStore)
        val loader = LnPluginLoader(context, networkHelper)
        val report = StringBuilder("\n===== Production headless LN host =====\n")

        // Pull the real registry instead of hardcoding (fragile) plugin filenames. Test reinstalls
        // wipe app data, so the installed set is empty here; the registry is the source of truth.
        val entries = runCatching {
            client.newCall(Request.Builder().url(REGISTRY_URL).build()).execute().use { res ->
                check(res.isSuccessful) { "registry HTTP ${res.code}" }
                LnRegistry.parse(res.body.string())
            }
        }.getOrElse {
            report.appendLine("registry fetch FAILED: ${it.message}")
            Log.i(TAG, report.toString())
            assertTrue("Could not fetch the lnreader registry; check connectivity.\n$report", false)
            return@runBlocking
        }

        // Anchor the sample with proven, non-Cloudflare sources (searched first, so the end-to-end
        // assertion isn't hostage to which plugins sort first), then add a breadth slice. Anchors:
        // novelbin -> new URL(href, base) + object-init URLSearchParams; wuxiaworld -> fetchProto.
        // WTRLAB exercises the @libs/aes (noble-ciphers gcm) + atob polyfills: it AES-decrypts
        // chapter bodies, so a full chain on it proves those shims work headlessly.
        val anchorIds = listOf("novelhall", "scribblehub", "novelbin", "wuxiaworld", "WTRLAB")
        val anchors = anchorIds.mapNotNull { id -> entries.firstOrNull { it.id == id } }
        val rest = entries
            .filter { it.lang.equals("English", ignoreCase = true) && it.id !in anchorIds }
            .take(SAMPLE_SIZE)
        val sample = anchors + rest
        report.appendLine(
            "registry: ${entries.size} plugins; testing ${sample.size} (${anchors.size} anchors + breadth)",
        )

        var fetchOk = 0
        var loadOk = 0
        var searchOk = 0
        var fullChain = 0
        val loadFailures = mutableListOf<String>()

        try {
            for (entry in sample) {
                val source = runCatching { loader.download(entry.url) }
                    .getOrElse {
                        report.appendLine("FETCH FAIL ${entry.id}: ${it.message}")
                        continue
                    }
                fetchOk++

                val info = runCatching { host.load(source, entry.iconUrl, entry.lang) }
                    .getOrElse {
                        val msg = "${entry.id}: ${it.message}"
                        loadFailures += msg
                        report.appendLine("LOAD FAIL $msg")
                        continue
                    }
                loadOk++
                report.appendLine("loaded ${info.name} (id=${info.id} v=${info.version})")

                // searchNovels exercises the full fetch (OkHttp) + cheerio-parse path headlessly.
                // Site 404 / Cloudflare blocks are environmental, not engine faults, so they don't
                // fail the test.
                if (searchAttempts >= SEARCH_CAP) continue
                searchAttempts++
                val results = runCatching { host.searchNovels(info.id, "world", 1) }
                    .getOrElse {
                        report.appendLine("  search ERROR: ${it.message}")
                        continue
                    }
                report.appendLine("  search -> ${results.size} results (first='${results.firstOrNull()?.name}')")
                if (results.isEmpty()) continue
                searchOk++

                if (fullChain >= FULL_CHAIN_CAP) continue
                val path = results.first().path
                val novel = runCatching { host.parseNovel(info.id, path) }
                    .getOrElse {
                        report.appendLine("  parseNovel ERROR: ${it.message}")
                        continue
                    }
                report.appendLine("  parseNovel -> '${novel.name}' chapters=${novel.chapters?.size ?: 0}")
                val chapterPath = novel.chapters?.firstOrNull()?.path ?: continue
                val text = runCatching { host.parseChapter(info.id, chapterPath) }
                    .getOrElse {
                        report.appendLine("  parseChapter ERROR: ${it.message}")
                        continue
                    }
                report.appendLine("  parseChapter -> ${text.length} chars")
                if (text.isNotBlank()) fullChain++
            }
        } finally {
            host.close()
        }

        report.appendLine("--- summary ---")
        report.appendLine("fetched=$fetchOk loaded=$loadOk searched=$searchOk fullChain=$fullChain")
        if (loadFailures.isNotEmpty()) {
            report.appendLine("ENGINE LOAD FAILURES (investigate: missing polyfill / unsupported global):")
            loadFailures.forEach { report.appendLine("  - $it") }
        }
        report.appendLine("=======================================")
        Log.i(TAG, report.toString())

        // Engine compatibility (deterministic): every plugin whose source we fetched must load under
        // the headless runtime. A load failure is a real engine gap, not network flakiness.
        assertTrue("No plugin sources fetched; check connectivity.\n$report", fetchOk > 0)
        assertTrue("Some plugins failed to load in the headless host.\n$report", loadOk == fetchOk)
        // End-to-end proof: at least one source completed search -> parseNovel -> parseChapter
        // entirely off-thread. The anchors make this reliable; Cloudflare blocks elsewhere are tolerated.
        assertTrue("No plugin completed the full search->parse chain headlessly.\n$report", fullChain > 0)
    }

    /**
     * Whole-registry health sweep, driven by scripts/plugin-sweep.ps1:
     * `am instrument -e class <fqcn>#lnPluginRegistrySweep -e registryUrl <url> [-e anchorIds a,b]`.
     * Probes every plugin via popularNovels(1) from its own default filters (Cloudflare solved through
     * the configured FlareSolverr), logging `RESULT <id> [<lang>] <STATUS> ...` per plugin under tag
     * "HeadlessJsTest". Anchors also run a full parseNovel + parseChapter chain. With no registryUrl
     * it skips, so a normal test run never triggers a network sweep.
     */
    @Test
    fun lnPluginRegistrySweep() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val registryUrl = args.getString("registryUrl")
        if (registryUrl.isNullOrBlank()) {
            Log.i(TAG, "lnPluginRegistrySweep skipped: pass -e registryUrl <url> to run a sweep.")
            return@runBlocking
        }
        val anchorIds = args.getString("anchorIds")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

        val networkHelper = Injekt.get<NetworkHelper>()
        val client = networkHelper.client
        val host = LnPluginHost(scope, context, networkHelper, context.appGraph.preferenceStore)
        val loader = LnPluginLoader(context, networkHelper)

        val entries = runCatching {
            client.newCall(Request.Builder().url(registryUrl).build()).execute().use { res ->
                check(res.isSuccessful) { "registry HTTP ${res.code}" }
                LnRegistry.parse(res.body.string())
            }
        }.getOrElse {
            Log.i(TAG, "registry fetch FAILED ($registryUrl): ${it.message}")
            assertTrue("Could not fetch the registry at $registryUrl.", false)
            return@runBlocking
        }
        Log.i(TAG, "===== sweep: ${entries.size} plugins from $registryUrl via popularNovels(1) =====")

        var fetchOk = 0
        var loadOk = 0
        var popularOk = 0
        var fullChain = 0
        try {
            for (entry in entries) {
                val tag = "${entry.id} [${entry.lang}]"
                val source = runCatching { loader.download(entry.url) }
                    .getOrElse {
                        Log.i(TAG, "RESULT $tag FETCH_FAIL ${it.message?.take(160)}")
                        continue
                    }
                fetchOk++

                val info = runCatching { host.load(source, entry.iconUrl, entry.lang) }
                    .getOrElse {
                        Log.i(TAG, "RESULT $tag LOAD_FAIL ${it.message?.take(160)}")
                        continue
                    }
                loadOk++

                val opts = buildOptions(info.filters, defaultFilterValues(info.filters), showLatest = false)
                val popular = runCatching { host.popularNovels(info.id, 1, opts) }
                    .getOrElse {
                        Log.i(TAG, "RESULT $tag POPULAR_ERROR ${it.message?.take(160)}")
                        continue
                    }
                if (popular.isEmpty()) {
                    Log.i(TAG, "RESULT $tag POPULAR_EMPTY 0")
                    continue
                }
                popularOk++

                if (entry.id in anchorIds) {
                    var chapters = 0
                    var chars = 0
                    var chainErr: String? = null
                    runCatching {
                        val novel = host.parseNovel(info.id, popular.first().path)
                        chapters = novel.chapters?.size ?: 0
                        novel.chapters?.firstOrNull()?.path?.let { chars = host.parseChapter(info.id, it).length }
                    }.onFailure { chainErr = it.message?.take(120) }
                    if (chars > 0) fullChain++
                    Log.i(
                        TAG,
                        "RESULT $tag OK popular=${popular.size} chain{chapters=$chapters chars=$chars" +
                            (chainErr?.let { " ERR $it" } ?: "") + "}",
                    )
                } else {
                    Log.i(TAG, "RESULT $tag OK popular=${popular.size} first='${popular.first().name.take(40)}'")
                }
            }
        } finally {
            host.close()
        }
        Log.i(
            TAG,
            "===== sweep summary: fetched=$fetchOk loaded=$loadOk popularOk=$popularOk fullChain=$fullChain " +
                "of ${entries.size} =====",
        )
        assertTrue("No plugin sources fetched from $registryUrl.", fetchOk > 0)
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
        // Representative of how manga sources historically used JavaScriptEngine (deobfuscation).
        assertEquals(
            "ABC",
            js.evaluate<String>("(function(){var s='';for(var i=0;i<3;i++)s+=String.fromCharCode(65+i);return s;})()"),
        )
        assertEquals("ff", js.evaluate<String>("(255).toString(16)"))
        assertEquals("255", js.evaluate<String>("String(parseInt('ff', 16))"))
        assertEquals("6", js.evaluate<String>("var a=[1,2,3]; String(a.reduce(function(x,y){return x+y;},0))"))
        assertEquals("2,4,6", js.evaluate<String>("[1,2,3].map(function(n){return n*2;}).join(',')"))
        assertEquals("héllo", js.evaluate<String>("decodeURIComponent('h%C3%A9llo')"))
        // Number marshalling: tolerate Int/Long/Double across the JNI boundary.
        val product = js.evaluate<Any?>("6 * 7").toString()
        assertTrue("expected 42, got '$product'", product == "42" || product == "42.0")
    }

    /**
     * fetchProto hands the decoded message over as LNReader does, so an enum stays a number: WuxiaWorld
     * switches on the numeric status and filed every novel as Completed when it arrived as a name.
     */
    @Test
    fun grpcWebBodyKeepsEnumsNumeric() = runBlocking {
        val q = DokarQuickJs.create(Dispatchers.IO)
        try {
            q.function("__lnLog") { null }
            q.evaluate<Any?>("(function(){globalThis.self=globalThis;globalThis.window=globalThis;})()")
            LnPluginHost.RUNTIME_ASSETS.forEach { path ->
                q.evaluate<Any?>(context.assets.open(path).bufferedReader().use { it.readText() })
            }
            val status = q.evaluate<String>(
                """
                (function () {
                  var root = protobuf.parse(
                    'syntax = "proto3"; enum Status { Finished = 0; Active = 1; } message Item { Status status = 1; }'
                  ).root;
                  var Item = root.lookupType("Item");
                  var msg = Item.encode({ status: 1 }).finish();
                  var framed = new Uint8Array(5 + msg.length);
                  framed[4] = msg.length;
                  framed.set(msg, 5);
                  var status = globalThis.__lnDecodeGrpcWebBody(Item, framed).status;
                  return typeof status + ":" + status;
                })()
                """.trimIndent(),
            )
            assertEquals("number:1", status)
        } finally {
            q.close()
        }
    }

    /**
     * The host's text codecs answer bad input as a browser does (WHATWG Encoding): a malformed UTF-8
     * sequence decodes to U+FFFD instead of throwing, which a fetched page's `text()` relies on, and a
     * lone surrogate encodes to U+FFFD. `fatal` still throws. Inline: no network.
     */
    @Test
    fun textCodecsReplaceMalformedInputLikeABrowser() = runBlocking {
        val q = DokarQuickJs.create(Dispatchers.IO)
        try {
            q.function("__lnLog") { null }
            q.evaluate<Any?>("(function(){globalThis.self=globalThis;globalThis.window=globalThis;})()")
            LnPluginHost.RUNTIME_ASSETS.forEach { path ->
                q.evaluate<Any?>(context.assets.open(path).bufferedReader().use { it.readText() })
            }
            val results = q.evaluate<String>(
                """
                (function () {
                  function d(bytes, opts) { return new TextDecoder("utf-8", opts).decode(new Uint8Array(bytes)); }
                  function hex(s) { var o = []; for (var i = 0; i < s.length; i++) o.push(s.charCodeAt(i).toString(16)); return o.join(" "); }
                  var fatal;
                  try { d([0xff], { fatal: true }); fatal = "no-throw"; } catch (e) { fatal = e.name; }
                  return [
                    hex(d([0x61, 0xff, 0x62])),
                    hex(d([0xe2, 0x82])),
                    hex(d([0xc0, 0xaf])),
                    hex(d([0xed, 0xa0, 0x80])),
                    d([0xef, 0xbb, 0xbf, 0x61]),
                    hex(d([0xf0, 0x9f, 0x98, 0x80])),
                    hex(new TextDecoder().decode(new Uint16Array([0x6261]))),
                    fatal,
                    Array.from(new TextEncoder().encode("a\uD800b")).join(","),
                  ].join("|");
                })()
                """.trimIndent(),
            )
            val expected = listOf(
                "61 fffd 62",
                "fffd",
                "fffd fffd",
                "fffd fffd fffd",
                "a",
                "d83d de00",
                "61 62",
                "TypeError",
                "97,239,191,189,98",
            )
            assertEquals(expected.joinToString("|"), results)
        } finally {
            q.close()
        }
    }

    /**
     * The query shim decodes a malformed escape as a browser does, leaving a stray `%` and reading bad
     * UTF-8 bytes as U+FFFD, where decodeURIComponent threw and failed a plugin's whole page. Inline: no network.
     */
    @Test
    fun queryShimDecodesMalformedEscapesLikeABrowser() = runBlocking {
        val q = DokarQuickJs.create(Dispatchers.IO)
        try {
            q.function("__lnLog") { null }
            q.evaluate<Any?>("(function(){globalThis.self=globalThis;globalThis.window=globalThis;})()")
            LnPluginHost.RUNTIME_ASSETS.forEach { path ->
                q.evaluate<Any?>(context.assets.open(path).bufferedReader().use { it.readText() })
            }
            val results = q.evaluate<String>(
                """
                (function () {
                  var p = new URLSearchParams("k=%B0%A1&j=%E4%BD%A0");
                  var k = p.get("k");
                  return [
                    new URL("https://a.org/s?q=100%").searchParams.get("q"),
                    p.get("j"),
                    k.charCodeAt(0).toString(16) + " " + k.charCodeAt(1).toString(16),
                  ].join("|");
                })()
                """.trimIndent(),
            )
            assertEquals("100%|你|fffd fffd", results)
        } finally {
            q.close()
        }
    }

    /**
     * A stored undefined reads back as undefined, as LNReader's Storage.get returns item.value
     * (refs/lnreader-main/src/plugins/helpers/storage.ts). JSON drops the undefined, so the envelope
     * carried only its timestamp and came back whole. Inline plugin: no network.
     */
    @Test
    fun storageReadsBackAStoredUndefined() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        val plugin = """
            var storage = require('@libs/storage').storage;
            module.exports.default = {
              id: 'storage-undefined-test', name: 'T', site: 'https://example.org', version: '1.0.0',
              parseChapter: async function () {
                storage.set('k', undefined);
                return String(typeof storage.get('k'));
              },
            };
        """.trimIndent()
        host.load(plugin)

        assertEquals("undefined", host.parseChapter("storage-undefined-test", "/c"))
    }

    /**
     * A plugin that reads the site's storage while it is constructed, as RanobeLib reads its sign-in,
     * sees what the WebView kept on its next call, without an app restart. Inline plugin: no network.
     */
    @Test
    fun webStorageReachesAPluginThatReadsItAtConstruction() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        val plugin = """
            var localStorage = require('@libs/storage').localStorage;
            var stored = localStorage.get();
            module.exports.default = {
              id: 'web-storage-test', name: 'T', site: 'https://example.org', version: '1.0.0',
              auth: stored ? stored.auth : 'none',
              parseChapter: async function () { return String(this.auth); },
            };
        """.trimIndent()
        host.load(plugin)
        host.storeWebStorage("web-storage-test", WebStorageSnapshot(local = "{}", session = "{}"))
        host.parseChapter("web-storage-test", "/c")

        host.storeWebStorage("web-storage-test", WebStorageSnapshot(local = """{"auth":"signed-in"}""", session = "{}"))

        assertEquals("signed-in", host.parseChapter("web-storage-test", "/c"))
    }

    /** Plugin code runs in the runtime's global scope, so the id-taking storage bridge must not be on it. */
    @Test
    fun aPluginCannotReadAnotherPluginsStorage() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        host.load("module.exports.default = { id: 'victim-test', name: 'V', site: 'https://a.org', version: '1' };")
        host.storeWebStorage("victim-test", WebStorageSnapshot(local = """{"auth":"secret"}""", session = "{}"))
        val thief = """
            module.exports.default = {
              id: 'thief-test', name: 'T', site: 'https://b.org', version: '1',
              parseChapter: async function () {
                try { return String(__lnGetStorage('victim-test', 'webview:local')); } catch (e) { return 'blocked'; }
              },
            };
        """.trimIndent()
        host.load(thief)

        assertEquals("blocked", host.parseChapter("thief-test", "/c"))
    }

    /** A reload that now declares another plugin's id would take over that plugin's engine and storage. */
    @Test
    fun aReloadDeclaringAnotherIdIsRefused() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        host.load(
            "module.exports.default = { id: 'owner-test', name: 'O', site: 'https://a.org', version: '1'," +
                " parseChapter: async function () { return 'owner'; } };",
        )
        val impostor = "module.exports.default = { id: 'owner-test', name: 'I', site: 'https://b.org', version: '1'," +
            " parseChapter: async function () { return 'impostor'; } };"

        runCatching { host.load(impostor, expectedId = "impostor-test") }

        assertEquals("owner", host.parseChapter("owner-test", "/c"))
    }

    /**
     * Plugin code reaching the runtime's loader with a stub that declares another plugin's id is refused,
     * and the engine's storage is its own plugin's whatever id the stub declares. Inline plugins: no network.
     */
    @Test
    fun aPluginCannotLoadAStubUnderAnotherPluginsId() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        host.load("module.exports.default = { id: 'nested-victim', name: 'V', site: 'https://a.org', version: '1' };")
        host.storeWebStorage("nested-victim", WebStorageSnapshot(local = """{"auth":"secret"}""", session = "{}"))
        host.setSetting("nested-victim", "password", JsonPrimitive("hunter2"))
        val stub = "var s = require('@libs/storage');" +
            " globalThis.leak = JSON.stringify(s.localStorage.get()) + '|' + s.storage.get('password');" +
            " s.storage.set('password', 'pwned');" +
            " module.exports.default = { id: 'nested-victim', name: 'X' };"
        val attacker = """
            module.exports.default = {
              id: 'nested-attacker', name: 'A', site: 'https://b.org', version: '1',
              parseChapter: async function () {
                try { globalThis.__lnLoadPlugin(${JsonPrimitive(stub)}); } catch (e) { return 'refused'; }
                return String(globalThis.leak);
              },
            };
        """.trimIndent()
        host.load(attacker)

        val read = host.parseChapter("nested-attacker", "/c")

        val password = (host.getSetting("nested-victim", "password") as? JsonPrimitive)?.content
        assertEquals(listOf("refused", "hunter2"), listOf(read, password))
    }

    /** A script read before the installer accepted its declared id sees no plugin's storage. */
    @Test
    fun aScriptReadForInstallSeesNoStorageOfTheIdItDeclares() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        host.setSetting("read-victim", "password", JsonPrimitive("hunter2"))
        val impostor = "var s = require('@libs/storage');" +
            " module.exports.default = { id: 'read-victim', name: String(s.storage.get('password')) };"

        assertEquals("undefined", host.inspectPlugin(impostor).name)
    }

    /** The other half: an update of the plugin its URL is installed as reads that plugin's storage, as komga's site does. */
    @Test
    fun aScriptReadAsItsInstalledIdSeesItsStorage() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        host.setSetting("read-owner", "url", JsonPrimitive("https://self.hosted"))
        val plugin = "var s = require('@libs/storage');" +
            " module.exports.default = { id: 'read-owner', name: 'O', site: s.storage.get('url') };"

        assertEquals("https://self.hosted", host.inspectPlugin(plugin, trustedId = "read-owner").site)
    }

    /** A reload declaring another id is refused before its code meets that id's storage or its own. */
    @Test
    fun aRefusedReloadWritesNoStorage() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        val impostor = "require('@libs/storage').storage.set('k', 'x');" +
            " module.exports.default = { id: 'refused-other', name: 'I' };"

        runCatching { host.inspectPlugin(impostor, expectedId = "refused-owner") }

        val written = listOf(host.getSetting("refused-other", "k"), host.getSetting("refused-owner", "k"))
        assertEquals(listOf(null, null), written)
    }

    /**
     * Each script is read in an engine of its own, so one cannot leave a hook behind for the next, as a
     * wrapped loader in a shared engine read every later plugin's storage. Inline plugins: no network.
     */
    @Test
    fun aScriptCannotLeaveCodeBehindForTheNextOneRead() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        host.inspectPlugin(
            "globalThis.__lnLoadPlugin = function () { return { id: 'hijacked', name: 'H' }; };" +
                " module.exports.default = { id: 'hook-first', name: 'F' };",
        )

        assertEquals("hook-second", host.inspectPlugin("module.exports.default = { id: 'hook-second', name: 'S' };").id)
    }

    private suspend fun LnPluginHost.load(
        source: String,
        iconUrl: String? = null,
        lang: String? = null,
        expectedId: String? = null,
    ): LnPluginInfo = inspectPlugin(source, iconUrl, lang, expectedId).also { retainPlugin(it.id, source) }

    /** LNReader's opt-in to the browser's storage reaches the app, which keeps none for a plugin without it. */
    @Test
    fun aPluginAskingForTheSiteStorageSaysSo() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        val plugin = """
            module.exports.default = {
              id: 'web-storage-flag-test', name: 'T', site: 'https://example.org', version: '1.0.0',
              webStorageUtilized: true,
            };
        """.trimIndent()

        assertTrue(host.load(plugin).webStorageUtilized)
    }

    /** The other half of the opt-in: a plugin that never asks is never handed the site's storage. */
    @Test
    fun aPluginNotAskingForTheSiteStorageSaysNo() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        val plugin = """
            module.exports.default = {
              id: 'no-web-storage-test', name: 'T', site: 'https://example.org', version: '1.0.0',
            };
        """.trimIndent()

        assertFalse(host.load(plugin).webStorageUtilized)
    }

    /**
     * LNReader's newer statuses (plugins c432ad8) reach a stored novel as the shared status each reads as.
     * The host's NovelStatus lacked them, so a plugin naming one sent no status at all. Inline plugin: no network.
     */
    @Test
    fun aPluginsNewerStatusesReadAsSharedOnes() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        val plugin = """
            var NovelStatus = require('@libs/novelStatus').NovelStatus;
            module.exports.default = {
              id: 'status-test', name: 'T', site: 'https://example.org', version: '1.0.0',
              parseNovel: async function (path) { return { path: path, name: 'T', status: NovelStatus[path] }; },
            };
        """.trimIndent()
        host.load(plugin)

        val codes = listOf("Inactive", "STUB").map { host.parseNovel("status-test", it).toNovel("status-test").status }

        assertEquals(listOf(NovelStatusCode.ON_HIATUS, NovelStatusCode.LICENSED).map(Int::toLong), codes)
    }

    /**
     * A plugin whose constructor throws in its own engine is built again on the next call, so it
     * recovers once the cause is gone. A half-built engine used to be kept, answering "plugin not
     * loaded" until it idled out. Inline plugin: no network.
     */
    @Test
    fun aPluginWhoseReplayFailedLoadsAgainOnTheNextCall() = runBlocking {
        val host = LnPluginHost(scope, context, Injekt.get<NetworkHelper>(), context.appGraph.preferenceStore)
        val plugin = """
            var storage = require('@libs/storage').storage;
            if (storage.get('fail')) throw new Error('constructor failed');
            module.exports.default = {
              id: 'replay-failure-test', name: 'T', site: 'https://example.org', version: '1.0.0',
              parseChapter: async function () { return 'ok'; },
            };
        """.trimIndent()
        host.load(plugin)
        host.setSetting("replay-failure-test", "fail", JsonPrimitive(true))
        runCatching { host.parseChapter("replay-failure-test", "/c") }

        host.setSetting("replay-failure-test", "fail", null)

        assertEquals("ok", host.parseChapter("replay-failure-test", "/c"))
    }

    /**
     * The `app.cash.quickjs.QuickJs` compat class, used directly by extensions like Mangago
     * (issue #26). Covers plain evaluate plus the cross-instance compile -> execute -> evaluate
     * roundtrip those extensions rely on. Synchronous, exactly as extensions call it.
     */
    @Test
    fun appCashQuickJsCompatShimWorks() {
        QuickJs.create().use { qjs ->
            assertEquals("ab", qjs.evaluate("'a' + 'b'"))
            assertEquals("HELLO", qjs.evaluate("'hello'.toUpperCase()"))
            assertEquals("42", qjs.evaluate("String(6 * 7)"))
            // Cash returned arrays as Object[], which extensions test for with `is Array<*>`.
            val nested = qjs.evaluate("[['a'], 'b']") as Array<*>
            assertEquals(listOf("a"), (nested[0] as Array<*>).toList())
        }
        // Mangago compiles a helper on one (throwaway) engine, then executes that "bytecode" on the
        // engine that actually runs the page-descrambling code.
        val bytecode = QuickJs.create().use { it.compile("function add(a, b) { return a + b; }", "?") }
        val result = QuickJs.create().use { qjs ->
            qjs.execute(bytecode)
            qjs.evaluate("String(add(2, 3))")
        }
        assertEquals("5", result)
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
