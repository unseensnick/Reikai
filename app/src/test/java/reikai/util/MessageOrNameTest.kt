package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.UnknownHostException

/** An extension store and an LN plugin repo name a failed fetch alike. */
class MessageOrNameTest {

    @Test
    fun `a failure reads as its message, or as its type where it has none`() {
        listOf(IOException("HTTP 404"), UnknownHostException()).map { it.messageOrName } shouldBe
            listOf("HTTP 404", "UnknownHostException")
    }
}
