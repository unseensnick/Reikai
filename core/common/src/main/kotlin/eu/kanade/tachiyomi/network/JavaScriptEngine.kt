package eu.kanade.tachiyomi.network

import android.content.Context
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Util for evaluating JavaScript in sources.
 *
 * RK: backed by the headless QuickJS engine (com.dokar.quickjs), the same engine Reikai's LN
 * plugin host uses, so the app ships a single native JS library (both QuickJS bindings produce a
 * `libquickjs.so` and cannot coexist). The public `evaluate` signature is unchanged, so manga
 * extensions using extensions-lib see no difference.
 */
@Suppress("UNUSED", "UNCHECKED_CAST")
class JavaScriptEngine(context: Context) {

    /**
     * Evaluate arbitrary JavaScript code and get the result as a primtive type
     * (e.g., String, Int).
     *
     * @since extensions-lib 1.4
     * @param script JavaScript to execute.
     * @return Result of JavaScript code as a primitive type.
     */
    suspend fun <T> evaluate(script: String): T = withIOContext {
        val engine = QuickJs.create(Dispatchers.IO)
        try {
            engine.evaluate<Any?>(script) as T
        } finally {
            engine.close()
        }
    }
}
