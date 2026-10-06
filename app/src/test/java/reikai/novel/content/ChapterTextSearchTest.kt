package reikai.novel.content

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.cancellation.CancellationException

class ChapterTextSearchTest {

    private fun count(
        text: String,
        query: String,
        isRegex: Boolean = false,
        wholeWord: Boolean = false,
        caseSensitive: Boolean = false,
    ) = ChapterTextSearch.findMatches(
        text,
        NovelRegexReplacements.findRegex(query, isRegex, wholeWord, caseSensitive),
    ).count

    @Test
    fun `a plain query matches literally`() {
        count("a.b axb", "a.b") shouldBe 1
    }

    @Test
    fun `a plain query ignores case by default`() {
        count("Rogue said: rogue? (a.b)", "rogue") shouldBe 2
    }

    @Test
    fun `match case keeps case`() {
        count("Rogue", "rogue", caseSensitive = true) shouldBe 0
    }

    @Test
    fun `ignoring case handles non-ascii letters`() {
        count("ÉLAN", "élan") shouldBe 1
    }

    @Test
    fun `a pattern matches as a regex`() {
        count("Player 1, Player 22, Player 3", "Player \\d+", isRegex = true) shouldBe 3
    }

    @Test
    fun `whole words skips matches inside words`() {
        count("Play the player, play", "play", wholeWord = true) shouldBe 2
    }

    @Test
    fun `whole words is the pattern's own business`() {
        // As in the rule editor, which hides the switch once the find is a pattern.
        count("cat|catalog", "cat|dog", isRegex = true, wholeWord = true) shouldBe 2
    }

    @Test
    fun `an invalid pattern throws`() {
        shouldThrow<PatternSyntaxException> {
            NovelRegexReplacements.findRegex("(unclosed", isRegex = true, wholeWord = false, caseSensitive = false)
        }
    }

    @Test
    fun `empty matches are not counted`() {
        count("aab", "b*", isRegex = true) shouldBe 1
    }

    @Test
    fun `a snippet carries its match bounds`() {
        val text = "x".repeat(100) + "needle" + "y".repeat(100)
        val snippet = ChapterTextSearch.findMatches(text, Regex("needle")).snippets.single()

        snippet.text.substring(snippet.matchStart, snippet.matchEnd) shouldBe "needle"
    }

    @Test
    fun `a snippet is cut with ellipses at both ends`() {
        val text = "x".repeat(100) + "needle" + "y".repeat(100)
        val snippet = ChapterTextSearch.findMatches(text, Regex("needle")).snippets.single()

        (snippet.text.first() to snippet.text.last()) shouldBe ('…' to '…')
    }

    @Test
    fun `every match is counted past the snippet cap`() {
        ChapterTextSearch.findMatches("a ".repeat(10), Regex("a"), maxSnippets = 2).count shouldBe 10
    }

    @Test
    fun `snippets stop at the cap`() {
        ChapterTextSearch.findMatches("a ".repeat(10), Regex("a"), maxSnippets = 2).snippets.size shouldBe 2
    }

    @Test
    fun `a cancelled scan aborts`() {
        shouldThrow<CancellationException> {
            ChapterTextSearch.findMatches("a".repeat(100_000), Regex("b"), isActive = { false })
        }
    }
}
