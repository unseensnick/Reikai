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

    // Held from app start, since a local source lists a series without suspending on the database.
    private val byType = queries.getAll { type, series, key, position, label ->
        Triple(type.toContentType(), series, LocalVolume(key, position, label))
    }.asFlow()
        .map { query -> query.awaitAsList().groupBy { it.first } }
        .map { rows -> rows.mapValues { (_, typed) -> typed.groupBy({ it.second }, { it.third }) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val flows = ContentType.entries.associateWith { type ->
        byType.map { it[type].orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    override fun volumes(type: ContentType): StateFlow<Map<String, List<LocalVolume>>> = flows.getValue(type)

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
            volumes.forEach {
                queries.setPosition(type.toDbValue(), series, it.key, it.position)
                queries.setLabel(type.toDbValue(), series, it.key, it.label)
            }
        }
    }
}
