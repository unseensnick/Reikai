package exh.md

import exh.md.utils.MdUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MdUtilTest {

    private val enabled = listOf(11L, 22L, 33L)

    private fun pick(preferred: String) = MdUtil.preferredOrFirst(enabled, preferred) { it }

    @Test
    @DisplayName("the preferred MangaDex source is picked when it is enabled")
    fun preferredIsPicked() {
        pick("22") shouldBe 22L
    }

    @Test
    @DisplayName("no preference picks the first enabled MangaDex source")
    fun unsetPicksFirst() {
        pick("0") shouldBe 11L
    }

    @Test
    @DisplayName("a preferred source that is not enabled falls back to the first")
    fun disabledPreferenceFallsBack() {
        pick("44") shouldBe 11L
    }

    @Test
    @DisplayName("alternative titles are listed under their heading after the description")
    fun altTitlesAreAppended() {
        MdUtil.addAltTitleToDesc("About.", listOf("One", "Two"), "Alternative titles") shouldBe
            "About.\n\nAlternative titles:\n• One\n• Two"
    }

    @Test
    @DisplayName("no alternative titles leaves the description alone")
    fun noAltTitlesIsUnchanged() {
        MdUtil.addAltTitleToDesc("About.", emptyList(), "Alternative titles") shouldBe "About."
    }

    @Test
    @DisplayName("the final volume and chapter are listed under their heading")
    fun finalChapterIsAppended() {
        MdUtil.addFinalChapterToDesc("", "3", "25", "Final chapter") shouldBe "Final chapter:\nVol.3 Ch.25"
    }
}
