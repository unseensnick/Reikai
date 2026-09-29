package reikai.domain.manga

import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.MergeGroupRepository

/**
 * Manga source-grouping manager: [EntryMergeManager] fixed to [ContentType.MANGA] and the manga
 * same-title switch. A distinct type only so injectors resolve the manga manager by type; all the logic
 * lives in the shared base.
 */
class MangaMergeManager(
    repository: MergeGroupRepository,
    preferences: ReikaiLibraryPreferences,
    onBeforeDissolve: suspend (memberIds: List<Long>) -> Unit,
) : EntryMergeManager(
    ContentType.MANGA,
    repository,
    preferences,
    sameTitlePreference = preferences.autoMergeSameTitle,
    onBeforeDissolve = onBeforeDissolve,
)
