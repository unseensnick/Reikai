package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.UnknownHostException

class CauseChainTest {

    class Case(val name: String, val error: Throwable, val expected: String) {
        override fun toString() = name
    }

    @ParameterizedTest
    @MethodSource("firstCauses")
    fun `the first cause looked for is found anywhere in the chain, the error itself included`(case: Case) {
        case.error.firstCause { (it as? UnknownHostException)?.message } shouldBe case.expected
    }

    // An extension store and an LN plugin repo name a failed fetch alike.
    @ParameterizedTest
    @MethodSource("messageOrNames")
    fun `a failure reads as its message, or as its type where it has none`(case: Case) {
        case.error.messageOrName shouldBe case.expected
    }

    companion object {
        @JvmStatic
        fun firstCauses() = listOf(
            Case("the error itself", UnknownHostException("itself"), "itself"),
            Case("a wrapped cause", IOException(UnknownHostException("wrapped")), "wrapped"),
        )

        @JvmStatic
        fun messageOrNames() = listOf(
            Case("a message", IOException("HTTP 404"), "HTTP 404"),
            Case("no message", UnknownHostException(), "UnknownHostException"),
        )
    }
}
