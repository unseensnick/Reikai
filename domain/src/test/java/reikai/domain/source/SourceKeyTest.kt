package reikai.domain.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A source's bare id, the form a search box and the migration picker match on. */
class SourceKeyTest {

    @Test
    fun `a manga source's bare id is its number`() {
        SourceKey.Manga(2499283573021220255L).rawId shouldBe "2499283573021220255"
    }

    @Test
    fun `a novel source's bare id is its slug as is`() {
        SourceKey.Novel("royalroad").rawId shouldBe "royalroad"
    }
}
