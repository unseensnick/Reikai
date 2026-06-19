package reikai.domain.novel.interactor

import kotlinx.coroutines.CancellationException
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelMigrationFlag
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Move a favorited novel's state onto a [target] novel from another source, the novel twin of
 * [mihon.domain.migration.usecases.MigrateMangaUseCase] (and modelled on LNReader's `migrateNovel`).
 *
 * The target is already materialised + chapter-synced by the picker, so this is DB-only: it copies
 * per-chapter read / bookmark / scroll-progress (matched by chapter number), moves categories,
 * favorites the target, and (when [replace]) unfavorites the old novel and splits it out of any merge
 * group. History-tab rows and tracks are intentionally not carried (parity with Mihon).
 */
class MigrateNovelUseCase(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val getNovelCategories: GetNovelCategories = Injekt.get(),
    private val setNovelCategories: SetNovelCategories = Injekt.get(),
    private val novelMergeManager: NovelMergeManager = Injekt.get(),
) {

    suspend operator fun invoke(
        current: Novel,
        target: Novel,
        flags: Set<NovelMigrationFlag>,
        replace: Boolean,
    ) {
        if (current.id == target.id) return
        try {
            if (NovelMigrationFlag.CHAPTER in flags) {
                val currentChapters = novelChapterRepository.getByNovelId(current.id)
                val targetChapters = novelChapterRepository.getByNovelId(target.id)
                computeChapterMigration(currentChapters, targetChapters).forEach {
                    novelChapterRepository.update(it)
                }
            }

            if (NovelMigrationFlag.CATEGORY in flags) {
                val categoryIds = getNovelCategories.awaitByNovelId(current.id).map { it.id }
                setNovelCategories.await(target.id, categoryIds)
            }

            novelRepository.update(
                target.copy(favorite = true, lastReadAt = current.lastReadAt ?: target.lastReadAt),
            )

            if (replace) {
                // Resolve the old novel's merge group before unfavoriting it, then split it out so no
                // dangling merge-pref pair lingers (harmless, but tidy).
                val group = novelMergeManager.computeRelatedNovelIds(current.id, current.title, current.author)
                novelRepository.update(current.copy(favorite = false))
                if (group.size > 1) novelMergeManager.removeFromGroup(group, listOf(current.id))
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}

/**
 * Pure core: given the source novel's chapters and the target's, return the target chapters whose
 * read / bookmark / progress should change. A target chapter takes its matched (same chapter number)
 * source chapter's state; additionally every target chapter at or below the highest read source
 * number is marked read (mirrors Mihon's `maxChapterRead` sweep, so coarser target numbering still
 * reflects how far you'd read). Unrecognized numbers (< 0) are skipped, matching the sync convention.
 */
internal fun computeChapterMigration(
    currentChapters: List<NovelChapter>,
    targetChapters: List<NovelChapter>,
): List<NovelChapter> {
    val maxReadNumber = currentChapters
        .filter { it.read && it.chapterNumber >= 0.0 }
        .maxOfOrNull { it.chapterNumber }

    return targetChapters.mapNotNull { target ->
        if (target.chapterNumber < 0.0) return@mapNotNull null

        val match = currentChapters.firstOrNull { it.chapterNumber >= 0.0 && it.chapterNumber == target.chapterNumber }
        var read = if (match != null) match.read else target.read
        val bookmark = if (match != null) match.bookmark else target.bookmark
        val progress = if (match != null) match.lastTextProgress else target.lastTextProgress
        if (maxReadNumber != null && target.chapterNumber <= maxReadNumber) read = true

        if (read == target.read && bookmark == target.bookmark && progress == target.lastTextProgress) {
            null
        } else {
            target.copy(read = read, bookmark = bookmark, lastTextProgress = progress)
        }
    }
}
