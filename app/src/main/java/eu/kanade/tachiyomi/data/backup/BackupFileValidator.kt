package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.serialization.protobuf.ProtoBuf
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.source.service.SourceManager

@Inject
class BackupFileValidator(
    // RK: kept, unlike upstream: the streaming reader needs it where upstream decodes the whole file.
    private val context: Context,
    private val sourceManager: SourceManager,
    private val trackerManager: TrackerManager,
    // RK: novels validate against their own source registry + the shared tracker manager.
    private val novelSourceManager: NovelSourceManager,
    private val parser: ProtoBuf,
) {

    /**
     * Checks for critical backup file data.
     *
     * RK: streams the backup field by field (via [BackupProtoReader]) and decodes only the manga,
     * source, and novel entries it needs, instead of re-inflating the whole file, which OOMs on a
     * large backup (unseensnick/Reikai#53).
     *
     * @return List of missing sources or missing trackers.
     */
    suspend fun validate(uri: Uri): Results {
        // RK --> the novel registry is empty until something loads the plugins, and a cold open straight to
        // restore is one, so without this every installed novel source reports as missing.
        novelSourceManager.ensureLoaded()

        val (backupSources, trackerIds, novelSources, novelSourceNames) = scan(uri)
        // RK <--

        val sources = backupSources.associate { it.sourceId to it.name } // RK: streamed
        val missingSources = sources
            .filterKeys { sourceManager.get(it) == null }
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

        val missingTrackers = trackerIds // RK: streamed, manga and novel ids in one set
            .mapNotNull { trackerManager.get(it) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        // RK --> fold in novel sources the restore can't satisfy, so the pre-restore warning covers
        // novels too. A missing novel source shows by the name the backup recorded, or by its id in a
        // backup older than that list.
        val missingNovelSources = novelSources
            .filter { novelSourceManager.get(it) == null }
            .map { novelSourceNames[it]?.ifBlank { null } ?: it }

        return Results((missingSources + missingNovelSources).distinct().sorted(), missingTrackers)
        // RK <--
    }

    // RK -->

    /**
     * Throws [IllegalStateException] when the file at [uri] does not decode, and resolves nothing: the
     * check a freshly written backup needs. Resolving novel sources loads every installed plugin and can
     * fetch over the network, which [validate] does only because the restore screen shows its answer.
     */
    suspend fun checkReadable(uri: Uri) {
        scan(uri)
    }

    // Streams the backup field by field and decodes only what validation reads.
    private suspend fun scan(uri: Uri): Scanned {
        val scanned = Scanned()
        try {
            BackupProtoReader(context).read(uri) { fieldNumber, data ->
                when (fieldNumber) {
                    1 -> parser.decodeFromByteArray(BackupManga.serializer(), data)
                        .tracking.forEach { scanned.trackerIds.add(it.syncId.toLong()) }
                    101 -> scanned.backupSources.add(parser.decodeFromByteArray(BackupSource.serializer(), data))
                    700 -> parser.decodeFromByteArray(BackupNovel.serializer(), data).let { novel ->
                        scanned.novelSources.add(novel.source)
                        novel.tracking.forEach { scanned.trackerIds.add(it.trackerId) }
                    }
                    717 -> parser.decodeFromByteArray(BackupNovelSource.serializer(), data).let {
                        scanned.novelSourceNames[it.sourceId] = it.name
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
        return scanned
    }

    private data class Scanned(
        val backupSources: MutableList<BackupSource> = mutableListOf(),
        // Both content types share one tracker registry, so one id set covers both.
        val trackerIds: MutableSet<Long> = mutableSetOf(),
        val novelSources: MutableSet<String> = mutableSetOf(),
        // Field 717, absent from a backup made before it existed.
        val novelSourceNames: MutableMap<String, String> = mutableMapOf(),
    )
    // RK <--

    data class Results(
        val missingSources: List<String>,
        val missingTrackers: List<String>,
    )
}
