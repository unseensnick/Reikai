package reikai.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType

class ContentTypeColumnTest {

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a stored content type reads back as itself`(type: ContentType) {
        type.toDbValue().toContentType() shouldBe type
    }

    // 50.sqm and 51.sqm write these values into existing rows, so they can never move.
    @Test
    fun `manga is stored as 0`() {
        ContentType.MANGA.toDbValue() shouldBe 0L
    }

    @Test
    fun `novels are stored as 1`() {
        ContentType.NOVELS.toDbValue() shouldBe 1L
    }

    @Test
    fun `an unknown stored value fails loudly`() {
        shouldThrow<IllegalStateException> { 2L.toContentType() }
    }

    @Test
    fun `ALL has no stored value`() {
        shouldThrow<IllegalStateException> { ContentType.ALL.toDbValue() }
    }
}
