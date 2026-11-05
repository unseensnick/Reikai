package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourcePaceTest {

    private val pace = SourcePace()

    private fun fail(sourceId: String = "a", floorMs: Long = 3_000L, hasMore: Boolean = true) =
        pace.waitAfter(sourceId, succeeded = false, floorMs = floorMs, hasMore = hasMore, random = 0.0)

    @Test
    fun `a failure doubles the wait from the floor`() {
        fail() shouldBe 6_000L
    }

    @Test
    fun `failures in a row keep doubling`() {
        fail()

        fail() shouldBe 12_000L
    }

    @Test
    fun `Off never waits after a back-off`() {
        fail()
        fail()

        fail(floorMs = 0L) shouldBe 0L
    }

    @Test
    fun `a delay turned on again after Off starts from its floor`() {
        fail()
        fail(floorMs = 0L)

        fail() shouldBe 6_000L
    }

    @Test
    fun `a source with nothing more queued does not wait`() {
        fail(hasMore = false) shouldBe 0L
    }

    @Test
    fun `a back-off is forgotten once the source has nothing more queued`() {
        fail()
        fail(hasMore = false)

        fail() shouldBe 6_000L
    }

    @Test
    fun `one source's back-off does not slow another`() {
        fail()

        fail(sourceId = "b") shouldBe 6_000L
    }
}
