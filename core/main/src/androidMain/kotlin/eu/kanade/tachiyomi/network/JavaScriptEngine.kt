package eu.kanade.tachiyomi.network

import android.content.Context
import com.dokar.quickjs.QuickJs
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.Dispatchers

/**
 * Util for evaluating JavaScript in sources.
 *
 * Backed by the headless QuickJS engine (com.dokar.quickjs), the same engine the LN plugin host
 * uses, so the app ships a single native JS library.
 */
class JavaScriptEngine(context: Context) {

    /**
     * Evaluate arbitrary JavaScript code and get the result as a primitive type
     * (e.g., String, Int).
     *
     * @since extensions-lib 1.4
     * @param script JavaScript to execute.
     * @return Result of JavaScript code as a primitive type.
     */
    @Suppress("UNUSED", "UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T = withIOContext {
        val engine = QuickJs.create(Dispatchers.IO)
        try {
            engine.evaluate<Any?>(script) as T
        } finally {
            engine.close()
        }
    }
}
