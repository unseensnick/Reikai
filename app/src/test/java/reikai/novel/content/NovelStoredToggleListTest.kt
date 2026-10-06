package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

/** The find-and-replace rules and the code snippets are kept by this one list, and read by these codecs. */
class NovelStoredToggleListTest {

    private val preference = EmittingPreferenceStore().getString("list", "[]")
    private val list = NovelStoredToggleList(preference, NovelSnippets)

    private val a = NovelCodeSnippet(title = "a", code = "1", id = "a")
    private val b = NovelCodeSnippet(title = "b", code = "2", id = "b")
    private val c = NovelCodeSnippet(title = "c", code = "3", id = "c")

    private fun stored() = NovelSnippets.decode(preference.get())

    private fun seed(vararg items: NovelCodeSnippet) = preference.set(NovelSnippets.encode(items.toList()))

    @Test
    fun `saving an item with a new id appends it`() {
        seed(a, b)
        list.save(c)
        stored() shouldBe listOf(a, b, c)
    }

    @Test
    fun `saving an item with an existing id replaces it where it stands`() {
        seed(a, b, c)
        val edited = b.copy(title = "b2", code = "22")
        list.save(edited)
        stored() shouldBe listOf(a, edited, c)
    }

    @Test
    fun `deleting removes only that item`() {
        seed(a, b, c)
        list.delete(b)
        stored() shouldBe listOf(a, c)
    }

    @Test
    fun `toggling switches only that item`() {
        seed(a, b, c)
        list.toggle(b)
        stored() shouldBe listOf(a, b.copy(enabled = false), c)
    }

    @Test
    fun `an unreadable stored list is replaced by the next save`() {
        preference.set("not json")
        list.save(a)
        stored() shouldBe listOf(a)
    }

    @Test
    fun `a stored snippet with a field this build does not know still reads`() {
        val json = """[{"title":"a","code":"1","id":"a","addedLater":true}]"""
        NovelSnippets.decode(json) shouldBe listOf(a)
    }

    @Test
    fun `a stored rule with a field this build does not know still reads`() {
        val json = """[{"title":"r","pattern":"x","replacement":"y","id":"r","addedLater":1}]"""
        NovelRegexRules.decode(json) shouldBe
            listOf(NovelRegexReplacement(title = "r", pattern = "x", replacement = "y", id = "r"))
    }
}
