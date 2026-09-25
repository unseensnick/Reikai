package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.presentation.browse.compareBrowseLanguages

/**
 * An lnreader registry names a language in the language itself, so these pin that those names reach
 * the same code the manga sources use rather than each becoming a section of its own.
 */
class NovelSourceLanguageTest {

    @Test
    fun `a language named in its own language becomes its code`() {
        "Espa\u00F1ol".toLangCode() shouldBe "es"
    }

    @Test
    fun `Arabic matches despite the invisible mark the registry prefixes it with`() {
        "\u200E\u0627\u0644\u0639\u0631\u0628\u064A\u0629".toLangCode() shouldBe "ar"
    }

    @Test
    fun `multi-language plugins join the manga sources' Multi section`() {
        "Multi".toLangCode() shouldBe "all"
    }

    @Test
    fun `a language already given as a code is left alone`() {
        "es".toLangCode() shouldBe "es"
    }

    @Test
    fun `a language nobody has mapped is kept rather than dropped`() {
        "Klingon".toLangCode() shouldBe "Klingon"
    }

    @Test
    fun `an installed source reports the same code as the registry entry it came from`() {
        source("T\u00FCrk\u00E7e").langCode() shouldBe "T\u00FCrk\u00E7e".toLangCode()
    }

    /** A plugin says "English" where an app source says "en"; a sort by display name merged the two. */
    @Test
    fun `a plugin and an app source of one language share one group`() {
        val plugin = named("Plugin", "English")
        val app = named("App", "en")

        groupByLanguage(listOf(plugin, app), naturalOrder()) shouldBe listOf("en" to listOf(app, plugin))
    }

    /** Android names "in" and "id" alike, and a sorted map keyed by display name alone kept one group. */
    @Test
    fun `two codes Android names alike stay two groups`() {
        val legacy = named("Legacy", "in")
        val current = named("Current", "id")

        groupByLanguage(listOf(legacy, current), ::compareBrowseLanguages).map { it.first } shouldBe
            listOf("id", "in")
    }

    @Test
    fun `a language switched off by its code covers a plugin that names it`() {
        source("English").isInDisabledLanguage(setOf("en")) shouldBe true
    }

    @Test
    fun `a language switched off by a plugin's name for it covers an app source`() {
        source("en").isInDisabledLanguage(setOf("English")) shouldBe true
    }

    @Test
    fun `another language switched off leaves a source on`() {
        source("en").isInDisabledLanguage(setOf("es")) shouldBe false
    }

    @Test
    fun `a source switched off by its id is disabled`() {
        withId("s1", "en").isDisabled(disabledIds = setOf("s1"), disabledLangs = emptySet()) shouldBe true
    }

    @Test
    fun `a source whose language is switched off by name is disabled`() {
        withId("s1", "en").isDisabled(disabledIds = emptySet(), disabledLangs = setOf("English")) shouldBe true
    }

    @Test
    fun `a source with neither its id nor its language switched off is enabled`() {
        withId("s1", "en").isDisabled(disabledIds = setOf("s2"), disabledLangs = setOf("es")) shouldBe false
    }

    private fun source(lang: String) = mockk<NovelSource> { every { this@mockk.lang } returns lang }

    private fun withId(id: String, lang: String) = mockk<NovelSource> {
        every { this@mockk.id } returns id
        every { this@mockk.lang } returns lang
    }

    private fun named(name: String, lang: String) = mockk<NovelSource> {
        every { this@mockk.lang } returns lang
        every { this@mockk.name } returns name
    }
}
