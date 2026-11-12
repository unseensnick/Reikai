package exh.md

import exh.md.service.MangaDexService
import exh.md.utils.MdLang
import exh.md.utils.MdUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MangaDexAllLanguagesTest {

    private val altTitles = listOf(
        mapOf("en" to "Hello"),
        mapOf("ja-ro" to "Konnichiwa"),
        mapOf("fr" to "Bonjour"),
    )

    @Test
    @DisplayName("the all-languages extension maps to its own code, so its preference keys end in _all")
    fun allMapsToItsOwnCode() {
        MdLang.fromExt("all")?.lang shouldBe "all"
    }

    @Test
    @DisplayName("the all-languages aggregate sends no translated-language filter")
    fun allAggregateHasNoLanguageFilter() {
        MangaDexService.aggregateUrl("abc", "all").queryParameterValues("translatedLanguage[]") shouldBe emptyList()
    }

    @Test
    @DisplayName("a per-language aggregate still filters to its language")
    fun perLanguageAggregateFilters() {
        MangaDexService.aggregateUrl("abc", "en").queryParameterValues("translatedLanguage[]") shouldBe listOf("en")
    }

    @Test
    @DisplayName("the all-languages source keeps every alternative title")
    fun allKeepsEveryAltTitle() {
        MdUtil.altTitleEntries(altTitles, "all", "ja") shouldBe
            listOf("en" to "Hello", "ja-ro" to "Konnichiwa", "fr" to "Bonjour")
    }

    @Test
    @DisplayName("a per-language source keeps its own and the romanized original alternative titles")
    fun perLanguageKeepsOwnAndRomanized() {
        MdUtil.altTitleEntries(altTitles, "en", "ja") shouldBe listOf("en" to "Hello", "ja-ro" to "Konnichiwa")
    }

    @Test
    @DisplayName("the all-languages description labels each alternative title with its language")
    fun allLabelsDescriptionTitles() {
        MdUtil.descriptionAltTitles(listOf("en" to "Hello", "fr" to "Bonjour"), "all") shouldBe
            listOf("[en] Hello", "[fr] Bonjour")
    }

    @Test
    @DisplayName("a per-language description lists alternative titles unlabelled")
    fun perLanguageDescriptionUnlabelled() {
        MdUtil.descriptionAltTitles(listOf("en" to "Hello"), "en") shouldBe listOf("Hello")
    }
}
