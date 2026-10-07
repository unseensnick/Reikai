package reikai.domain.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceVisualNameTest {

    @Test
    fun `a source with a language shows its code beside its name`() {
        sourceVisualName("Site", "en") shouldBe "Site (EN)"
    }

    @Test
    fun `a source with no language shows its name alone`() {
        sourceVisualName("Site", "") shouldBe "Site"
    }
}
