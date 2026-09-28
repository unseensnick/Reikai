package tachiyomi.domain.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A stored stub is compared against a freshly made one before it is written back
 * (AndroidSourceManager.registerStubSource), so the same source must compare equal or it is
 * rewritten on every reload (mihon 0044cc73e).
 */
class StubSourceTest {

    @Test
    fun `two stubs for the same source are equal`() {
        StubSource(id = 1L, lang = "en", name = "Source") shouldBe StubSource(id = 1L, lang = "en", name = "Source")
    }
}
