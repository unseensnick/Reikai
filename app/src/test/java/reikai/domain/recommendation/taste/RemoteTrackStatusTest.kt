package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.dto.ALLibraryEntry
import eu.kanade.tachiyomi.data.track.anilist.dto.ALLibraryMedia
import eu.kanade.tachiyomi.data.track.anilist.dto.ALLibraryMediaTitle
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionItem
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryItem
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryListStatus
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryNode
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRate
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.track.installTrackerTestGraph
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

class RemoteStatusCase(
    private val label: String,
    val fetcher: () -> TrackerLibraryFetcher,
    val expected: TrackStatus,
) {
    override fun toString() = label
}

/**
 * A tracker library's list status reaches the taste profile as the status the tracker itself means
 * by that token, one entry per case through the real fetcher.
 */
class RemoteTrackStatusTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a library entry's list status maps to the tracker's own meaning`(case: RemoteStatusCase) = runTest {
        case.fetcher().fetchLibrary().single().status shouldBe case.expected
    }

    companion object {
        private lateinit var appScope: InjektScope

        @JvmStatic
        @BeforeAll
        fun installGraph() {
            appScope = installTrackerTestGraph()
        }

        @JvmStatic
        @AfterAll
        fun restoreGraph() {
            Injekt = appScope
        }

        private fun preferences() = ReikaiRecommendationPreferences(InMemoryPreferenceStore())

        private fun anilist(status: String?, expected: TrackStatus) = RemoteStatusCase(
            "AniList $status",
            {
                val tracker = spyk(Anilist(2))
                coEvery { tracker.getUserLibrary() } returns listOf(
                    ALLibraryEntry(status = status, media = ALLibraryMedia(id = 1, title = ALLibraryMediaTitle("t"))),
                )
                AnilistLibraryFetcher(tracker, preferences())
            },
            expected,
        )

        private fun myAnimeList(listStatus: MALLibraryListStatus?, expected: TrackStatus) = RemoteStatusCase(
            "MyAnimeList $listStatus",
            {
                val tracker = spyk(MyAnimeList(1))
                coEvery { tracker.getUserLibrary() } returns listOf(
                    MALLibraryItem(node = MALLibraryNode(id = 1, title = "t"), listStatus = listStatus),
                )
                MyAnimeListLibraryFetcher(tracker, preferences())
            },
            expected,
        )

        private fun kitsu(status: String, expected: TrackStatus) = RemoteStatusCase(
            "Kitsu $status",
            {
                val tracker = spyk(Kitsu(3))
                coEvery { tracker.getUserLibrary() } returns listOf(
                    KitsuLibraryEntry(1, "t", status, null, emptyList(), null, null),
                )
                KitsuLibraryFetcher(tracker, preferences())
            },
            expected,
        )

        private fun shikimori(status: String?, expected: TrackStatus) = RemoteStatusCase(
            "Shikimori $status",
            {
                val tracker = spyk(Shikimori(4))
                coEvery { tracker.getUserLibrary() } returns listOf(
                    SMUserRate(status = status, manga = SMUserRateManga(id = "1", name = "t")),
                )
                ShikimoriLibraryFetcher(tracker, preferences())
            },
            expected,
        )

        private fun bangumi(type: Int, expected: TrackStatus) = RemoteStatusCase(
            "Bangumi $type",
            {
                val tracker = spyk(Bangumi(5))
                coEvery { tracker.getUserLibrary() } returns listOf(BGMCollectionItem(subjectId = 1, type = type))
                BangumiLibraryFetcher(tracker, preferences())
            },
            expected,
        )

        private fun mal(status: String?, rereading: Boolean = false) =
            MALLibraryListStatus(status = status, isRereading = rereading)

        @JvmStatic
        fun cases() = listOf(
            anilist("CURRENT", TrackStatus.READING),
            anilist("REPEATING", TrackStatus.READING),
            anilist("COMPLETED", TrackStatus.COMPLETED),
            anilist("PAUSED", TrackStatus.ON_HOLD),
            anilist("DROPPED", TrackStatus.DROPPED),
            anilist("PLANNING", TrackStatus.PLAN_TO_READ),
            anilist(null, TrackStatus.UNKNOWN),
            anilist("UNHEARD_OF", TrackStatus.UNKNOWN),
            myAnimeList(mal("reading"), TrackStatus.READING),
            myAnimeList(mal("completed"), TrackStatus.COMPLETED),
            myAnimeList(mal("on_hold"), TrackStatus.ON_HOLD),
            myAnimeList(mal("dropped"), TrackStatus.DROPPED),
            myAnimeList(mal("plan_to_read"), TrackStatus.PLAN_TO_READ),
            myAnimeList(mal("completed", rereading = true), TrackStatus.READING),
            myAnimeList(mal(null), TrackStatus.UNKNOWN),
            myAnimeList(mal("unheard_of"), TrackStatus.UNKNOWN),
            myAnimeList(null, TrackStatus.UNKNOWN),
            kitsu("current", TrackStatus.READING),
            kitsu("CURRENT", TrackStatus.READING),
            kitsu("completed", TrackStatus.COMPLETED),
            kitsu("on_hold", TrackStatus.ON_HOLD),
            kitsu("ON_HOLD", TrackStatus.ON_HOLD),
            kitsu("dropped", TrackStatus.DROPPED),
            kitsu("planned", TrackStatus.PLAN_TO_READ),
            kitsu("PLANNED", TrackStatus.PLAN_TO_READ),
            kitsu("unheard_of", TrackStatus.UNKNOWN),
            shikimori("watching", TrackStatus.READING),
            shikimori("rewatching", TrackStatus.READING),
            shikimori("completed", TrackStatus.COMPLETED),
            shikimori("on_hold", TrackStatus.ON_HOLD),
            shikimori("dropped", TrackStatus.DROPPED),
            shikimori("planned", TrackStatus.PLAN_TO_READ),
            shikimori(null, TrackStatus.UNKNOWN),
            shikimori("unheard_of", TrackStatus.UNKNOWN),
            bangumi(1, TrackStatus.PLAN_TO_READ),
            bangumi(2, TrackStatus.COMPLETED),
            bangumi(3, TrackStatus.READING),
            bangumi(4, TrackStatus.ON_HOLD),
            bangumi(5, TrackStatus.DROPPED),
            bangumi(0, TrackStatus.UNKNOWN),
            bangumi(6, TrackStatus.UNKNOWN),
        )
    }
}
