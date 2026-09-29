package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RecentsListTopTest {

    @Test
    fun `a list at the top stays there when a new first row arrives`() {
        staysAtTop(0, 0, shownFirstKey = "yesterday", firstKey = "today") shouldBe true
    }

    @Test
    fun `a list scrolled down keeps its place`() {
        staysAtTop(4, 0, shownFirstKey = "yesterday", firstKey = "today") shouldBe false
    }

    @Test
    fun `a list scrolled part way into its first row keeps its place`() {
        staysAtTop(0, 12, shownFirstKey = "yesterday", firstKey = "today") shouldBe false
    }

    /** Nothing arrived above it, so a scroll the user has just started is left alone. */
    @Test
    fun `an unchanged first row leaves the list alone`() {
        staysAtTop(0, 0, shownFirstKey = "today", firstKey = "today") shouldBe false
    }
}
