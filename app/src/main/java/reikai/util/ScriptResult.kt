package reikai.util

import kotlinx.serialization.json.Json

/**
 * The string a script handed `evaluateJavascript`, which encodes it as a JSON string literal; null for a
 * `null` result or one that is no string. Megabytes for a page's markup, so a caller decodes off Main.
 */
fun scriptString(result: String?): String? =
    result?.let { runCatching { Json.decodeFromString<String?>(it) }.getOrNull() }
