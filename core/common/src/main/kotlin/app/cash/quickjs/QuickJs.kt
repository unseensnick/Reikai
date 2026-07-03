package app.cash.quickjs

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.Executors
import com.dokar.quickjs.QuickJs as DokarQuickJs

/**
 * RK: compatibility class for the legacy `app.cash.quickjs.QuickJs` API.
 *
 * Many manga extensions compile against `app.cash.quickjs.QuickJs` and call it directly at
 * runtime (Cash App's original JS engine, which Mihon provides via zhanghai's drop-in fork).
 * Reikai instead ships dokar3's `com.dokar.quickjs` engine so a single native `libquickjs.so`
 * serves both the manga JS helper and the LN plugin host (two QuickJS bindings collide on that
 * `.so`). Without this class those extensions crash with
 * `Failed resolution of: Lapp/cash/quickjs/QuickJs;`.
 *
 * This bridges the small, frozen Cash surface onto the same dokar3 engine. The engine is
 * coroutine-based and QuickJS is single-threaded, so each instance confines its engine to one
 * dedicated thread and blocks on it (the Cash API was synchronous, called from background
 * threads). Kept from R8 by the existing `-keep class app.cash.quickjs.**` rule.
 */
@Suppress("unused", "UNCHECKED_CAST")
class QuickJs private constructor() : Closeable {

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "quickjs-compat").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Created lazily on the dedicated thread so all native access stays on one thread.
    private var engine: DokarQuickJs? = null

    private fun <R> onJsThread(block: suspend (DokarQuickJs) -> R): R = runBlocking(dispatcher) {
        val current = engine ?: DokarQuickJs.create(dispatcher).also { engine = it }
        block(current)
    }

    /** Evaluate JavaScript and return the result as a primitive (String, Int, ...). */
    fun evaluate(script: String): Any? = onJsThread { it.evaluate<Any?>(script) }

    /** Cash exposed a file-name overload; the name is only used for stack traces, so ignore it. */
    fun evaluate(script: String, fileName: String): Any? = evaluate(script)

    /**
     * Cash compiled JS to QuickJS bytecode; here the source itself is the "bytecode" and is
     * re-evaluated in [execute]. Behaviourally identical for how extensions use compile/execute
     * (define a function on one engine, run it on another) while avoiding any assumption that
     * compiled bytecode is portable across engine instances.
     */
    fun compile(sourceCode: String, fileName: String): ByteArray = sourceCode.toByteArray(Charsets.UTF_8)

    fun execute(bytecode: ByteArray): Any? = evaluate(String(bytecode, Charsets.UTF_8))

    /** Interface binding is unused by current extensions; fail loudly rather than silently. */
    fun <T : Any> get(name: String, type: Class<T>): T =
        throw UnsupportedOperationException("app.cash.quickjs.QuickJs.get is not supported")

    fun <T : Any> set(name: String, type: Class<T>, instance: T): Unit =
        throw UnsupportedOperationException("app.cash.quickjs.QuickJs.set is not supported")

    override fun close() {
        try {
            engine?.let { active -> runBlocking(dispatcher) { active.close() } }
        } finally {
            dispatcher.close()
        }
    }

    companion object {
        @JvmStatic
        fun create(): QuickJs = QuickJs()
    }
}
