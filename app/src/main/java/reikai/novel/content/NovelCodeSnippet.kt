package reikai.novel.content

import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID

/**
 * One user-written piece of CSS or JavaScript the WebView reader adds to a chapter page. Persisted as
 * JSON in the novel reader's snippet preferences, so the property names are the stored schema.
 * [runOnAppend] is JavaScript only: a snippet that marks it runs again for each chapter added to the
 * page, where the rest run once when the page loads.
 */
@Serializable
data class NovelCodeSnippet(
    val title: String,
    val code: String,
    val enabled: Boolean = true,
    val runOnAppend: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
)

enum class NovelSnippetKind { CSS, JS }

object NovelSnippets {
    /** The stored snippet list, or empty for one that will not read, as the find-and-replace rules do. */
    fun decode(json: String): List<NovelCodeSnippet> = try {
        novelRegexRuleJson.decodeFromString(json)
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Failed to parse code snippets" }
        emptyList()
    }

    fun encode(snippets: List<NovelCodeSnippet>): String = novelRegexRuleJson.encodeToString(snippets)
}
