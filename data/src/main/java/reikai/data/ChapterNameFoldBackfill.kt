package reikai.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.Inject
import tachiyomi.data.Database

/**
 * Stores [chapterNameFold] on every chapter row of both types that predates the column. Reads only names holding a
 * non-ASCII char and writes in batches, so it can run beside a sync; running it again finds nothing left to do.
 */
@Inject
class ChapterNameFoldBackfill(private val database: Database) {

    suspend fun run() {
        val queries = database.chapter_name_foldQueries
        queries.mangaCandidates().awaitAsList()
            .mapNotNull { row -> chapterNameFold(row.remote_name)?.let { Triple(row.id, row.remote_name, it) } }
            .chunked(BATCH)
            .forEach { batch ->
                database.transaction { batch.forEach { (id, name, fold) -> queries.setMangaFold(fold, id, name) } }
            }
        queries.novelCandidates().awaitAsList()
            .mapNotNull { row -> chapterNameFold(row.name)?.let { Triple(row._id, row.name, it) } }
            .chunked(BATCH)
            .forEach { batch ->
                database.transaction { batch.forEach { (id, name, fold) -> queries.setNovelFold(fold, id, name) } }
            }
    }

    private companion object {
        const val BATCH = 500
    }
}
