package exh.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The query parser turns a typed library search into structured components, and Text.asRegex turns
 * a component into a case-insensitive matcher that honours `*` / `?` wildcards. These tests pin the
 * grammar (namespaces, aliases, exclusion, exact, quotes) and the wildcard regex behavior, since the
 * in-memory library matcher (LibraryItem.matches) is built entirely on top of them.
 */
class SearchEngineTest {

    private val engine = SearchEngine()

    @Test
    fun `plain words parse to separate non-excluded text components`() {
        val q = engine.parseQuery("big breasts")

        q.size shouldBe 2
        q.all { it is Text && !it.excluded } shouldBe true
    }

    @Test
    fun `a namespaced term parses to a Namespace with its tag`() {
        val ns = engine.parseQuery("artist:toyya").single() as Namespace

        ns.namespace shouldBe "artist"
        ns.tag?.rawTextOnly() shouldBe "toyya"
    }

    @Test
    fun `short namespace aliases expand to their full names`() {
        (engine.parseQuery("a:toyya").single() as Namespace).namespace shouldBe "artist"
        (engine.parseQuery("f:glasses").single() as Namespace).namespace shouldBe "female"
        (engine.parseQuery("l:english").single() as Namespace).namespace shouldBe "language"
    }

    @Test
    fun `a leading minus marks the component excluded`() {
        val ns = engine.parseQuery("-male:yaoi").single() as Namespace

        ns.excluded shouldBe true
        ns.namespace shouldBe "male"
    }

    @Test
    fun `exclusion does not carry to the following component`() {
        val q = engine.parseQuery("-male yuri")

        q[0].excluded shouldBe true
        q[1].excluded shouldBe false
    }

    @Test
    fun `a multi-char wildcard matches a run of characters`() {
        val text = engine.parseQuery("big*").single() as Text

        text.asRegex().containsMatchIn("big breasts") shouldBe true
        text.asRegex().containsMatchIn("small") shouldBe false
    }

    @Test
    fun `a single-char wildcard matches exactly one character`() {
        val text = engine.parseQuery("b?g").single() as Text

        text.asRegex().containsMatchIn("big") shouldBe true
        text.asRegex().containsMatchIn("bug") shouldBe true
        text.asRegex().containsMatchIn("blarg") shouldBe false
    }

    @Test
    fun `regex metacharacters in plain text are matched literally`() {
        val text = engine.parseQuery("a.b").single() as Text

        text.asRegex().containsMatchIn("a.b") shouldBe true
        text.asRegex().containsMatchIn("axb") shouldBe false
    }

    @Test
    fun `an exact term anchors to the whole value instead of matching a substring`() {
        val text = engine.parseQuery("\$big").single() as Text

        text.exact shouldBe true
        text.asRegex(text.exact).containsMatchIn("big") shouldBe true
        text.asRegex(text.exact).containsMatchIn("big breasts") shouldBe false
    }

    @Test
    fun `a quoted phrase keeps its spaces as a single component`() {
        val text = engine.parseQuery("\"big breasts\"").single() as Text

        text.rawTextOnly() shouldBe "big breasts"
    }
}
