package eu.kanade.presentation.util

import io.kotest.matchers.nulls.shouldBeNull
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
            .wordedCause()
            .shouldBeInstanceOf<UnknownHostException>()
    }

    @Test
    fun `an error with no recognised cause has no worded cause`() {
        IOException("closed", IllegalStateException("closed")).wordedCause().shouldBeNull()
    }
}
