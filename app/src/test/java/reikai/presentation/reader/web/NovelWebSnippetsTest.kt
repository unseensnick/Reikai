package reikai.presentation.reader.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippets

class NovelWebSnippetsTest {

    private val on = NovelCodeSnippet(title = "On", code = "p { color: red; }", id = "a")
    private val off = NovelCodeSnippet(title = "Off", code = "p { color: blue; }", enabled = false, id = "b")

    @Test
    fun `only switched-on css reaches the page`() {
        NovelWebSnippets.from(NovelSnippets.encode(listOf(on, off)), "[]").css shouldBe "p { color: red; }"
    }

    @Test
    fun `only switched-on javascript reaches the page`() {
        NovelWebSnippets.from("[]", NovelSnippets.encode(listOf(on, off))).js shouldBe listOf(on)
    }

    @Test
    fun `a list that will not read adds nothing`() {
        NovelWebSnippets.from("not json", "not json") shouldBe NovelWebSnippets()
    }

    @Test
    fun `javascript already run as it is does not run again`() {
        NovelWebSnippets(js = listOf(on)).jsChangedSince(mapOf("a" to on.code)) shouldBe emptyList()
    }

    @Test
    fun `javascript edited since it ran runs again`() {
        NovelWebSnippets(js = listOf(on)).jsChangedSince(mapOf("a" to "old")) shouldBe listOf(on)
    }

    /** The runner is evaluated as script, so a snippet's own quotes and tags must stay inside its string. */
    @Test
    fun `a snippet cannot end the string it travels in`() {
        val hostile = NovelCodeSnippet(title = "x", code = "\"); alert(1); (\"</script>", id = "c")

        NovelWebSnippets.runner(listOf(hostile))!! shouldContain "[\"\\\"); alert(1)"
    }

    /** The stylesheet is written into the reader's own script element when the document is built. */
    @Test
    fun `css cannot end the script it is written into`() {
        NovelWebSnippets.jsLiteral("a</script><script>alert(1)") shouldNotContain "</script>"
    }

    @Test
    fun `nothing to run builds no script`() {
        NovelWebSnippets.runner(emptyList()) shouldBe null
    }
}
