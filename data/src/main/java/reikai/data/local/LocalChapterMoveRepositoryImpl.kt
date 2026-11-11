package reikai.data.local

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.data.toDbValue
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.library.ContentType
import reikai.domain.local.ChapterMove
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.OwnerMoves
import reikai.domain.local.SeriesAddress
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeRowMove
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalChapterMoveRepositoryImpl(
    private val database: Database,
    private val tombstones: ChapterTombstoneRepository,
) : LocalChapterMoveRepository {

    private val queries = database.local_moveQueries

    override suspend fun move(
        type: ContentType,
        address: SeriesAddress?,
        chapters: List<OwnerMoves>,
        volumes: List<VolumeRowMove>,
        clearing: Map<Long, List<String>>,
    ) {
        val dbType = type.toDbValue()
        database.transaction {
            // Before the moves, so a kept state arriving with its chapter is never among what is cleared.
            clearing.forEach { (owner, prefixes) -> tombstones.forget(type, owner, prefixes) }
            address?.let { setAddress(type, it.id, it.url) }
            chapters.forEach { owner -> owner.moves.forEach { moveChapter(type, owner.fromOwner, owner.toOwner, it) } }
            volumes.forEach {
                queries.forgetVolume(dbType, it.toSeries, it.toKey)
                queries.moveVolume(it.toSeries, it.toKey, it.position, dbType, it.fromSeries, it.fromKey)
            }
            chapters.groupBy({ it.toOwner }, { it.moves }).forEach { (owner, moves) ->
                tombstones.settle(type, owner, moves.flatten().map { it.toUrl })
            }
        }
    }

    override suspend fun forgetVolumes(type: ContentType, entryId: Long, series: String, volumes: Map<String, String>) {
        val dbType = type.toDbValue()
        database.transaction {
            volumes.keys.forEach { key ->
                queries.forgetVolume(dbType, series, key)
                queries.forgetVolumePick(dbType, entryId, key)
            }
            tombstones.forget(type, entryId, volumes.values.toList())
        }
    }

    override suspend fun rename(
        type: ContentType,
        rename: SeriesRename,
        journalId: Long?,
        movedUrl: (String) -> String,
    ) {
        val dbType = type.toDbValue()
        database.transaction {
            journalId?.let { database.local_file_journalQueries.delete(it) }
            setAddress(type, rename.id, rename.toSeries)
            // A kept state's address follows the rename as a row's does, so a chapter that comes back finds it.
            val urls = chapterUrls(type, rename.id) + tombstones.byOwner(type, rename.id).map { it.url }
            val moves = urls.map { ChapterMove(it, movedUrl(it)) }.filter { it.fromUrl != it.toUrl }
            moves.forEach { moveChapter(type, rename.id, rename.id, it) }
            rename.volumes.forEach { (from, to) ->
                if (to != from) queries.forgetVolume(dbType, rename.fromSeries, to)
                queries.renameVolume(to, dbType, rename.fromSeries, from)
                queries.renameVolumePick(to, dbType, rename.id, from)
            }
            if (rename.toSeries != rename.fromSeries) queries.forgetSeriesVolumes(dbType, rename.toSeries)
            queries.renameVolumeSeries(rename.toSeries, dbType, rename.fromSeries)
            tombstones.settle(type, rename.id, moves.map { it.toUrl })
        }
    }

    private suspend fun chapterUrls(type: ContentType, id: Long): List<String> = when (type) {
        ContentType.NOVELS -> queries.novelChapterUrls(id)
        else -> queries.mangaChapterUrls(id)
    }.awaitAsList()

    private suspend fun setAddress(type: ContentType, id: Long, url: String) {
        when (type) {
            ContentType.NOVELS -> queries.renameNovel(url, id)
            else -> queries.renameManga(url, id)
        }
    }

    // A tombstone at the address, a chapter with no row, moves with the rows' corrections; one landing
    // where a row already is folds onto that row in the settle that ends the transaction.
    private suspend fun moveChapter(type: ContentType, fromOwner: Long, toOwner: Long, move: ChapterMove) {
        val (fromUrl, toUrl) = move
        when (type) {
            ContentType.NOVELS -> {
                queries.moveNovelChapter(toOwner, toUrl, fromOwner, fromUrl)
                queries.moveNovelNumberOverride(toOwner, toUrl, fromOwner, fromUrl)
                queries.moveNovelCopyMark(toOwner, toUrl, fromOwner, fromUrl)
            }
            else -> {
                queries.moveMangaChapter(toOwner, toUrl, fromOwner, fromUrl)
                queries.moveMangaHistory(toOwner, toUrl)
                queries.moveMangaNumberOverride(toOwner, toUrl, fromOwner, fromUrl)
                queries.moveMangaCopyMark(toOwner, toUrl, fromOwner, fromUrl)
            }
        }
        tombstones.move(type, fromOwner, fromUrl, toOwner, toUrl)
    }
}
