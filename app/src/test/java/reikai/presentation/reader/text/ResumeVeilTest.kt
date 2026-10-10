package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** When both renderers veil a chapter whose saved percent is still to be landed. */
class ResumeVeilTest {

    @Test
    fun `a percent landing waits while the chapter's pictures load`() {
        ResumeVeil.waitsForPictures(picturesLoading = true, waitOver = false) shouldBe true
    }

    @Test
    fun `a percent landing stops waiting once the wait for pictures runs out`() {
        ResumeVeil.waitsForPictures(picturesLoading = true, waitOver = true) shouldBe false
    }

    @Test
    fun `a percent landing does not wait once the pictures have landed`() {
        ResumeVeil.waitsForPictures(picturesLoading = false, waitOver = false) shouldBe false
    }

    @Test
    fun `a page opening at a saved percent waits veiled until it is ready`() {
        ResumeVeil.pageWaits(initialFraction = 0.4f) shouldBe true
    }

    @Test
    fun `a page opening at its start is never veiled`() {
        ResumeVeil.pageWaits(initialFraction = 0f) shouldBe false
    }

    @Test
    fun `raising the veil twice draws it once`() {
        val drawn = mutableListOf<Boolean>()
        val veil = ResumeVeil(drawn::add)

        veil.set(true)
        veil.set(true)

        drawn shouldBe listOf(true)
    }

    @Test
    fun `a raised veil is lowered`() {
        val drawn = mutableListOf<Boolean>()
        val veil = ResumeVeil(drawn::add)

        veil.set(true)
        veil.set(false)

        drawn shouldBe listOf(true, false)
    }

    @Test
    fun `lowering a veil never raised draws nothing`() {
        val drawn = mutableListOf<Boolean>()
        val veil = ResumeVeil(drawn::add)

        veil.set(false)

        drawn shouldBe emptyList()
    }

    @Test
    fun `touches are held while the veil is up`() {
        val veil = ResumeVeil {}

        veil.set(true)

        veil.isUp shouldBe true
    }
}
