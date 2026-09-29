package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The one rule Fill from tracker applies to the tag chips, for manga and novels alike. */
class MergeTrackerGenresTest {

    @Test
    fun `existing tags stay and new genres are appended`() {
        mergeTrackerGenres(listOf("ROMANCE", "FANTASY"), listOf("Mystery", "Supernatural")) shouldBe
            listOf("ROMANCE", "FANTASY", "Mystery", "Supernatural")
    }

    @Test
    fun `a genre differing from a tag only in case is not added again`() {
        mergeTrackerGenres(listOf("FANTASY"), listOf("Fantasy", "Mystery")) shouldBe listOf("FANTASY", "Mystery")
    }

    @Test
    fun `a genre differing from a tag only in surrounding spaces is not added again`() {
        mergeTrackerGenres(listOf("Fantasy"), listOf(" fantasy ")) shouldBe listOf("Fantasy")
    }

    @Test
    fun `a tracker naming one genre twice adds it once`() {
        mergeTrackerGenres(emptyList(), listOf("Action", "action")) shouldBe listOf("Action")
    }

    @Test
    fun `a blank genre is dropped`() {
        mergeTrackerGenres(listOf("Drama"), listOf(" ")) shouldBe listOf("Drama")
    }
}
