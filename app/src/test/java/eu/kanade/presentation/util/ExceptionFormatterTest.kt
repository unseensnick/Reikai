package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Which throwable the source error formatter reads. OkHttp's await wraps every network failure in a bare
 * IOException, so an offline source reached the formatter as that wrapper and showed the raw host error.
 */
class ExceptionFormatterTest {

    private val context = mockk<Context>(relaxed = true)

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

    @Test
    fun `a failed load the formatter can word reads in its words`() {
        val error = IOException("raw wrapper text", HttpException(503))

        with(context) { error.loadFailureMessage } shouldBe with(context) { HttpException(503).formattedMessage }
    }

    @Test
    fun `a failed load the formatter cannot word keeps its own text`() {
        with(context) { IllegalStateException("Source is not installed").loadFailureMessage } shouldBe
            "Source is not installed"
    }

    @Test
    fun `a failed load with no text still says what failed`() {
        with(context) { IllegalStateException().loadFailureMessage } shouldBe "IllegalStateException: null"
    }
}
