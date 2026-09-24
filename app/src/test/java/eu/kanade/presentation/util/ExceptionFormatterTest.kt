package eu.kanade.presentation.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Which throwable the source error formatter reads. OkHttp's await wraps every network failure in a bare
 * IOException, so an offline source reached the formatter as that wrapper and showed the raw host error.
 */
class ExceptionFormatterTest {

    @Test
    fun `a network failure wrapped by OkHttp is formatted by its cause`() {
        IOException("Unable to resolve host", UnknownHostException("Unable to resolve host"))
            .formattableCause()
            .shouldBeInstanceOf<UnknownHostException>()
    }

    @Test
    fun `an error with no recognised cause is formatted as itself`() {
        val error = IOException("closed", IllegalStateException("closed"))

        (error.formattableCause() === error) shouldBe true
    }
}
