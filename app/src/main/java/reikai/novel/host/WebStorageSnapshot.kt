package reikai.novel.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A site's local and session storage, each as the JSON object of its items, as LNReader's WebView
 * captures them for a plugin to read back through a keyless `localStorage.get()`.
 */
data class WebStorageSnapshot(val local: String, val session: String)

/** Run in the page; returns both storages as one JSON string, or null where the page may not read them. */
const val WEB_STORAGE_SCRIPT =
    "(function(){try{return JSON.stringify({local:localStorage,session:sessionStorage});}catch(e){return null;}})()"

/** Reads what [WEB_STORAGE_SCRIPT] returned through `evaluateJavascript`, which JSON-encodes the string again. */
fun parseWebStorage(result: String?): WebStorageSnapshot? {
    val payload = result?.let { runCatching { Json.decodeFromString<String?>(it) }.getOrNull() } ?: return null
    val storages = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
    val local = storages["local"] as? JsonObject ?: return null
    val session = storages["session"] as? JsonObject ?: JsonObject(emptyMap())
    return WebStorageSnapshot(local.toString(), session.toString())
}
