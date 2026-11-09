package reikai.novel.content

import androidx.compose.runtime.Immutable
import kotlin.coroutines.cancellation.CancellationException

/** A short excerpt around one match. [matchStart] and [matchEnd] index into [text]; [position] is 0 to 1. */
@Immutable
data class ChapterSearchSnippet(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
    val position: Float,
)

@Immutable
data class ChapterTextMatches(
    val count: Int,
    val snippets: List<ChapterSearchSnippet>,
)

/** A linear find over a chapter's visible text, for the search across a novel's downloaded chapters. */
object ChapterTextSearch {

    const val SNIPPET_CONTEXT = 60
    const val MAX_SNIPPETS_PER_CHAPTER = 3
    private const val MAX_SNIPPET_MATCH_LENGTH = 200

    /**
     * Counts every non-empty match of [regex] in [text] and keeps excerpts for the first few. Once
     * [isActive] turns false the scan throws [CancellationException], even inside a slow match.
     */
    fun findMatches(
        text: String,
        regex: Regex,
        isActive: () -> Boolean = { true },
    ): ChapterTextMatches {
        if (text.isEmpty()) return ChapterTextMatches(0, emptyList())
        var count = 0
        val snippets = mutableListOf<ChapterSearchSnippet>()
        for (match in regex.findAll(CancellableCharSequence(text, isActive))) {
            if (match.range.isEmpty()) continue
            count++
            if (snippets.size < MAX_SNIPPETS_PER_CHAPTER) {
                snippets += snippetFor(text, match.range.first, match.range.last + 1)
            }
        }
        return ChapterTextMatches(count, snippets)
    }

    private fun snippetFor(text: String, start: Int, end: Int): ChapterSearchSnippet {
        val shownEnd = minOf(end, start + MAX_SNIPPET_MATCH_LENGTH)
        val from = (start - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (shownEnd + SNIPPET_CONTEXT).coerceAtMost(text.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return ChapterSearchSnippet(
            text = prefix + text.substring(from, to) + suffix,
            matchStart = prefix.length + start - from,
            matchEnd = prefix.length + shownEnd - from,
            position = start.toFloat() / text.length,
        )
    }
}
