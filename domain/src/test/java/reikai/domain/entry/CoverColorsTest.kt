package reikai.domain.entry

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Browsing is capped and a library entry is never what the cap evicts, in this process or after a restart,
 * since an evicted library entry loses its first-frame tint and re-reads its cover on every memory miss.
 */
class CoverColorsTest {

    @AfterEach
    fun clear() {
        CoverColors.clear()
    }

    @Test
    fun `a library entry's colour survives browsing past the cap`() {
        CoverColors.put(LIBRARY_KEY, RED, inLibrary = true)

        browsePastTheCap()

        CoverColors[LIBRARY_KEY] shouldBe RED
    }

    @Test
    fun `browse colours keep their recency across a restart`() {
        // Preferences hand a string set back in no particular order, so the stored order is reversed here.
        CoverColors.put(OLDEST, RED, inLibrary = false)
        (1L..CoverColors.CAP - 1L).forEach { CoverColors.put(BROWSE_BASE + it, BLUE, inLibrary = false) }
        CoverColors[OLDEST]
        val stored = CoverColors.encode()
        CoverColors.clear()
        CoverColors.restore(stored.reversed().toSet())

        (1L..CoverColors.CAP - 1L).forEach { CoverColors.put(-it, BLUE, inLibrary = false) }

        CoverColors[OLDEST] shouldBe RED
    }

    @Test
    fun `a library entry's colour survives a restart and browsing past the cap`() {
        CoverColors.put(LIBRARY_KEY, RED, inLibrary = true)
        val stored = CoverColors.encode()
        CoverColors.clear()
        CoverColors.restore(stored)

        browsePastTheCap()

        CoverColors[LIBRARY_KEY] shouldBe RED
    }

    @Test
    fun `an entry taken out of the library becomes evictable`() {
        CoverColors.put(LIBRARY_KEY, RED, inLibrary = true)
        CoverColors.put(LIBRARY_KEY, RED, inLibrary = false)

        browsePastTheCap()

        CoverColors[LIBRARY_KEY] shouldBe null
    }

    private fun browsePastTheCap() {
        (1L..CoverColors.CAP + 1L).forEach { CoverColors.put(BROWSE_BASE + it, BLUE, inLibrary = false) }
    }

    private companion object {
        const val LIBRARY_KEY = 1L
        const val OLDEST = 2L
        const val BROWSE_BASE = 10_000L
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
