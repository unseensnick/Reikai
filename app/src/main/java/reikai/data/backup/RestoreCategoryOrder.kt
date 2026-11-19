package reikai.data.backup

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import reikai.domain.category.CategoryContentType
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Puts the categories a restore created back in the backup's order across both libraries. Each library's
 * restore appends its own after every row of either, so a novel category backed up above a universal one
 * would come back below it; the backups' orders come from the one shared table, so they compare directly.
 * Rows the device already had keep their places, and the created ones only swap among the slots they took.
 */
@Inject
class RestoreCategoryOrder(
    private val categoryRepository: CategoryRepository,
) {

    /** The rows on the device before the restore, read before either library's categories go in. */
    suspend fun existing(): Set<Long> = categoryRepository.getUnfiltered().mapTo(HashSet()) { it.id }

    suspend fun restore(existing: Set<Long>, manga: List<BackupCategory>, novel: List<BackupNovelCategory>) {
        val rows = categoryRepository.getUnfiltered().filterNot(Category::isSystemCategory)
        val created = rows.filter { it.id !in existing }
        if (created.size < 2) return
        val inBackupOrder = created.sortedBy { backedUpOrder(it, manga, novel) ?: Long.MAX_VALUE }.iterator()
        categoryRepository.updateAllOrders(rows.map { if (it.id in existing) it.id else inBackupOrder.next().id })
    }

    // A universal row whose name a novel-only row holds is created as manga-only, so a name still matches.
    private fun backedUpOrder(row: Category, manga: List<BackupCategory>, novel: List<BackupNovelCategory>): Long? =
        if (row.contentType == CategoryContentType.NOVEL) {
            novel.firstOrNull { it.name == row.name }?.order
        } else {
            manga.filter { it.name == row.name }
                .let { named -> named.firstOrNull { it.contentType == row.contentType } ?: named.firstOrNull() }
                ?.order
        }
}
