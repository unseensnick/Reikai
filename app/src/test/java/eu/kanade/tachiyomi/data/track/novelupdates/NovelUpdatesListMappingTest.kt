package eu.kanade.tachiyomi.data.track.novelupdates

import io.kotest.matchers.shouldBe
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The reference fork honours a user's custom list map when writing and ignores it when reading, so
 * a remapped status comes back as a different one and is then pushed back wrong. One map answers
 * both directions here, and these cases are what hold that.
 */
class NovelUpdatesListMappingTest {

    private val json = AppBindings.providesJson()

    @ParameterizedTest
    @ValueSource(
        longs = [
            NovelUpdates.READING,
            NovelUpdates.COMPLETED,
            NovelUpdates.ON_HOLD,
            NovelUpdates.DROPPED,
            NovelUpdates.PLAN_TO_READ,
        ],
    )
    fun `every default status survives a round trip through its list`(status: Long) {
        val mapping = NovelUpdatesListMapping.Default

        mapping.listIdFor(status)?.let(mapping::statusFor) shouldBe status
    }

    /** The failure this exists for: a remapped status must read back as itself, not as the default. */
    @ParameterizedTest
    @ValueSource(longs = [NovelUpdates.READING, NovelUpdates.ON_HOLD, NovelUpdates.DROPPED])
    fun `a custom status survives a round trip too`(status: Long) {
        val mapping = NovelUpdatesListMapping.from("""{"1":7,"3":8,"4":9}""", json)

        mapping.listIdFor(status)?.let(mapping::statusFor) shouldBe status
    }

    @Test
    fun `a custom map moves only the statuses it names`() {
        val mapping = NovelUpdatesListMapping.from("""{"3":8}""", json)

        mapping.listIdFor(NovelUpdates.ON_HOLD) shouldBe 8L
        mapping.listIdFor(NovelUpdates.COMPLETED) shouldBe 1L
        mapping.statusFor(8L) shouldBe NovelUpdates.ON_HOLD
    }

    /** An unreadable or empty preference must not lose the user's tracking, only their remapping. */
    @Test
    fun `a broken or empty preference falls back to the stock lists`() {
        listOf("", "not json", "{}", """{"nope":1}""").forEach { stored ->
            NovelUpdatesListMapping.from(stored, json)
                .listIdFor(NovelUpdates.PLAN_TO_READ) shouldBe 2L
        }
    }

    /** A list the user made that the mapping does not cover reads as that, not as a stock status. */
    @Test
    fun `an unknown list reads as a list of the user's own`() {
        NovelUpdatesListMapping.Default.statusFor(42L) shouldBe NovelUpdates.OTHER_LIST
    }

    /** So a push leaves the series on the user's own list instead of moving it onto Reading. */
    @Test
    fun `a series on a list of the user's own is moved nowhere`() {
        NovelUpdatesListMapping.Default.listIdFor(NovelUpdates.OTHER_LIST) shouldBe null
    }
}
