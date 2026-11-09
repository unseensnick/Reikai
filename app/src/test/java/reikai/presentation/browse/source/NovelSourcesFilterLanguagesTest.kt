package reikai.presentation.browse.source

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginInfo
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource

/**
 * The novel filter screen offers a switch for every language a listing offers, installed or not, in the
 * order the manga half of the same screen uses.
 */
class NovelSourcesFilterLanguagesTest {

    private fun languages(sources: List<NovelSource>, listed: List<String> = emptyList()) =
        filterLanguages(sources, listed, disabledSources = emptySet(), disabledLanguages = emptySet())

    @Test
    fun `a language only a listing offers gets a switch with no sources under it`() {
        languages(listOf(EN_SOURCE), listed = listOf("ja")) shouldBe
            listOf("en" to listOf(EN_SOURCE), "ja" to emptyList())
    }

    @Test
    fun `a listing's language name joins the installed sources of its code`() {
        languages(listOf(EN_SOURCE), listed = listOf("English")) shouldBe listOf("en" to listOf(EN_SOURCE))
    }

    /** A plugin says "English" where an app source says "en"; a sort by display name merged the two. */
    @Test
    fun `a plugin and an app source of one language share one group`() {
        val plugin = LnPluginSource(mockk(), LnPluginInfo(id = "Plugin", name = "Plugin", lang = "English"))

        languages(listOf(plugin, EN_SOURCE)) shouldBe listOf("en" to listOf(plugin, EN_SOURCE))
    }

    /** Android names "in" and "id" alike, and a sorted map keyed by display name alone kept one group. */
    @Test
    fun `two codes Android names alike stay two groups`() {
        languages(listOf(source("a", "in"), source("b", "id"))).map { it.first } shouldBe listOf("id", "in")
    }

    @Test
    fun `a language switched off comes after the ones switched on`() {
        filterLanguages(
            listOf(source("a", "de"), EN_SOURCE),
            listed = emptyList(),
            disabledSources = emptySet(),
            disabledLanguages = setOf("de"),
        ).map { it.first } shouldBe listOf("en", "de")
    }

    @Test
    fun `a source switched off sinks below the others of its language`() {
        val off = source("Alpha", "en")

        filterLanguages(
            listOf(off, EN_SOURCE),
            listed = emptyList(),
            disabledSources = setOf(off.id),
            disabledLanguages = emptySet(),
        ).single().second shouldBe listOf(EN_SOURCE, off)
    }

    private companion object {
        val EN_SOURCE = source("Site", "en")

        fun source(name: String, lang: String) = mockk<NovelSource> {
            every { id } returns "plugin:$name"
            every { this@mockk.lang } returns lang
            every { this@mockk.name } returns name
        }
    }
}
