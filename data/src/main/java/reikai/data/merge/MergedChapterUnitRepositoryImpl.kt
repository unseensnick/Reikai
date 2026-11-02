package reikai.data.merge

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import reikai.data.toDbValue
import reikai.domain.library.ContentType
import reikai.domain.manga.ChapterAggregation
import reikai.domain.merge.ChapterCopyRow
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.DownloadUnitRow
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.MergedChapterUnitRepository.GroupStitch
import reikai.domain.merge.MergedGroupCounts
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergedChapterUnitRepositoryImpl(
    private val database: Database,
) : MergedChapterUnitRepository {

    private val queries = database.merged_chapter_unitQueries

    override suspend fun claimPendingGroups(contentType: ContentType): List<Long> =
        database.transactionWithResult {
            val type = contentType.toDbValue()
            queries.dropSupersededClaims(type)
            queries.claimPending(type)
            queries.claimedGroups(type).awaitAsList()
        }

    override suspend fun releaseClaim(groupId: Long) {
        queries.deleteClaim(groupId)
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
        groupCountsQuery(contentType).subscribeToList().map { it.toMap() }.distinctUntilChanged()

    private fun groupCountsQuery(contentType: ContentType) =
        when (contentType) {
            ContentType.NOVELS -> queries.countsByGroupNovel { groupId, total, read, bookmarked ->
                groupId to MergedGroupCounts(total, read, bookmarked)
            }
            else -> queries.countsByGroup { groupId, total, read, bookmarked ->
                groupId to MergedGroupCounts(total, read, bookmarked)
            }
        }

    override suspend fun getDownloadUnits(
        contentType: ContentType,
        ownerIds: Set<Long>,
    ): Map<Long, List<DownloadUnitRow>> {
        if (ownerIds.isEmpty()) return emptyMap()
        val memberCount = when (contentType) {
            ContentType.NOVELS -> queries.mergedNovelLibraryMemberCount()
            else -> queries.mergedLibraryMemberCount()
        }.awaitAsOne()
        val readAll = ownerIds.size >= memberCount * READ_ALL_SHARE
        return downloadUnitRows(contentType, ownerIds, readAll).groupBy { it.groupId }
    }

    /** [ownerIds]' rows, through the owner index or by reading every row and keeping theirs. */
    internal suspend fun downloadUnitRows(
        contentType: ContentType,
        ownerIds: Set<Long>,
        readAll: Boolean,
    ): List<DownloadUnitRow> =
        if (readAll) {
            downloadUnitsQuery(contentType, null).awaitAsList().filter { it.ownerId in ownerIds }
        } else {
            ownerIds.chunked(MAX_SQL_IDS_PER_QUERY).flatMap { downloadUnitsQuery(contentType, it).awaitAsList() }
        }

    private fun downloadUnitsQuery(contentType: ContentType, ownerIds: Collection<Long>?) =
        when (contentType) {
            ContentType.NOVELS -> when (ownerIds) {
                null -> queries.downloadUnitsByGroupNovel(::novelDownloadUnit)
                else -> queries.downloadUnitsOfNovelOwners(ownerIds, ::novelDownloadUnit)
            }
            else -> when (ownerIds) {
                null -> queries.downloadUnitsByGroup(::mangaDownloadUnit)
                else -> queries.downloadUnitsOfOwners(ownerIds, ::mangaDownloadUnit)
            }
        }

    private fun mangaDownloadUnit(
        groupId: Long,
        unit: Long?,
        ownerId: Long,
        name: String,
        scanlator: String?,
        url: String,
    ) =
        DownloadUnitRow(groupId, unit!!.toInt(), ownerId, name, scanlator, url)

    private fun novelDownloadUnit(groupId: Long, unit: Long?, ownerId: Long, name: String, url: String) =
        DownloadUnitRow(groupId, unit!!.toInt(), ownerId, name, scanlator = null, chapterUrl = url)

    // Not deduplicated: a restitch that only re-pairs chapters leaves every count as it was, and this is
    // what tells the library its download badge needs probing again.
    override fun getStitchWritesAsFlow(): Flow<Unit> =
        queries.storedRankings().asFlow().pausedWhileUnsubscribed().map { }

    override fun getCopiesAsFlow(
        contentType: ContentType,
        chapterIds: Collection<Long>,
    ): Flow<Map<Long, List<ChapterCopyRow>>> =
        when (contentType) {
            ContentType.NOVELS -> queries.copiesOfNovelChapters(chapterIds) {
                    namedId,
                    copyId,
                    unit,
                    copyOrder,
                    _,
                    ownerTitle,
                    ownerSource,
                    name,
                    url,
                ->
                ChapterCopyRow(
                    namedId,
                    ChapterUnit(copyId, unit!!.toInt(), copyOrder.toInt()),
                    ownerTitle,
                    ownerSource,
                    name,
                    scanlator = null,
                    chapterUrl = url,
                )
            }
            else -> queries.copiesOfChapters(chapterIds) {
                    namedId,
                    copyId,
                    unit,
                    copyOrder,
                    _,
                    ownerTitle,
                    ownerSource,
                    name,
                    scanlator,
                    url,
                ->
                ChapterCopyRow(
                    namedId,
                    ChapterUnit(copyId, unit!!.toInt(), copyOrder.toInt()),
                    ownerTitle,
                    ownerSource.toString(),
                    name,
                    scanlator,
                    url,
                )
            }
        }.subscribeToList().map { rows -> rows.groupBy { it.namedId } }

    override fun getRecognizedChapterCountsAsFlow(): Flow<Map<Long, Long>> =
        combine(
            queries.savedRecognizedCounts().subscribeToList(),
            queries.unsavedRecognizedChapterNumbers().subscribeToList(),
        ) { saved, unsaved ->
            saved.associate { it.mangaId to it.recognizedCount } +
                unsaved.groupBy({ it.mangaId }, { it.chapterNumber })
                    .mapValues { (_, numbers) -> ChapterAggregation.distinctChapterNumberCount(numbers).toLong() }
        }.distinctUntilChanged()

    override suspend fun replaceGroup(
        contentType: ContentType,
        groupId: Long,
        stitch: GroupStitch,
        ranking: String?,
    ) {
        val novels = contentType == ContentType.NOVELS
        database.transaction {
            if (novels) queries.deleteNovelGroup(groupId) else queries.deleteGroup(groupId)
            if (!novels) {
                queries.deleteMemberCounts(groupId)
                stitch.recognizedCounts.forEach { (mangaId, count) ->
                    queries.insertMemberCount(mangaId, groupId, count)
                }
            }
            stitch.units.forEach {
                val unit = it.unit?.toLong()
                val copyOrder = it.copyOrder.toLong()
                if (novels) {
                    queries.insertNovel(
                        it.chapterId,
                        groupId,
                        unit,
                        copyOrder,
                        it.chapterName,
                        it.chapterNumber,
                        it.sourceOrder,
                    )
                } else {
                    queries.insert(it.chapterId, groupId, unit, copyOrder, it.chapterNumber, it.sourceOrder)
                }
            }
            when {
                ranking == null -> queries.deleteRanking(groupId)
                novels -> queries.insertNovelRanking(groupId, ranking)
                else -> queries.insertRanking(groupId, ranking)
            }
        }
    }

    private companion object {
        // The owners' share of the grouped library members at which reading every row is the cheaper
        // read, measured with each member's chapter rows interleaved, as years of updates leave them
        // (rows stored in one block favour the narrow read further). See merged-series.md.
        const val READ_ALL_SHARE = 0.3
    }
}

/** How many ids one IN list binds, under SQLite's 999-variable limit on older Android versions. */
const val MAX_SQL_IDS_PER_QUERY = 500
