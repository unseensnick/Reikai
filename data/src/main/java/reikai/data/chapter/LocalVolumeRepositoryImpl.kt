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
import reikai.data.HeldRows
import reikai.data.toContentType
import reikai.data.toDbValue
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType
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
