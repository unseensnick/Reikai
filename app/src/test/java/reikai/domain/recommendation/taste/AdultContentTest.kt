package reikai.domain.recommendation.taste

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The sexual-content rule the adult-content setting acts on: a tracker's own answer wins, and the
 * tag fallback only runs when it has none. Scope is sexual content, never violence.
 */
class AdultContentTest {

    private fun entry(
        adult: AdultContent = AdultContent.UNKNOWN,
        tags: List<String> = emptyList(),
    ) = TrackedEntry(
        trackerId = 1L,
        remoteId = 1L,
        title = "Title",
        score = -1.0,
        status = TrackStatus.READING,
        tags = tags,
        adult = adult,
    )

    @Test
    fun `a tracker saying adult is explicit`() {
        entry(adult = AdultContent.ADULT).isSexuallyExplicit() shouldBe true
    }

    @Test
    fun `a tracker saying clean beats an adult-looking tag`() {
        entry(adult = AdultContent.CLEAN, tags = listOf("hentai")).isSexuallyExplicit() shouldBe false
    }

    @Test
    fun `an unanswering tracker falls back to the tags`() {
        entry(tags = listOf("action", "erotica")).isSexuallyExplicit() shouldBe true
    }

    @Test
    fun `an unanswering tracker with clean tags is not explicit`() {
        entry(tags = listOf("action", "adventure")).isSexuallyExplicit() shouldBe false
    }

    @Test
    fun `an unanswering tracker with no tags at all is not explicit`() {
        entry().isSexuallyExplicit() shouldBe false
    }

    @Test
    fun `violence and gore are not sexual content`() {
        entry(tags = listOf("mature", "gore", "horror", "ero guro")).isSexuallyExplicit() shouldBe false
    }

    @Test
    fun `a tag matches on substring, so compound genre names still count`() {
        entry(tags = listOf("erotica (adult)")).isSexuallyExplicit() shouldBe true
    }

    /**
     * The near-misses, each taken from a real published vocabulary. Every one of these was either in
     * an earlier draft of the keyword list or is a substring of a term that was, and each would
     * silently shrink a profile.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            // MyAnimeList theme and AniList tag; "adult" alone matches both.
            "adult cast",
            "primarily adult cast",
            // MangaUpdates defines Mature and Adult as violence OR sex, so neither implies sexual.
            "mature",
            "adult",
            // AniList orientation tags; "sex" alone matches all three.
            "asexual",
            "bisexual",
            "heterosexual",
            // MyAnimeList theme; "sex" alone matches it.
            "magical sex shift",
            // AniList tag, and it carries isAdult false there.
            "nudity",
            // Orientation and romance, not explicitness. Shikimori censors these under local law.
            "yaoi",
            "yuri",
            "boys love",
            "girls love",
            // Demographics, not content.
            "josei",
            "seinen",
            // MangaUpdates: "fan based work inspired by official anime or manga".
            "doujinshi",
            // MyAnimeList theme, AniList non-adult tag, Kitsu category flagged not-NSFW.
            "harem",
            "reverse harem",
            "gender bender",
        ],
    )
    fun `near-miss genres are not treated as sexual content`(tag: String) {
        entry(tags = listOf(tag)).isSexuallyExplicit() shouldBe false
    }

    @ParameterizedTest
    @ValueSource(strings = ["hentai", "erotica", "smut", "lolicon", "хентай", "里番", "r18"])
    fun `published adult genres are treated as sexual content`(tag: String) {
        entry(tags = listOf(tag)).isSexuallyExplicit() shouldBe true
    }
}
