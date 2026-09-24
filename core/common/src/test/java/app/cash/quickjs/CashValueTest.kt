package app.cash.quickjs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Extensions written against Cash's engine read a JS array as Object[]: one builds its pages only when
 * the result `is Array<*>`, another casts with `as Array<*>`. dokar hands back a java.util.List, so the
 * shim converts, nested arrays included, before an extension sees the value.
 */
class CashValueTest {

    @Test
    fun `a nested JS array reaches an extension as nested Object arrays`() {
        val converted = toCashValue(listOf(listOf("a"), "b")) as Array<*>

        (converted[0] as Array<*>).toList() shouldBe listOf("a")
    }
}
