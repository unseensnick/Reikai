package reikai.presentation.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

class LibraryEngineTest {

    private fun provider(type: ContentType): LibraryProvider {
        val provider = mockk<LibraryProvider>(relaxed = true)
        every { provider.contentType } returns type
        return provider
    }

    private val manga = provider(ContentType.MANGA)
    private val novel = provider(ContentType.NOVELS)
    private val engine = LibraryEngine(listOf(manga, novel))

    @Test
    fun `a single content type drives its own provider`() {
        engine.behaviorFor(ContentType.MANGA) shouldBe manga
        engine.behaviorFor(ContentType.NOVELS) shouldBe novel
    }

    @Test
    fun `ALL fans out to every provider`() {
        engine.providersFor(ContentType.ALL) shouldContainExactly listOf(manga, novel)
    }

    @Test
    fun `a mixed view fails loudly instead of rendering one content type`() {
        shouldThrow<IllegalStateException> { engine.behaviorFor(ContentType.ALL) }
    }
}
