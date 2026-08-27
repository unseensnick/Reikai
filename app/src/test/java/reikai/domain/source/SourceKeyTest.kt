package reikai.domain.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [SourceKey.serialize] is an on-disk format: the last-used source is stored in it, so a change here
 * silently empties that section on everyone's next launch.
 */
class SourceKeyTest {

    @Test
    fun `a manga source survives the round trip`() {
        val key = SourceKey.Manga(4_793_064_155_310_670_919L)

        SourceKey.parse(key.serialize()) shouldBe key
    }

    @Test
    fun `a novel source survives the round trip`() {
        val key = SourceKey.Novel("novelfire")

        SourceKey.parse(key.serialize()) shouldBe key
    }

    @Test
    fun `a plugin slug keeps a separator of its own`() {
        // The id is everything after the first separator, so a slug is never truncated at one.
        val key = SourceKey.Novel("host:8080/plugin")

        SourceKey.parse(key.serialize()) shouldBe key
    }

    @Test
    fun `the empty value reads as no source`() {
        SourceKey.parse("") shouldBe null
    }

    @Test
    fun `a value from neither branch reads as no source`() {
        SourceKey.parse("novelfire") shouldBe null
    }

    @Test
    fun `a manga branch that does not carry a number reads as no source`() {
        SourceKey.parse("manga:novelfire") shouldBe null
    }
}
