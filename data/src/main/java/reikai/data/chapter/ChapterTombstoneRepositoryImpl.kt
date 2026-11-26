package reikai.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.chapter.ChapterTombstone
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.Revival
import reikai.domain.chapter.TombstoneSession
import reikai.domain.library.StoredType
import reikai.domain.readinglog.isLoggableSession
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

    override suspend fun byOwner(type: StoredType, ownerId: Long): List<String> = when (type) {
        StoredType.NOVELS -> queries.novelByOwner(ownerId)
        else -> queries.mangaByOwner(ownerId)
    }.awaitAsList()

    override suspend fun getByOwners(type: StoredType, ownerIds: List<Long>): Map<Long, List<ChapterTombstone>> {
        if (ownerIds.isEmpty()) return emptyMap()
        val sessions = HashMap<Pair<Long, String>, MutableList<TombstoneSession>>()
        val session = { owner: Long, url: String, endedAt: Long, duration: Long ->
            sessions.getOrPut(owner to url) { mutableListOf() } += TombstoneSession(endedAt, duration)
        }
        if (type == StoredType.NOVELS) {
            queries.novelSessionsOfOwners(ownerIds, session).awaitAsList()
        } else {
            queries.mangaSessionsOfOwners(ownerIds, session).awaitAsList()
        }
        val rows = if (type == StoredType.NOVELS) {
            queries.novelOfOwners(ownerIds) {
                    owner,
                    url,
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
                    url, read != 0L, bookmark != 0L, progress, words, listened,
                    Instant.fromEpochMilliseconds(fetch), readAt?.let(Instant::fromEpochMilliseconds), time,
                    sessions[owner to url].orEmpty(), removedAt,
                )
            }
        } else {
            queries.mangaOfOwners(ownerIds) {
                    owner,
                    url,
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
                    url, read != 0L, bookmark != 0L, page, pages, null,
                    Instant.fromEpochMilliseconds(fetch), readAt?.let(Instant::fromEpochMilliseconds), time,
                    sessions[owner to url].orEmpty(), removedAt,
                )
            }
        }.awaitAsList()
        return rows.groupBy({ it.first }, { it.second })
    }

    override suspend fun <T> capturing(
        type: StoredType,
        ownerId: Long,
        removedIds: List<Long>,
        revivals: List<Revival>,
        write: suspend () -> T,
    ): T = database.transactionWithResult {
        if (removedIds.isNotEmpty()) {
            val now = Clock.System.now().toEpochMilliseconds()
            if (type == StoredType.NOVELS) {
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

    override suspend fun move(type: StoredType, fromOwner: Long, fromUrl: String, toOwner: Long, toUrl: String) {
        if (fromOwner == toOwner && fromUrl == toUrl) return
        if (type == StoredType.NOVELS) {
            queries.moveNovel(toOwner, toUrl, fromOwner, fromUrl)
            queries.moveNovelSessions(toOwner, toUrl, fromOwner, fromUrl)
        } else {
            queries.moveManga(toOwner, toUrl, fromOwner, fromUrl)
            queries.moveMangaSessions(toOwner, toUrl, fromOwner, fromUrl)
        }
        consume(type, fromOwner, fromUrl)
    }

    override suspend fun settle(type: StoredType, ownerId: Long, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val settled = if (type == StoredType.NOVELS) {
            queries.novelSettled(ownerId, urls)
        } else {
            queries.mangaSettled(ownerId, urls)
        }
        settled.awaitAsList().forEach { apply(type, ownerId, it, it) }
    }

    // One transaction: a tombstone dropped without its sessions would hand them to the next chapter at its url.
    override suspend fun forget(type: StoredType, ownerId: Long, urlPrefixes: List<String>, urls: List<String>) {
        database.transaction {
            urlPrefixes.forEach { prefix ->
                if (type == StoredType.NOVELS) {
                    queries.forgetNovel(ownerId, prefix)
                    queries.forgetNovelSessions(ownerId, prefix)
                } else {
                    queries.forgetManga(ownerId, prefix)
                    queries.forgetMangaSessions(ownerId, prefix)
                }
            }
            urls.forEach { consume(type, ownerId, it) }
        }
    }

    override suspend fun restore(
        type: StoredType,
        ownerId: Long,
        tombstones: List<ChapterTombstone>,
        chapterUrls: Collection<String>,
    ) {
        database.transaction {
            tombstones.forEach { fold(type, ownerId, it) }
            settle(type, ownerId, tombstones.map { it.url } + chapterUrls)
        }
    }

    private suspend fun fold(type: StoredType, ownerId: Long, tombstone: ChapterTombstone) = with(tombstone) {
        val fetch = dateFetch.toEpochMilliseconds()
        // From a file, so held to the reading log's own check, as every other restored session is.
        val sessions = sessions.filter { isLoggableSession(Instant.fromEpochMilliseconds(it.endedAt), it.duration) }
        val readAt = historyReadAt?.toEpochMilliseconds()
        if (type == StoredType.NOVELS) {
            queries.foldNovel(
                ownerId, url, read.toLong(), bookmark.toLong(), progress, length, listenedMs, fetch,
                readAt, historyDuration, removedAt,
            )
            sessions.forEach { queries.foldNovelSession(ownerId, url, it.endedAt, it.duration) }
        } else {
            queries.foldManga(
                ownerId, url, read.toLong(), bookmark.toLong(), progress, length ?: 0L, fetch, readAt,
                historyDuration, removedAt,
            )
            sessions.forEach { queries.foldMangaSession(ownerId, url, it.endedAt, it.duration) }
        }
    }

    /** The tombstone at [tombstoneUrl] onto the row at [url], then gone. */
    private suspend fun apply(type: StoredType, ownerId: Long, url: String, tombstoneUrl: String) {
        if (type == StoredType.NOVELS) {
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

    private suspend fun consume(type: StoredType, ownerId: Long, url: String) {
        if (type == StoredType.NOVELS) {
            queries.consumeNovelSessions(ownerId, url)
            queries.consumeNovel(ownerId, url)
        } else {
            queries.consumeMangaSessions(ownerId, url)
            queries.consumeManga(ownerId, url)
        }
    }
}
