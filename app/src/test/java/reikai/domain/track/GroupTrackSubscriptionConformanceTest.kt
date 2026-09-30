package reikai.domain.track

import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.EntryMergeManager
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.NovelTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track

/**
 * A details page stays open across a merge, so its tracks subscription has to follow the group as it
 * forms: the Tracking button counts what this read emits. Run over each type's group read, which hand
 * [GroupTrackReader] their own merge manager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupTrackSubscriptionConformanceTest {

    enum class Type { MANGA, NOVEL }

    private val memberships = MutableStateFlow(emptyMap<Long, Long>())

    private val preferences = mockk<ReikaiLibraryPreferences> {
        every { syncTrackerLinksGrouped } returns mockk { every { get() } returns true }
    }

    private fun <M : EntryMergeManager> M.followingMemberships(): M = apply {
        every { membershipChanges() } returns memberships
        coEvery { relatedIdsList(OPEN) } answers { memberships.value.keys.toList().ifEmpty { listOf(OPEN) } }
    }

    /** The tracker ids [OPEN]'s group read emits; only [SIBLING] has a track. */
    private fun trackerIds(type: Type): Flow<List<Long>> = when (type) {
        Type.MANGA -> GetTracksInGroup(
            preferences,
            mockk<GetTracks> {
                every { subscribe(OPEN) } returns flowOf(emptyList())
                every { subscribe(SIBLING) } returns flowOf(listOf(mangaTrack()))
            },
            mockk<MangaMergeManager>().followingMemberships(),
        ).subscribe(OPEN).map { it.map(Track::trackerId) }
        Type.NOVEL -> GetNovelTracks(
            mockk<NovelTrackRepository> {
                every { getTracksByNovelIdAsFlow(OPEN) } returns flowOf(emptyList())
                every { getTracksByNovelIdAsFlow(SIBLING) } returns flowOf(listOf(novelTrack()))
            },
            mockk<NovelMergeManager>().followingMemberships(),
            preferences,
        ).subscribeGroup(OPEN).map { it.map(NovelTrack::trackerId) }
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an open subscription picks up the group's tracker once its entry joins the group`(type: Type) = runTest {
        val seen = mutableListOf<List<Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trackerIds(type).toList(seen) }

        memberships.value = mapOf(OPEN to GROUP, SIBLING to GROUP)

        seen.last() shouldContainExactly listOf(TRACKER)
    }

    private companion object {
        const val OPEN = 1L
        const val SIBLING = 2L
        const val GROUP = 9L
        const val TRACKER = 10L

        fun mangaTrack() = Track(
            id = 1L,
            mangaId = SIBLING,
            trackerId = TRACKER,
            remoteId = 100L,
            libraryId = null,
            title = "title",
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )

        fun novelTrack() = NovelTrack(
            id = 1L,
            novelId = SIBLING,
            trackerId = TRACKER,
            remoteId = 100L,
            libraryId = null,
            title = "title",
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )
    }
}
