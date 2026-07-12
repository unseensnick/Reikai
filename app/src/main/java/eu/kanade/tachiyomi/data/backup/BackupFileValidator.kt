package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.track.TrackerManager
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BackupFileValidator(
    private val context: Context,

    private val sourceManager: SourceManager = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    // RK: novels validate against their own source registry + the shared tracker manager.
    private val novelSourceManager: NovelSourceManager = Injekt.get(),
) {

    /**
     * Checks for critical backup file data.
     *
     * @return List of missing sources or missing trackers.
     */
    fun validate(uri: Uri): Results {
        val backup = try {
            BackupDecoder(context).decode(uri)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }

        val sources = backup.backupSources.associate { it.sourceId to it.name }
        val missingSources = sources
            .filter { sourceManager.get(it.key) == null }
            .values.map {
                val id = it.toLongOrNull()
                if (id == null) {
                    it
                } else {
                    sourceManager.getOrStub(id).toString()
                }
            }
            .distinct()
            .sorted()

        val trackers = backup.backupManga
            .flatMap { it.tracking }
            .map { it.syncId }
            .distinct()
        val missingTrackers = trackers
            .mapNotNull { trackerManager.get(it.toLong()) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        // RK --> fold in novel sources / trackers the restore can't satisfy, so the pre-restore
        // warning covers novels too. A missing novel source shows by its id (novels carry no
        // source-name table); novel trackers reuse the shared tracker manager.
        val missingNovelSources = backup.backupNovels
            .map { it.source }
            .distinct()
            .filter { novelSourceManager.get(it) == null }
        val missingNovelTrackers = backup.backupNovels
            .flatMap { it.tracking }
            .map { it.trackerId }
            .distinct()
            .mapNotNull { trackerManager.get(it) }
            .filter { !it.isLoggedIn }
            .map { it.name }

        return Results(
            (missingSources + missingNovelSources).distinct().sorted(),
            (missingTrackers + missingNovelTrackers).distinct().sorted(),
        )
        // RK <--
    }

    data class Results(
        val missingSources: List<String>,
        val missingTrackers: List<String>,
    )
}
