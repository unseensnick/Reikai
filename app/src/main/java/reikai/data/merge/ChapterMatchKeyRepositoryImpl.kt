package reikai.data.merge

import app.cash.sqldelight.async.coroutines.awaitAsList
import reikai.domain.merge.ChapterMatchKeyRepository
import reikai.domain.merge.ChapterMatchKeyRepository.ResolvedKey
import reikai.domain.merge.ChapterMatchKeyRepository.ResolvedNovelKey
import reikai.domain.merge.ChapterMatchKeyRepository.StaleChapter
import reikai.domain.merge.ChapterMatchKeyRepository.StaleNovelChapter
import tachiyomi.data.Database

class ChapterMatchKeyRepositoryImpl(
    private val database: Database,
) : ChapterMatchKeyRepository {

    private val queries = database.chapter_match_keyQueries

    override suspend fun getStaleMangaChapters(): List<StaleChapter> =
        queries.staleMergedChapters(::StaleChapter).awaitAsList()

    override suspend fun getStaleNovelChapters(): List<StaleNovelChapter> =
        queries.staleMergedNovelChapters(::StaleNovelChapter).awaitAsList()

    override suspend fun upsertMangaKeys(keys: List<ResolvedKey>) {
        if (keys.isEmpty()) return
        database.transaction {
            keys.forEach { queries.upsert(it.chapterId, it.matchKey, it.chapterNumber) }
        }
    }

    override suspend fun upsertNovelKeys(keys: List<ResolvedNovelKey>) {
        if (keys.isEmpty()) return
        database.transaction {
            keys.forEach { queries.upsertNovel(it.chapterId, it.matchKey, it.name, it.chapterNumber) }
        }
    }

    override suspend fun getMergedUnreadCounts(): Map<Long, Long> =
        queries.mergedUnreadCounts().awaitAsList().associate { it.groupId to it.unreadCount }

    override suspend fun getMergedUnreadCountsNovel(): Map<Long, Long> =
        queries.mergedUnreadCountsNovel().awaitAsList().associate { it.groupId to it.unreadCount }
}
