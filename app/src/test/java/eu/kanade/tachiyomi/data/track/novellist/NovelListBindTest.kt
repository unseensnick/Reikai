package eu.kanade.tachiyomi.data.track.novellist

import eu.kanade.tachiyomi.data.track.novellist.NovelList.Companion.COMPLETED
import eu.kanade.tachiyomi.data.track.novellist.NovelList.Companion.DROPPED
import eu.kanade.tachiyomi.data.track.novellist.NovelList.Companion.PLAN_TO_READ
import eu.kanade.tachiyomi.data.track.novellist.NovelList.Companion.READING
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * A bind keeps an entry already on the user's list, as MyAnimeList's does: only read chapters move it,
 * and then to Reading unless it is Completed. NovelList has no reread state, so Dropped reopens.
 */
class NovelListBindTest {

    @ParameterizedTest(name = "on the list as {0}, read {1} -> {2}")
    @MethodSource("cases")
    fun `bind decides the status from the site's entry`(siteStatus: Long?, hasReadChapters: Boolean, expected: Long) {
        statusOnBind(siteStatus, hasReadChapters) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of(null, false, PLAN_TO_READ),
            Arguments.of(null, true, READING),
            Arguments.of(COMPLETED, true, COMPLETED),
            Arguments.of(COMPLETED, false, COMPLETED),
            Arguments.of(DROPPED, true, READING),
            Arguments.of(DROPPED, false, DROPPED),
            Arguments.of(PLAN_TO_READ, true, READING),
            Arguments.of(PLAN_TO_READ, false, PLAN_TO_READ),
            Arguments.of(READING, false, READING),
        )
    }
}
