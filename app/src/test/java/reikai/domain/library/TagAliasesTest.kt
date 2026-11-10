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
    fun `an alias onto an alias points at the end of the chain`() {
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
    fun `a tag the filter shows moves to the tag it now reads as`() {
        val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        preferences.filterTagsInclude.set(setOf("sci fi"))
        preferences.addTagAlias("Sci-fi", "Science Fiction")
        preferences.filterTagsInclude.get() shouldBe setOf("science fiction")
    }
}
