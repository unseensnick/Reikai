package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TagAliasesTest {

    private val aliases = TagAliases(listOf(TagAlias("Sci-fi", "Science Fiction")))

    @Test
    fun `an alias resolves whatever its spelling`() {
        aliases.resolve("SCI_FI") shouldBe "Science Fiction"
    }

    @Test
    fun `a series carrying both an alias and its target keeps the tag once`() {
        aliases.resolveAll(listOf("Sci-fi", "science fiction", "Drama")) shouldBe listOf("Science Fiction", "Drama")
    }

    @Test
    fun `a stored key resolves to its target's key`() {
        aliases.resolveKey("sci fi") shouldBe "science fiction"
    }

    @Test
    fun `an alias onto a tag that becomes an alias moves to the new target`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.addTagAlias("SF", "Sci-fi")
        preferences.addTagAlias("Sci-fi", "Science Fiction")
        preferences.currentTagAliases().resolve("SF") shouldBe "Science Fiction"
    }

    @Test
    fun `a tag is never made an alias of itself`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.addTagAlias("Sci-fi", "sci fi")
        preferences.tagAliases.get() shouldBe emptyList()
    }

    @Test
    fun `removing an alias gives the filter back the tag that was picked`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.filterTagsInclude.set(setOf("harem"))
        preferences.addTagAlias("Harem", "Romance")
        preferences.removeTagAlias("Harem")
        preferences.filterTagsInclude.get() shouldBe setOf("harem")
    }

    @Test
    fun `while an alias exists the filter reads as its target`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.filterTagsInclude.set(setOf("harem"))
        preferences.addTagAlias("Harem", "Romance")
        preferences.currentTagFilter().through(preferences.currentTagAliases()).include shouldBe setOf("romance")
    }

    @Test
    fun `an alias onto an existing alias points at the end of the chain`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.addTagAlias("Sci-fi", "Science Fiction")
        preferences.addTagAlias("SF", "Sci-fi")
        preferences.currentTagAliases().resolve("SF") shouldBe "Science Fiction"
    }

    @Test
    fun `a tag edited while an alias exists leaves the other tags as they were picked`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.filterTagsInclude.set(setOf("harem"))
        preferences.addTagAlias("Harem", "Romance")
        preferences.hideTag("gore")
        preferences.removeTagAlias("Harem")
        preferences.currentTagFilter().let { it.include to it.exclude } shouldBe (setOf("harem") to setOf("gore"))
    }
}
