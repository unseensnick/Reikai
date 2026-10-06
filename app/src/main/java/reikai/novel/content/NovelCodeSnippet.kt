package reikai.novel.content

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One user-written piece of CSS or JavaScript the WebView reader adds to a chapter page. Persisted as
 * JSON in the novel reader's snippet preferences, so the property names are the stored schema.
 * [runOnAppend] is JavaScript only: a snippet that marks it runs again for each chapter added to the
 * page, where the rest run once when the page loads.
 */
@Serializable
data class NovelCodeSnippet(
    override val title: String,
    val code: String,
    override val enabled: Boolean = true,
    val runOnAppend: Boolean = false,
    override val id: String = UUID.randomUUID().toString(),
) : NovelStoredItem<NovelCodeSnippet> {
    override fun toggled() = copy(enabled = !enabled)
}

enum class NovelSnippetKind { CSS, JS }

/** The codec for both snippet lists, each stored in its own preference. */
object NovelSnippets : NovelStoredListCodec<NovelCodeSnippet>(NovelCodeSnippet.serializer(), "code snippets")
