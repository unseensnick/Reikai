package reikai.domain.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A lazy-loading placeholder never replaces a cover already known, for either content type. */
class PlaceholderCoverTest {

    @Test
    fun `the Madara lazy-load image is a placeholder`() {
        isPlaceholderCover("https://lightnovelheaven.com/wp-content/themes/madara/images/dflazy.jpg") shouldBe true
    }

    /** A dotted query would otherwise read as the extension. */
    @Test
    fun `a placeholder with a versioned query is a placeholder`() {
        isPlaceholderCover("https://site.example/wp-content/themes/madara/images/dflazy.jpg?ver=6.4.2") shouldBe true
    }

    @Test
    fun `an inline svg is a placeholder`() {
        isPlaceholderCover("data:image/svg+xml,%3Csvg%3E%3C/svg%3E") shouldBe true
    }

    @Test
    fun `a cover whose name only mentions lazy is a cover`() {
        isPlaceholderCover("https://site.example/covers/the-lazy-prince.jpg") shouldBe false
    }

    @Test
    fun `a placeholder keeps the cover already stored`() {
        keptCover(COVER, PLACEHOLDER) shouldBe COVER
    }

    @Test
    fun `a real cover replaces the one stored`() {
        keptCover(COVER, "https://site.example/new.jpg") shouldBe "https://site.example/new.jpg"
    }

    @Test
    fun `a blank cover keeps the one stored`() {
        keptCover(COVER, " ") shouldBe COVER
    }

    @Test
    fun `a stored placeholder takes the listing cover`() {
        healedCover(PLACEHOLDER, COVER) shouldBe COVER
    }

    @Test
    fun `a stored cover is kept over the listing's`() {
        healedCover(COVER, "https://site.example/new.jpg") shouldBe null
    }

    @Test
    fun `a listing placeholder heals nothing`() {
        healedCover(null, PLACEHOLDER) shouldBe null
    }

    private companion object {
        const val COVER = "https://site.example/cover.jpg"
        const val PLACEHOLDER = "https://site.example/wp-content/themes/madara/images/dflazy.jpg"
    }
}
