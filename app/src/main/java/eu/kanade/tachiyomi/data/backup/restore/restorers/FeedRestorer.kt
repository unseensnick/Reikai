package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupFeedRow
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import reikai.domain.source.FeedSavedSearchRepository
import reikai.domain.source.MAX_FEED_ROWS
import reikai.domain.source.SavedSearchRepository
import reikai.domain.source.SourceKey
import reikai.domain.source.model.SavedSearch

/**
 * Puts saved searches and feed rows back, matching on what they hold rather than on the ids they had.
 * Restoring onto an install that already has some must not double them, and a backup's ids mean
 * nothing here, so a search is the same search when its source, name, query and filters agree.
 *
 * A row whose source is not installed is restored anyway. It costs one row that shows nothing until
 * that source is back, where dropping it would lose the row for good.
 */
@Inject
class FeedRestorer(
    private val savedSearchRepository: SavedSearchRepository,
    private val feedRepository: FeedSavedSearchRepository,
) {

    suspend operator fun invoke(
        backupSavedSearches: List<BackupSavedSearch>,
        backupFeedRows: List<BackupFeedRow>,
    ) {
        if (backupSavedSearches.isEmpty() && backupFeedRows.isEmpty()) return

        backupSavedSearches.forEach { resolve(it) }

        // Restored in the order they were arranged in, since the row's own number cannot be kept:
        // insert assigns the next one, so the sequence is what carries the order across.
        backupFeedRows.sortedBy { it.feedOrder }.forEach { row ->
            val sourceKey = SourceKey.parse(row.sourceKey) ?: return@forEach
            // A backup is untrusted input and every row costs a source round trip on each open, so an
            // over-long feed is refused rather than rendered. Our own backups can never exceed it.
            if (row.global && feedRepository.countGlobal() >= MAX_FEED_ROWS) return@forEach
            val savedSearchId = row.savedSearch?.let { resolve(it) }
            // The repository returns the existing row rather than adding a second one like it, so a
            // file restored twice, or one carrying the same row twice, lands it once.
            feedRepository.insert(sourceKey, savedSearchId, row.global)
        }
    }

    /** The id of the matching saved search, inserting it when this install has none like it. */
    private suspend fun resolve(backup: BackupSavedSearch): Long? {
        val sourceKey = SourceKey.parse(backup.sourceKey) ?: return null
        val existing = savedSearchRepository.getBySource(sourceKey).firstOrNull { it.matches(backup) }
        return existing?.id ?: savedSearchRepository.insert(
            sourceKey = sourceKey,
            name = backup.name,
            query = backup.query,
            filtersJson = backup.filtersJson,
        )
    }

    private fun SavedSearch.matches(backup: BackupSavedSearch) =
        name == backup.name && query == backup.query && filtersJson == backup.filtersJson
}
