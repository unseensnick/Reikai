package reikai.domain.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The one title rule a refresh follows, for manga and novels alike. */
class RefreshedTitleTest {

    @Test
    fun `an entry outside the library takes the source's title`() {
        refreshedTitle("New", isFavorite = false, updateTitles = false) shouldBe "New"
    }

    @Test
    fun `a library entry keeps its title while titles are not updated to match the source`() {
        refreshedTitle("New", isFavorite = true, updateTitles = false) shouldBe null
    }

    @Test
    fun `a library entry takes the source's title while titles are updated to match the source`() {
        refreshedTitle("New", isFavorite = true, updateTitles = true) shouldBe "New"
    }

    @Test
    fun `an empty source title never replaces the stored one`() {
        refreshedTitle("", isFavorite = false, updateTitles = true) shouldBe null
    }

    @Test
    fun `a whitespace source title replaces the stored one, as Mihon's isNotEmpty check does`() {
        refreshedTitle(" ", isFavorite = false, updateTitles = true) shouldBe " "
    }
}
