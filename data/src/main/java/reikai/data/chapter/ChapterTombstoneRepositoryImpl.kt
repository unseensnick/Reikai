package reikai.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.chapter.ChapterTombstone
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.Revival
import reikai.domain.chapter.SyncedChapter
import reikai.domain.chapter.TombstoneSession
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.Database
import kotlin.time.Clock
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterTombstoneRepositoryImpl(
    private val database: Database,
) : ChapterTombstoneRepository {

    private val queries = database.chapter_tombstoneQueries

    override suspend fun byOwner(type: ContentType, ownerId: Long): List<SyncedChapter> {
        val row = { url: String, number: Double -> SyncedChapter(-1L, url, number) }
        return when (type) {
            ContentType.NOVELS -> queries.novelByOwner(ownerId, row)
            else -> queries.mangaByOwner(ownerId, row)
        }.awaitAsList()
    }

    override suspend fun getByOwners(type: ContentType, ownerIds: List<Long>): Map<Long, List<ChapterTombstone>> {
        if (ownerIds.isEmpty()) return emptyMap()
        val sessions = HashMap<Pair<Long, String>, MutableList<TombstoneSession>>()
        val session = { owner: Long, url: String, endedAt: Long, duration: Long ->
            sessions.getOrPut(owner to url) { mutableListOf() } += TombstoneSession(endedAt, duration)
        }
        if (type == ContentType.NOVELS) {
            queries.novelSessionsOfOwners(ownerIds, session).awaitAsList()
        } else {
            queries.mangaSessionsOfOwners(ownerIds, session).awaitAsList()
        }
        val rows = if (type == ContentType.NOVELS) {
            queries.novelOfOwners(ownerIds) {
                    owner,
                    url,
                    number,
                    read,
                    bookmark,
                    progress,
                    words,
                    listened,
                    fetch,
                    readAt,
                    time,
                    removedAt,
                ->
                owner to ChapterTombstone(
                    url, number, read != 0L, bookmark != 0L, progress, words, listened,
                    Instant.fromEpochMilliseconds(fetch), readAt?.let(Instant::fromEpochMilliseconds), time,
                    sessions[owner to url].orEmpty(), removedAt,
                )
            }
        } else {
            queries.mangaOfOwners(ownerIds) {
                    owner,
                    url,
                    number,
                    read,
                    bookmark,
                    page,
                    pages,
                    fetch,
                    readAt,
                    time,
                    removedAt,
                ->
                owner to ChapterTombstone(
                    url, number, read != 0L, bookmark != 0L, page, pages, null,
                    Instant.fromEpochMilliseconds(fetch), readAt?.let(Instant::fromEpochMilliseconds), time,
                    sessions[owner to url].orEmpty(), removedAt,
                )
            }
        }.awaitAsList()
        return rows.groupBy({ it.first }, { it.second })
    }

    override suspend fun <T> capturing(
        type: ContentType,
        ownerId: Long,
        removedIds: List<Long>,
        revivals: List<Revival>,
        write: suspend () -> T,
    ): T = database.transactionWithResult {
        if (removedIds.isNotEmpty()) {
            val now = Clock.System.now().toEpochMilliseconds()
            if (type == ContentType.NOVELS) {
                queries.captureNovel(now, removedIds)
                queries.captureNovelSessions(removedIds)
            } else {
                queries.captureManga(now, removedIds)
                queries.captureMangaSessions(removedIds)
            }
        }
        val result = write()
        revivals.forEach { apply(type, ownerId, it.url, it.tombstoneUrl) }
        result
    }

    override suspend fun move(type: ContentType, fromOwner: Long, fromUrl: String, toOwner: Long, toUrl: String) {
        if (fromOwner == toOwner && fromUrl == toUrl) return
        if (type == ContentType.NOVELS) {
            queries.moveNovel(toOwner, toUrl, fromOwner, fromUrl)
            queries.moveNovelSessions(toOwner, toUrl, fromOwner, fromUrl)
        } else {
            queries.moveManga(toOwner, toUrl, fromOwner, fromUrl)
            queries.moveMangaSessions(toOwner, toUrl, fromOwner, fromUrl)
        }
        consume(type, fromOwner, fromUrl)
    }

    override suspend fun settle(type: ContentType, ownerId: Long) {
        val urls = if (type == ContentType.NOVELS) queries.novelSettled(ownerId) else queries.mangaSettled(ownerId)
        urls.awaitAsList().forEach { apply(type, ownerId, it, it) }
    }

    override suspend fun forget(type: ContentType, ownerId: Long, urlPrefixes: List<String>) {
        urlPrefixes.forEach { prefix ->
            if (type == ContentType.NOVELS) {
                queries.forgetNovel(ownerId, prefix)
                queries.forgetNovelSessions(ownerId, prefix)
            } else {
                queries.forgetManga(ownerId, prefix)
                queries.forgetMangaSessions(ownerId, prefix)
            }
        }
    }

    override suspend fun restore(type: ContentType, ownerId: Long, tombstones: List<ChapterTombstone>) {
        database.transaction {
            tombstones.forEach { fold(type, ownerId, it) }
            settle(type, ownerId)
        }
    }

    private suspend fun fold(type: ContentType, ownerId: Long, tombstone: ChapterTombstone) = with(tombstone) {
        val fetch = dateFetch.toEpochMilliseconds()
        val readAt = historyReadAt?.toEpochMilliseconds()
        if (type == ContentType.NOVELS) {
            queries.foldNovel(
                ownerId, url, number, read.toLong(), bookmark.toLong(), progress, length, listenedMs, fetch,
                readAt, historyDuration, removedAt,
            )
            sessions.forEach { queries.foldNovelSession(ownerId, url, it.endedAt, it.duration) }
        } else {
            queries.foldManga(
                ownerId, url, number, read.toLong(), bookmark.toLong(), progress, length ?: 0L, fetch, readAt,
                historyDuration, removedAt,
            )
            sessions.forEach { queries.foldMangaSession(ownerId, url, it.endedAt, it.duration) }
        }
    }

    /** The tombstone at [tombstoneUrl] onto the row at [url], then gone. */
    private suspend fun apply(type: ContentType, ownerId: Long, url: String, tombstoneUrl: String) {
        if (type == ContentType.NOVELS) {
            queries.applyNovel(ownerId, tombstoneUrl, url)
            queries.applyNovelHistory(url, ownerId, tombstoneUrl)
            queries.applyNovelWords(url, ownerId, tombstoneUrl)
            queries.applyNovelSessions(url, ownerId, tombstoneUrl)
        } else {
            queries.applyManga(ownerId, tombstoneUrl, url)
            queries.applyMangaHistory(url, ownerId, tombstoneUrl)
            queries.applyMangaSessions(url, ownerId, tombstoneUrl)
        }
        consume(type, ownerId, tombstoneUrl)
    }

    private suspend fun consume(type: ContentType, ownerId: Long, url: String) {
        if (type == ContentType.NOVELS) {
            queries.consumeNovelSessions(ownerId, url)
            queries.consumeNovel(ownerId, url)
        } else {
            queries.consumeMangaSessions(ownerId, url)
            queries.consumeManga(ownerId, url)
        }
    }
}
