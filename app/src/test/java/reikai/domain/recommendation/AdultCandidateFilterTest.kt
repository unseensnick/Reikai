package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.taste.AdultContent
import reikai.domain.recommendation.taste.AdultTagOverrides

class AdultCandidateFilterTest {

    private fun candidate(
        adult: AdultContent = AdultContent.UNKNOWN,
        genre: String? = null,
    ) = RelatedMangaCandidate(
        sourceId = 1L,
        trackerName = null,
        manga = SManga.create().apply {
            url = "/manga/1"
            title = "Title"
            this.genre = genre
        },
        origin = RecommendationOrigin.SourceNative("Source"),
        adult = adult,
    )

    private fun filter(
        enabled: Boolean = true,
        overrides: AdultTagOverrides = AdultTagOverrides.NONE,
    ) = AdultCandidateFilter(enabled, overrides)

    @Test
    fun `a provider saying adult is hidden`() {
        filter().shouldHide(candidate(adult = AdultContent.ADULT)) shouldBe true
    }

    @Test
    fun `a provider saying clean is kept even with an adult-looking genre`() {
        filter().shouldHide(candidate(adult = AdultContent.CLEAN, genre = "Hentai")) shouldBe false
    }

    @Test
    fun `a candidate with no answer falls back to its genres`() {
        filter().shouldHide(candidate(genre = "Action, Hentai")) shouldBe true
    }

    @Test
    fun `a candidate carrying no genres at all is kept`() {
        filter().shouldHide(candidate()) shouldBe false
    }

    @Test
    fun `nothing is hidden once the user has opted into adult content`() {
        filter(enabled = false).shouldHide(candidate(adult = AdultContent.ADULT)) shouldBe false
    }

    @Test
    fun `the user's own tag picks reach candidates too`() {
        filter(overrides = AdultTagOverrides(alwaysAdult = setOf("ecchi")))
            .shouldHide(candidate(genre = "Action, Ecchi")) shouldBe true
    }

    @Test
    fun `an allowed tag keeps a candidate the built-in list would have hidden`() {
        filter(overrides = AdultTagOverrides(neverAdult = setOf("erotica")))
            .shouldHide(candidate(genre = "Erotica")) shouldBe false
    }

    @Test
    fun `a denied tag outranks a provider saying clean`() {
        filter(overrides = AdultTagOverrides(alwaysAdult = setOf("ecchi")))
            .shouldHide(candidate(adult = AdultContent.CLEAN, genre = "Action, Ecchi")) shouldBe true
    }

    @Test
    fun `an allowed tag clears a provider saying adult`() {
        filter(overrides = AdultTagOverrides(neverAdult = setOf("yuri")))
            .shouldHide(candidate(adult = AdultContent.ADULT, genre = "Yuri, Romance")) shouldBe false
    }

    @Test
    fun `an allowed tag leaves the remaining genres to decide`() {
        filter(overrides = AdultTagOverrides(neverAdult = setOf("yuri")))
            .shouldHide(candidate(adult = AdultContent.ADULT, genre = "Yuri, Hentai")) shouldBe true
    }
}
