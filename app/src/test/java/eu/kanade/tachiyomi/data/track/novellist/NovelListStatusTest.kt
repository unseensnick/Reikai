package eu.kanade.tachiyomi.data.track.novellist

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * NovelList has no on-hold state. The reference fork maps on-hold onto "planned", which reads back
 * as plan-to-read, so the user's choice silently becomes a different one. The capability rule says
 * a state a service cannot represent is hidden rather than faked, so it is not offered at all.
 */
class NovelListStatusTest {

    /** The value every other tracker in the app uses for on-hold. */
    private val onHold = 3L

    private val tracker = NovelList(101L)

    @Test
    fun `only the four states the service can store are offered`() {
        tracker.getStatusList() shouldContainExactly listOf(
            NovelList.READING,
            NovelList.COMPLETED,
            NovelList.DROPPED,
            NovelList.PLAN_TO_READ,
        )
    }

    @Test
    fun `on-hold is neither offered nor labelled`() {
        tracker.getStatusList().contains(onHold) shouldBe false
        tracker.getStatus(onHold) shouldBe null
    }

    @Test
    fun `every offered status has a label`() {
        tracker.getStatusList().forEach { tracker.getStatus(it).shouldNotBeNull() }
    }
}
