package reikai.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
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
import reikai.data.local.localSeriesId
import reikai.data.toContentType
import reikai.data.toDbValue
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.library.ContentType
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class VolumePickRepositoryImpl(
    private val database: Database,
    @AppCoroutineScope scope: CoroutineScope,
) : VolumePickRepository {

    private val queries = database.local_volume_pickQueries

    // Held from app start so a details screen opens on its volume rather than flashing every volume first.
    private val all = queries.getAll { type, entryId, key -> Triple(type.toContentType(), entryId, key) }.asFlow()
        .map { query ->
            query.awaitAsList().groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val byType = ContentType.entries.associateWith { type ->
        all.map { it[type].orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    override fun picks(type: ContentType): StateFlow<Map<Long, String>> = byType.getValue(type)

    override suspend fun set(type: ContentType, entryId: Long, volumeKey: String?) {
        if (volumeKey == null) {
            queries.clear(type.toDbValue(), entryId)
        } else {
            queries.set(type.toDbValue(), entryId, volumeKey)
        }
    }

    override suspend fun loadedPicks(type: ContentType): Map<Long, String> =
        queries.getAll { dbType, entryId, key -> Triple(dbType, entryId, key) }.awaitAsList()
            .filter { it.first == type.toDbValue() }.associate { it.second to it.third }

    override suspend fun restore(type: ContentType, series: String, key: String) {
        // By the series' address, since the restore gave it a new id.
        val id = database.localSeriesId(type, series) ?: return
        queries.setIfAbsent(type.toDbValue(), id, key)
    }
}
