package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The compile kernel both the reading pipeline and the rule editor's preview run through, so a rule
 * cannot behave one way when tested and another way when read.
 */
class NovelRegexReplacementsTest {

    private fun rule(
        pattern: String,
        replacement: String = "",
        isRegex: Boolean = true,
        matchWholeWord: Boolean = false,
        caseSensitive: Boolean = false,
    ) = NovelRegexReplacement(
        title = "rule",
        pattern = pattern,
        replacement = replacement,
        isRegex = isRegex,
        matchWholeWord = matchWholeWord,
        caseSensitive = caseSensitive,
    )

    private fun run(rule: NovelRegexReplacement, input: String) =
        NovelRegexReplacements.compile(rule).applyTo(input)

    @Test
    fun `a regex rule rewrites what it matches`() {
        run(rule("h[0-9]", "p"), "<h1>Title</h1>") shouldBe "<p>Title</p>"
    }

    /** The default, because a reader writing "chapter" should not have to guess the source's casing. */
    @Test
    fun `matching ignores case unless the rule asks for it`() {
        run(rule("chapter", "Part"), "CHAPTER one") shouldBe "Part one"
    }

    @Test
    fun `a case-sensitive rule leaves the other casing alone`() {
        run(rule("chapter", "Part", caseSensitive = true), "CHAPTER one") shouldBe "CHAPTER one"
    }

    /** Without escaping, a find-and-replace of "a.b" would also match "axb". */
    @Test
    fun `a text rule treats its pattern as characters rather than a pattern`() {
        run(rule("a.b", "X", isRegex = false), "axb a.b") shouldBe "axb X"
    }

    @Test
    fun `whole word matching skips a longer word containing it`() {
        run(rule("cat", "dog", isRegex = false, matchWholeWord = true), "cat catalogue") shouldBe "dog catalogue"
    }

    /**
     * The half that separates the two modes. A regex rule may reference its groups, and a text rule
     * may not, so a replacement containing a dollar sign is written out rather than failing the rule.
     */
    @Test
    fun `a regex replacement can reference a captured group`() {
        run(rule("""Chapter (\d+)""", "Ch. $1"), "Chapter 12") shouldBe "Ch. 12"
    }

    @Test
    fun `a text replacement writes a dollar sign out as typed`() {
        run(rule("price", "$5", isRegex = false), "price") shouldBe "$5"
    }

    /** What the editor reports as an error rather than saving a rule that could never run. */
    @Test
    fun `an unparseable pattern throws rather than compiling to nothing`() {
        assertThrows<Exception> { NovelRegexReplacements.compile(rule("[unclosed")) }
    }

    /**
     * The runtime reads a replacement only once something matches, so without this check a rule
     * tested on an empty sample saved cleanly and was then skipped on the first chapter it touched.
     */
    @ParameterizedTest
    @ValueSource(strings = ["$5", "trailing \\", "\${missing}"])
    fun `a regex replacement the runtime cannot read throws at compile`(replacement: String) {
        assertThrows<NovelRegexReplacements.InvalidReplacementException> {
            NovelRegexReplacements.compile(rule("(?<w>a)", replacement))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["$1", "\${w}", "\\$1", "plain"])
    fun `a regex replacement naming a group the pattern has compiles`(replacement: String) {
        run(rule("(?<w>a)", replacement), "b") shouldBe "b"
    }

    /** The generic catch around each rule would otherwise swallow a cancelled load as a failed rule. */
    @Test
    fun `a cancelled load throws out of the rules rather than being skipped as a failed rule`() {
        val rules = """[{"title":"t","pattern":"b","replacement":"","isRegex":true}]"""

        assertThrows<CancellationException> {
            NovelRegexReplacements.apply("a".repeat(100_000), rules, isActive = { false })
        }
    }

    /** A nested quantifier can run for hours, so a load that gave up must be able to leave the match. */
    @Test
    fun `a load cancelled while a rule is matching leaves the match`() {
        val rules = """[{"title":"t","pattern":"(.*c){12}d","replacement":"","isRegex":true}]"""
        val started = TimeSource.Monotonic.markNow()

        assertThrows<CancellationException> {
            NovelRegexReplacements.apply("c".repeat(40), rules, isActive = { started.elapsedNow() < 200.milliseconds })
        }
    }

    @Test
    fun `a disabled rule is not applied`() {
        val rules = """[{"title":"t","pattern":"cat","replacement":"dog","isRegex":false,"enabled":false}]"""

        NovelRegexReplacements.apply("cat", rules) shouldBe "cat"
    }

    @Test
    fun `a rule that will not compile is skipped and the rest still run`() {
        val rules = """[{"title":"t","pattern":"[unclosed","replacement":"x"},""" +
            """{"title":"u","pattern":"cat","replacement":"dog","isRegex":false}]"""

        NovelRegexReplacements.apply("cat", rules) shouldBe "dog"
    }

    @Test
    fun `each rule runs on what the one before it produced`() {
        val rules = """[{"title":"t","pattern":"a","replacement":"b","isRegex":false},""" +
            """{"title":"u","pattern":"b","replacement":"c","isRegex":false}]"""

        NovelRegexReplacements.apply("a", rules) shouldBe "c"
    }

    /** Its thread may still be matching, so running the pattern again would start another. */
    @Test
    fun `a pattern that ran past its budget is not run again`() {
        val run = NovelRegexReplacements.BudgetedRun(50.milliseconds)
        val slow = NovelRegexReplacements.compile(rule("(.*e){12}f"))
        run.applyOrNull(slow, "e".repeat(40), isActive = { true })

        run.applyOrNull(slow, "", isActive = { true }) shouldBe null
    }
}
