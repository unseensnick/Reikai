package reikai.domain.merge

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

/**
 * The real merge managers of both types over [memberships] (entry id to group id per type), every
 * member in the library. Setting [groups] regroups them, as a merge or an unmerge does.
 */
class TestMergeManagers(memberships: Map<ContentType, Map<Long, Long>>, mergingOn: Boolean) {

    val groups = MutableStateFlow(memberships)

    private val repository = mockk<MergeGroupRepository> {
        coEvery { getAllMemberships(any()) } answers { groups.value[firstArg()].orEmpty() }
        coEvery { getGroupId(any(), any()) } answers { groups.value[firstArg()]?.get(secondArg()) }
        every { getLibraryMembershipsAsFlow(any()) } answers {
            val type = firstArg<ContentType>()
            groups.map { it[type].orEmpty() }
        }
        // No group overrides the global source ranking.
        coEvery { getGroup(any()) } returns null
        every { getOverrideRankingsAsFlow(any()) } returns flowOf(emptyMap())
        coEvery { getFavoriteMembers(any(), any()) } answers {
            groups.value[firstArg()].orEmpty().filterValues { it == secondArg<Long>() }.keys.toList()
        }
    }

    private val preferences = ReikaiLibraryPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference("series_merging_enabled", mergingOn, true))),
    )

    val manga = MangaMergeManager(repository, preferences, onMerged = {}) {}
    val novel = NovelMergeManager(repository, preferences, onMerged = {}) {}

    fun of(type: ContentType): EntryMergeManager = if (type == ContentType.MANGA) manga else novel
}
