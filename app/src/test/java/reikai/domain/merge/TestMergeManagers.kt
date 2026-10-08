package reikai.domain.merge

import io.mockk.coEvery
import io.mockk.mockk
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

/**
 * The real merge managers of both types over fixed [memberships] (entry id to group id per type), every
 * member in the library.
 */
class TestMergeManagers(memberships: Map<ContentType, Map<Long, Long>>, mergingOn: Boolean) {

    private val repository = mockk<MergeGroupRepository> {
        coEvery { getAllMemberships(any()) } answers { memberships[firstArg()].orEmpty() }
        coEvery { getGroupId(any(), any()) } answers { memberships[firstArg()]?.get(secondArg()) }
        // No group overrides the global source ranking.
        coEvery { getGroup(any()) } returns null
        coEvery { getFavoriteMembers(any(), any()) } answers {
            memberships[firstArg()].orEmpty().filterValues { it == secondArg<Long>() }.keys.toList()
        }
    }

    private val preferences = ReikaiLibraryPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference("series_merging_enabled", mergingOn, true))),
    )

    val manga = MangaMergeManager(repository, preferences, onMerged = {}) {}
    val novel = NovelMergeManager(repository, preferences, onMerged = {}) {}

    fun of(type: ContentType): EntryMergeManager = if (type == ContentType.MANGA) manga else novel
}
