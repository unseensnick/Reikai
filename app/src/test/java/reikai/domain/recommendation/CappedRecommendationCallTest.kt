package reikai.domain.recommendation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class CappedRecommendationCallTest {

    private val failure = { "failed" }

    @Test
    fun `a call that answers within 15 seconds is kept`() = runTest {
        cappedRecommendationCall(failure) {
            delay(14.seconds)
            listOf("rec")
        } shouldBe listOf("rec")
    }

    @Test
    fun `a call still running at 15 seconds is dropped`() = runTest {
        cappedRecommendationCall(failure) {
            delay(16.seconds)
            listOf("rec")
        }.shouldBeNull()
    }

    @Test
    fun `a failed call is dropped`() = runTest {
        cappedRecommendationCall<List<String>>(failure) { throw IOException("down") }.shouldBeNull()
    }

    @Test
    fun `a cancelled call is not swallowed`() = runTest {
        shouldThrow<CancellationException> {
            cappedRecommendationCall<List<String>>(failure) { throw CancellationException("screen closed") }
        }
    }
}
