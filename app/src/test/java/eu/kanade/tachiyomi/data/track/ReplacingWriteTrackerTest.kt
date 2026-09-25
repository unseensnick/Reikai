package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The tracking sheet asks before binding only a tracker that declares its writes replace the remote
 * entry. RanobeDB is the one that does: no route reads a list entry back, so a bind clears the labels
 * and notes of a series already on the user's list. A tracker that merges into the entry must not ask.
 */
class ReplacingWriteTrackerTest {

    @ParameterizedTest(name = "{0} asks before binding: {2}")
    @MethodSource("cases")
    fun `only a tracker whose writes replace the entry asks before binding`(
        @Suppress("UNUSED_PARAMETER") name: String,
        tracker: Tracker,
        expected: Boolean,
    ) {
        (tracker is ReplacingWriteTracker) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of("RanobeDB", RanobeDb(1L), true),
            Arguments.of("NovelList", NovelList(2L), false),
            Arguments.of("MyAnimeList", MyAnimeList(3L), false),
        )
    }
}
