package tachiyomi.domain.manga.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class WithCustomInfoTest {

    private val source = Manga.create().copy(
        id = 1L,
        title = "Source Title",
        author = "Source Author",
        artist = "Source Artist",
        description = "Source description",
        genre = listOf("Action", "Drama"),
        status = 1L,
        thumbnailUrl = "https://example.com/source.jpg",
    )

    @Test
    fun `a null overlay passes the source through unchanged`() {
        source.withCustomInfo(null) shouldBe source
    }

    @Test
    fun `an all-null overlay passes every field through`() {
        source.withCustomInfo(CustomMangaInfo(mangaId = 1L)) shouldBe source
    }

    @Test
    fun `a set field wins over the source value`() {
        source.withCustomInfo(CustomMangaInfo(mangaId = 1L, title = "My Title")).title shouldBe "My Title"
    }

    @Test
    fun `an unset field keeps the source value even when another is overridden`() {
        val custom = CustomMangaInfo(mangaId = 1L, title = "My Title")
        source.withCustomInfo(custom).author shouldBe "Source Author"
    }

    @Test
    fun `every field can be overridden at once`() {
        val custom = CustomMangaInfo(
            mangaId = 1L,
            title = "T",
            author = "Au",
            artist = "Ar",
            description = "D",
            genre = listOf("G"),
            status = 2L,
            thumbnailUrl = "u",
        )
        source.withCustomInfo(custom) shouldBe source.copy(
            title = "T",
            author = "Au",
            artist = "Ar",
            description = "D",
            genre = listOf("G"),
            status = 2L,
            thumbnailUrl = "u",
        )
    }
}
