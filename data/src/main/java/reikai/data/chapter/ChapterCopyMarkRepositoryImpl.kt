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
import reikai.domain.chapter.ChapterCopyMark
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.CopyMarkReport
import reikai.domain.library.ContentType
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterCopyMarkRepositoryImpl(
    private val database: Database,
    @AppCoroutineScope scope: CoroutineScope,
) : ChapterCopyMarkRepository {

    private val queries = database.chapter_copy_markQueries

    // Held from app start so a reader can read the marks on a page turn without suspending.
    private val manga = queries.getMangaMarks(::row).asFlow().map { byOwner(it.awaitAsList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val novels = queries.getNovelMarks(::row).asFlow().map { byOwner(it.awaitAsList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun marks(type: ContentType): StateFlow<Map<Long, Map<String, CopyMark>>> =
        if (type == ContentType.NOVELS) novels else manga

    override suspend fun set(type: ContentType, marks: List<ChapterCopyMark>) {
        database.transaction {
            marks.forEach {
                val group = (it.mark as? CopyMark.Group)?.key
                when (type) {
                    ContentType.NOVELS -> queries.upsertNovel(it.ownerId, it.url, group)
                    else -> queries.upsertManga(it.ownerId, it.url, group)
                }
            }
        }
    }

    override suspend fun clear(type: ContentType, ownerId: Long, urls: List<String>) {
        database.transaction {
            urls.forEach {
                when (type) {
                    ContentType.NOVELS -> queries.deleteNovel(ownerId, it)
                    else -> queries.deleteManga(ownerId, it)
                }
            }
        }
    }

    override suspend fun report(type: ContentType): List<CopyMarkReport> {
        return when (type) {
            ContentType.NOVELS -> queries.reportNovel { title, source, url, name, number, scanlator, group ->
                CopyMarkReport(title, source, url, name, number, scanlator, markOf(group))
            }
            else -> queries.reportManga { title, source, url, name, number, scanlator, group ->
                CopyMarkReport(title, source.toString(), url, name, number, scanlator, markOf(group))
            }
        }.awaitAsList()
    }

    private fun row(ownerId: Long, url: String, group: Long?) = ChapterCopyMark(ownerId, url, markOf(group))

    private fun markOf(group: Long?): CopyMark = group?.let(CopyMark::Group) ?: CopyMark.NeverSkip

    private fun byOwner(rows: List<ChapterCopyMark>) =
        rows.groupBy { it.ownerId }.mapValues { (_, owned) -> owned.associate { it.url to it.mark } }
}
