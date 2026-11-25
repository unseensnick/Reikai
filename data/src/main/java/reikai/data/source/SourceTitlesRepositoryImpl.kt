package reikai.data.source

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.source.SourceTitlesRepository
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceTitlesRepositoryImpl(
    private val database: Database,
) : SourceTitlesRepository {

    private val queries = database.source_titlesQueries

    override suspend fun otherMangaTitles(sourceIds: Collection<Long>, mangaId: Long): List<String> =
        queries.otherMangaTitles(sourceIds, mangaId).awaitAsList()

    override suspend fun otherNovelTitles(sources: Collection<String>, novelId: Long): List<String> =
        queries.otherNovelTitles(sources, novelId).awaitAsList()
}
