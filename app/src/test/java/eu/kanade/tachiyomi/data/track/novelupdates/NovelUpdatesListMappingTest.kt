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

        mapping.statusFor(mapping.listIdFor(status)) shouldBe status
    }

    /** The failure this exists for: a remapped status must read back as itself, not as the default. */
    @ParameterizedTest
    @ValueSource(longs = [NovelUpdates.READING, NovelUpdates.ON_HOLD, NovelUpdates.DROPPED])
    fun `a custom status survives a round trip too`(status: Long) {
        val mapping = NovelUpdatesListMapping.from("""{"1":7,"3":8,"4":9}""", json)

        mapping.statusFor(mapping.listIdFor(status)) shouldBe status
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

    /** A list the mapping has never heard of leaves the local status alone rather than resetting it. */
    @Test
    fun `an unknown list reports no status`() {
        NovelUpdatesListMapping.Default.statusFor(42L) shouldBe null
    }
}
