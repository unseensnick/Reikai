package reikai.domain.novel

import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.MergeGroupRepository

/**
 * Novel source-grouping manager: [EntryMergeManager] fixed to [ContentType.NOVELS]. A distinct type only so
 * injectors resolve the novel manager by type; all the logic lives in the shared base.
 */
class NovelMergeManager(
    repository: MergeGroupRepository,
    preferences: ReikaiLibraryPreferences,
) : EntryMergeManager(ContentType.NOVELS, repository, preferences)
