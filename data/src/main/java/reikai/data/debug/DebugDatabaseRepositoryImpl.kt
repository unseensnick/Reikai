package reikai.data.debug

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.debug.DebugDatabaseRepository
import reikai.domain.source.SourceKey
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaMapper
import tachiyomi.domain.manga.model.Manga

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DebugDatabaseRepositoryImpl(
    private val database: Database,
) : DebugDatabaseRepository {

    private val queries get() = database.debugQueries

    override suspend fun getAllManga(): List<Manga> = queries.getAllManga(MangaMapper::mapManga).awaitAsList()

    override suspend fun migrateSource(from: Long, to: Long) {
        val oldKey = SourceKey.Manga(from).serialize()
        val newKey = SourceKey.Manga(to).serialize()
        database.transaction {
            queries.migrateMangaSource(newId = to, oldId = from)
            queries.migrateSavedSearchSource(newKey = newKey, oldKey = oldKey)
            queries.migrateFeedSource(newKey = newKey, oldKey = oldKey)
        }
    }

    override suspend fun resetBrokenReaderFlags() {
        queries.resetBrokenReaderFlags()
    }

    override suspend fun deleteAllSavedSearches() {
        queries.deleteAllSavedSearches()
    }
}
