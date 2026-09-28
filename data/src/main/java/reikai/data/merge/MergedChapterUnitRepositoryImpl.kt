package reikai.data.merge

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.domain.manga.ChapterAggregation
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.DownloadUnitRow
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import reikai.domain.merge.MergedGroupCounts
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergedChapterUnitRepositoryImpl(
    private val database: Database,
) : MergedChapterUnitRepository {

    private val queries = database.merged_chapter_unitQueries

    override suspend fun getStaleGroups(contentType: ContentType): List<Long> =
        when (contentType) {
            ContentType.NOVELS -> queries.staleMergedNovelGroups().awaitAsList()
            else -> queries.staleMergedGroups().awaitAsList()
        }

    override suspend fun isStale(contentType: ContentType, groupId: Long): Boolean =
        when (contentType) {
            ContentType.NOVELS -> queries.staleMergedNovelGroup(groupId).awaitAsOneOrNull()
            else -> queries.staleMergedGroup(groupId).awaitAsOneOrNull()
        } != null

    override suspend fun getRankings(): Map<Long, String> =
        queries.storedRankings().awaitAsList().associate { it.group_id to it.ranking }

    override suspend fun getStitch(contentType: ContentType, groupId: Long): List<ChapterUnit> =
        when (contentType) {
            ContentType.NOVELS -> queries.stitchOfNovelGroup(groupId) { chapterId, unit, copyOrder ->
                ChapterUnit(chapterId, unit!!.toInt(), copyOrder.toInt())
            }
            else -> queries.stitchOfGroup(groupId) { chapterId, unit, copyOrder ->
                ChapterUnit(chapterId, unit!!.toInt(), copyOrder.toInt())
            }
        }.awaitAsList()

    override suspend fun getGroupCounts(contentType: ContentType): Map<Long, MergedGroupCounts> =
        groupCountsQuery(contentType).awaitAsList().toMap()

    override fun getGroupCountsAsFlow(contentType: ContentType): Flow<Map<Long, MergedGroupCounts>> =
        groupCountsQuery(contentType).subscribeToList().map { it.toMap() }

    private fun groupCountsQuery(contentType: ContentType) =
        when (contentType) {
            ContentType.NOVELS -> queries.countsByGroupNovel { groupId, total, read, bookmarked ->
                groupId to MergedGroupCounts(total, read, bookmarked)
            }
            else -> queries.countsByGroup { groupId, total, read, bookmarked ->
                groupId to MergedGroupCounts(total, read, bookmarked)
            }
        }

    override fun getDownloadUnitsAsFlow(contentType: ContentType): Flow<Map<Long, List<DownloadUnitRow>>> =
        when (contentType) {
            ContentType.NOVELS -> queries.downloadUnitsByGroupNovel { groupId, unit, ownerId, name, url ->
                DownloadUnitRow(groupId, unit!!.toInt(), ownerId, name, scanlator = null, chapterUrl = url)
            }
            else -> queries.downloadUnitsByGroup { groupId, unit, ownerId, name, scanlator, url ->
                DownloadUnitRow(groupId, unit!!.toInt(), ownerId, name, scanlator, url)
            }
        }.subscribeToList().map { rows -> rows.groupBy { it.groupId } }

    override suspend fun getRecognizedChapterCounts(): Map<Long, Long> =
        queries.recognizedChapterNumbersByManga().awaitAsList()
            .groupBy({ it.mangaId }, { it.chapterNumber })
            .mapValues { (_, numbers) -> ChapterAggregation.distinctChapterNumberCount(numbers).toLong() }

    override suspend fun replaceGroup(
        contentType: ContentType,
        groupId: Long,
        units: List<StoredUnit>,
        ranking: String?,
    ) {
        val novels = contentType == ContentType.NOVELS
        database.transaction {
            if (novels) queries.deleteNovelGroup(groupId) else queries.deleteGroup(groupId)
            units.forEach {
                val unit = it.unit?.toLong()
                val copyOrder = it.copyOrder.toLong()
                if (novels) {
                    queries.insertNovel(it.chapterId, groupId, unit, copyOrder, it.chapterName, it.chapterNumber)
                } else {
                    queries.insert(it.chapterId, groupId, unit, copyOrder, it.chapterNumber)
                }
            }
            when {
                ranking == null -> queries.deleteRanking(groupId)
                novels -> queries.insertNovelRanking(groupId, ranking)
                else -> queries.insertRanking(groupId, ranking)
            }
        }
    }
}
