package reikai.data.chapter

import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import mihon.core.metro.AppCoroutineScope
import reikai.data.HeldRows
import reikai.domain.chapter.ChapterCopyMark
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.CopyMarkReport
import reikai.domain.library.StoredType
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterCopyMarkRepositoryImpl(
    private val database: Database,
    @AppCoroutineScope scope: CoroutineScope,
) : ChapterCopyMarkRepository {

    private val queries = database.chapter_copy_markQueries

    // Held so a reader can read the marks on a page turn without suspending.
    private val manga = HeldRows(marksOf(queries.getMangaMarks(::row)), scope, emptyMap())
    private val novels = HeldRows(marksOf(queries.getNovelMarks(::row)), scope, emptyMap())

    private fun marksOf(query: Query<ChapterCopyMark>) = query.asFlow().map { byOwner(it.awaitAsList()) }

    private fun of(type: StoredType) = if (type == StoredType.NOVELS) novels else manga

    override fun marks(type: StoredType): StateFlow<Map<Long, Map<String, CopyMark>>> = of(type).held

    override suspend fun loadedMarks(type: StoredType) = of(type).loaded()

    override suspend fun loadedMarks(type: StoredType, ownerId: Long): Map<String, CopyMark> =
        when (type) {
            StoredType.NOVELS -> queries.getNovelMarksOf(ownerId, ::row)
            StoredType.MANGA -> queries.getMangaMarksOf(ownerId, ::row)
        }.awaitAsList().associate { it.url to it.mark }

    override suspend fun set(type: StoredType, marks: List<ChapterCopyMark>) {
        database.transaction {
            marks.forEach {
                val group = (it.mark as? CopyMark.Group)?.key
                when (type) {
                    StoredType.NOVELS -> queries.upsertNovel(it.ownerId, it.url, group)
                    StoredType.MANGA -> queries.upsertManga(it.ownerId, it.url, group)
                }
            }
        }
    }

    override suspend fun clear(type: StoredType, ownerId: Long, urls: List<String>) {
        database.transaction {
            urls.forEach {
                when (type) {
                    StoredType.NOVELS -> queries.deleteNovel(ownerId, it)
                    StoredType.MANGA -> queries.deleteManga(ownerId, it)
                }
            }
        }
    }

    override suspend fun report(type: StoredType): List<CopyMarkReport> {
        return when (type) {
            StoredType.NOVELS -> queries.reportNovel { title, source, url, name, number, scanlator, group ->
                CopyMarkReport(title, source, url, name, number, scanlator, markOf(group))
            }
            StoredType.MANGA -> queries.reportManga { title, source, url, name, number, scanlator, group ->
                CopyMarkReport(title, source.toString(), url, name, number, scanlator, markOf(group))
            }
        }.awaitAsList()
    }

    private fun row(ownerId: Long, url: String, group: Long?) = ChapterCopyMark(ownerId, url, markOf(group))

    private fun markOf(group: Long?): CopyMark = group?.let(CopyMark::Group) ?: CopyMark.NeverSkip

    private fun byOwner(rows: List<ChapterCopyMark>) =
        rows.groupBy { it.ownerId }.mapValues { (_, owned) -> owned.associate { it.url to it.mark } }
}
