package reikai.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.core.metro.AppCoroutineScope
import reikai.data.HeldRows
import reikai.data.toContentType
import reikai.data.toDbValue
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.LooseFiles
import reikai.domain.library.ContentType
import reikai.domain.local.VolumeOwner
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalVolumeRepositoryImpl(
    private val database: Database,
    @AppCoroutineScope scope: CoroutineScope,
) : LocalVolumeRepository {

    private val queries = database.local_volumeQueries

    private val byType = HeldRows(
        queries.getAll { type, series, key, position, label ->
            Triple(type.toContentType(), series, LocalVolume(key, position, label))
        }.asFlow()
            .map { query -> query.awaitAsList().groupBy { it.first } }
            .map { rows -> rows.mapValues { (_, typed) -> typed.groupBy({ it.second }, { it.third }) } },
        scope,
        emptyMap(),
    )

    private val flows = ContentType.entries.associateWith { type ->
        byType.held.map { it[type].orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    override fun volumes(type: ContentType): StateFlow<Map<String, List<LocalVolume>>> = flows.getValue(type)

    override suspend fun loadedVolumes(type: ContentType) = byType.loaded()[type].orEmpty()

    override suspend fun setOrder(type: ContentType, series: String, keys: List<String>) {
        database.transaction {
            keys.forEachIndexed { index, key -> queries.setPosition(type.toDbValue(), series, key, index.toLong()) }
        }
    }

    override suspend fun setLabel(type: ContentType, series: String, key: String, label: String?) {
        queries.setLabel(type.toDbValue(), series, key, label?.trim()?.ifEmpty { null })
    }

    override suspend fun all(type: ContentType): List<VolumeOwner> =
        database.local_volume_ownerQueries.getAll(type.toDbValue()) { folder, volume, owner ->
            VolumeOwner(folder, volume, owner)
        }.awaitAsList()

    private val loose = HeldRows(
        database.local_manga_looseQueries.getAll().asFlow()
            .map { query -> query.awaitAsList().associate { it.series to LooseFiles.of(it.mode) } },
        scope,
        emptyMap(),
    )

    override fun looseFiles(): StateFlow<Map<String, LooseFiles>> = loose.held

    override suspend fun loadedLooseFiles() = loose.loaded()

    override suspend fun setLooseFiles(series: String, mode: LooseFiles) {
        // The default is no row, so a series left as it was carries nothing.
        if (mode == LooseFiles.ONE_VOLUME) {
            database.local_manga_looseQueries.clear(series)
        } else {
            database.local_manga_looseQueries.setMode(series, mode.stored)
        }
    }

    override suspend fun loadedPicks(type: ContentType): Map<Long, String> =
        database.local_volume_pickQueries.getAll { dbType, entryId, key -> Triple(dbType, entryId, key) }.awaitAsList()
            .filter { it.first == type.toDbValue() }.associate { it.second to it.third }

    override suspend fun restorePick(type: ContentType, series: String, key: String) {
        // By the series' address, since the restore gave it a new id.
        val id = when (type) {
            ContentType.NOVELS -> database.local_moveQueries.localNovelId(series)
            else -> database.local_moveQueries.localMangaId(series)
        }.awaitAsOneOrNull() ?: return
        database.local_volume_pickQueries.setIfAbsent(type.toDbValue(), id, key)
    }

    override suspend fun restore(type: ContentType, series: String, volumes: List<LocalVolume>) {
        if (volumes.isEmpty()) return
        database.transaction {
            // Only what the backup holds, so a field it lacks keeps the one set on this device.
            volumes.forEach {
                it.position?.let { position -> queries.setPosition(type.toDbValue(), series, it.key, position) }
                it.label?.let { label -> queries.setLabel(type.toDbValue(), series, it.key, label) }
            }
        }
    }
}
