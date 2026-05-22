package yokai.novel.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry from an lnreader plugin registry's `plugins.min.json` (or `plugins.json`).
 *
 * Reference shape: `https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json`
 * (the user must paste a repo URL at runtime — we don't bundle this in the APK).
 *
 * Required fields ([id], [name], [version], [site], [lang], [url]) are present on every plugin in
 * the upstream registry. Optional fields are present only on a subset (icons, custom JS/CSS for
 * site-specific patching).
 */
@Serializable
data class LnRegistryEntry(
    /** Stable plugin identifier; matches `plugin.id` at runtime (e.g. `novelbin`). */
    val id: String,
    val name: String,
    val version: String,
    val site: String,
    val lang: String,
    /** Compiled `.js` URL for the plugin source. */
    val url: String,
    val iconUrl: String? = null,
    /** Optional URL to a custom JS file the host should `evaluate` after loading the plugin. */
    val customJS: String? = null,
    /** Optional URL to a custom CSS file (only meaningful inside the plugin's WebView contexts). */
    val customCSS: String? = null,
)

/**
 * Pure deserialization of an lnreader registry JSON blob. The network fetch is deliberately
 * separate (Slice C will add a service that pairs this with [NetworkHelper]); this keeps the
 * parser unit-testable without any Android / OkHttp dependencies.
 */
object LnRegistry {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * @return the parsed entries.
     * @throws kotlinx.serialization.SerializationException on malformed JSON, missing required
     * fields, or wrong types. Callers (the future install service) should catch and surface a
     * user-readable error.
     */
    fun parse(jsonText: String): List<LnRegistryEntry> =
        json.decodeFromString(jsonText)
}
