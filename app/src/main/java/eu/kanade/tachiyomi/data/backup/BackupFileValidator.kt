package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.serialization.protobuf.ProtoBuf
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
    private val parser: ProtoBuf = Injekt.get(),
) {

    /**
     * Checks for critical backup file data.
     *
     * RK: streams the backup field by field (via [BackupProtoReader]) and decodes only the manga,
     * source, and novel entries it needs, instead of re-inflating the whole file, which OOMs on a
     * large backup (Issue #53).
     *
     * @return List of missing sources or missing trackers.
     */
    suspend fun validate(uri: Uri): Results {
        val backupSources = mutableListOf<BackupSource>()
        val mangaTrackerIds = mutableSetOf<Int>()
        val novelSources = mutableSetOf<String>()
        val novelTrackerIds = mutableSetOf<Long>()

        try {
            BackupProtoReader(context).read(uri) { fieldNumber, data ->
                when (fieldNumber) {
                    1 -> parser.decodeFromByteArray(BackupManga.serializer(), data)
                        .tracking.forEach { mangaTrackerIds.add(it.syncId) }
                    101 -> backupSources.add(parser.decodeFromByteArray(BackupSource.serializer(), data))
                    700 -> parser.decodeFromByteArray(BackupNovel.serializer(), data).let { novel ->
                        novelSources.add(novel.source)
                        novel.tracking.forEach { novelTrackerIds.add(it.trackerId) }
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }

        val sources = backupSources.associate { it.sourceId to it.name }
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

        val missingTrackers = mangaTrackerIds
            .mapNotNull { trackerManager.get(it.toLong()) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        // RK --> fold in novel sources / trackers the restore can't satisfy, so the pre-restore
        // warning covers novels too. A missing novel source shows by its id (novels carry no
        // source-name table); novel trackers reuse the shared tracker manager.
        val missingNovelSources = novelSources
            .filter { novelSourceManager.get(it) == null }
        val missingNovelTrackers = novelTrackerIds
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
