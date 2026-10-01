package reikai.domain.track

import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.NovelTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track

/**
 * A details page stays open across a merge, a re-add and a removal, so its tracks subscription has to
 * follow the group the way the merge manager resolves it: the Tracking button counts what this read
 * emits. Run over each type's group read, each over its real merge manager and a stored group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupTrackSubscriptionConformanceTest {

    enum class Type(val contentType: ContentType) { MANGA(ContentType.MANGA), NOVEL(ContentType.NOVELS) }

    /** The membership table: entry id to group id. */
    private val memberships = MutableStateFlow(emptyMap<Long, Long>())

    /** The entries in the library. */
    private val library = MutableStateFlow(setOf(OPEN, SIBLING))

    private val mergingEnabled = MutableStateFlow(true)

    private val preferences = mockk<ReikaiLibraryPreferences>(relaxed = true) {
        every { syncTrackerLinksGrouped } returns mockk { every { get() } returns true }
        every { seriesMergingEnabled } returns mockk {
            every { get() } answers { mergingEnabled.value }
            every { changes() } returns mergingEnabled
        }
    }

    private fun repository(type: ContentType) = mockk<MergeGroupRepository> {
        every { getAllMembershipsAsFlow(type) } returns memberships
        every { getLibraryMembershipsAsFlow(type) } returns
            combine(memberships, library) { groups, inLibrary -> groups.filterKeys { it in inLibrary } }
        coEvery { getGroupId(type, any()) } answers { memberships.value[secondArg<Long>()] }
        coEvery { getFavoriteMembers(type, any()) } answers {
            memberships.value.filter { (id, group) -> group == secondArg<Long>() && id in library.value }.keys.toList()
        }
    }

    /** The tracker ids [OPEN]'s group read emits; only [SIBLING] has a track. */
    private fun trackerIds(type: Type): Flow<List<Long>> = when (type) {
        Type.MANGA -> GetTracksInGroup(
            preferences,
            mockk<GetTracks> {
                every { subscribe(OPEN) } returns flowOf(emptyList())
                every { subscribe(SIBLING) } returns flowOf(listOf(mangaTrack()))
            },
            MangaMergeManager(repository(type.contentType), preferences) {},
        ).subscribe(OPEN).map { it.map(Track::trackerId) }
        Type.NOVEL -> GetNovelTracks(
            mockk<NovelTrackRepository> {
                every { getTracksByNovelIdAsFlow(OPEN) } returns flowOf(emptyList())
                every { getTracksByNovelIdAsFlow(SIBLING) } returns flowOf(listOf(novelTrack()))
            },
            NovelMergeManager(repository(type.contentType), preferences) {},
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

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an open subscription picks up the group's tracker once its entry is added back to the library`(
        type: Type,
    ) = runTest {
        // A removed entry keeps its membership row, so the re-add touches only the library.
        memberships.value = mapOf(OPEN to GROUP, SIBLING to GROUP)
        library.value = setOf(SIBLING)
        val seen = mutableListOf<List<Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trackerIds(type).toList(seen) }

        library.value = setOf(OPEN, SIBLING)

        seen.last() shouldContainExactly listOf(TRACKER)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an open subscription drops a sibling's tracker once the sibling leaves the library`(type: Type) = runTest {
        memberships.value = mapOf(OPEN to GROUP, SIBLING to GROUP)
        val seen = mutableListOf<List<Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trackerIds(type).toList(seen) }

        library.value = setOf(OPEN)

        seen.last() shouldContainExactly emptyList()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an open subscription drops a sibling's tracker once merging is switched off`(type: Type) = runTest {
        memberships.value = mapOf(OPEN to GROUP, SIBLING to GROUP)
        val seen = mutableListOf<List<Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trackerIds(type).toList(seen) }

        mergingEnabled.value = false

        seen.last() shouldContainExactly emptyList()
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
