package reikai.domain.merge

import io.mockk.coEvery
import io.mockk.mockk
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

/** The real merge managers of both types over fixed [memberships] (entry id to group id per type). */
class TestMergeManagers(memberships: Map<ContentType, Map<Long, Long>>, mergingOn: Boolean) {

    private val repository = mockk<MergeGroupRepository> {
        coEvery { getAllMemberships(any()) } answers { memberships[firstArg()].orEmpty() }
    }

    private val preferences = ReikaiLibraryPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference("series_merging_enabled", mergingOn, true))),
    )

    val manga = MangaMergeManager(repository, preferences) {}
    val novel = NovelMergeManager(repository, preferences) {}

    fun of(type: ContentType): EntryMergeManager = if (type == ContentType.MANGA) manga else novel
}
