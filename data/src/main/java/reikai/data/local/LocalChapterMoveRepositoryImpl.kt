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
import reikai.domain.local.LocalGroups
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeOwner
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
    private val ownerQueries = database.local_volume_ownerQueries

    override suspend fun forgetVolumes(
        type: ContentType,
        entryId: Long,
        series: String,
        volumes: Map<String, String>,
        owners: List<VolumeOwner>,
    ) {
        val dbType = type.toDbValue()
        database.transaction {
            writeOwners(dbType, owners, emptyList())
            volumes.keys.forEach { key ->
                queries.forgetVolume(dbType, series, key)
                queries.forgetVolumePick(dbType, entryId, key)
            }
            tombstones.forget(type, entryId, volumes.values.toList())
        }
    }

    override suspend fun regroup(
        type: ContentType,
        series: Map<String, Long>,
        remove: List<VolumeOwner>,
        add: List<VolumeOwner>,
        volumes: List<VolumeRowMove>,
        unitOf: (String) -> Pair<String, String>?,
    ) {
        val dbType = type.toDbValue()
        database.transaction {
            writeOwners(dbType, remove, add)
            // Each chapter goes where the table now says its volume lists, so a combine and its Undo move exactly
            // what changed hands, in any order, and a chapter the series held all along stays.
            val groups = LocalGroups(
                ownerQueries.getAll(dbType) { folder, volume, owner -> VolumeOwner(folder, volume, owner) }
                    .awaitAsList(),
            )
            val moves = series.values.flatMap { id ->
                (chapterUrls(type, id) + tombstones.byOwner(type, id).map { it.url }).distinct().mapNotNull { url ->
                    val (folder, volume) = unitOf(url) ?: return@mapNotNull null
                    series[groups.ownerOf(folder, volume)]?.takeIf { it != id }?.let { Triple(id, it, url) }
                }
            }
            moves.forEach { (from, to, url) -> moveChapter(type, from, to, ChapterMove(url, url)) }
            val arrived = moves.groupBy({ it.second }, { it.third })
            volumes.forEach {
                queries.forgetVolume(dbType, it.toSeries, it.toKey)
                queries.moveVolume(it.toSeries, it.toKey, it.position, dbType, it.fromSeries, it.fromKey)
            }
            arrived.forEach { (owner, urls) -> tombstones.settle(type, owner, urls) }
        }
    }

    override suspend fun organize(
        type: ContentType,
        rename: SeriesRename,
        remove: List<VolumeOwner>,
        add: List<VolumeOwner>,
        inTransaction: suspend () -> Unit,
        movedUrl: (String) -> String,
    ) {
        database.transaction {
            writeOwners(type.toDbValue(), remove, add)
            renameRows(type, rename, movedUrl)
            inTransaction()
        }
    }

    private suspend fun writeOwners(dbType: Long, remove: List<VolumeOwner>, add: List<VolumeOwner>) {
        remove.forEach { ownerQueries.forget(dbType, it.folder, it.volume) }
        add.forEach { ownerQueries.setOwner(dbType, it.folder, it.volume, it.owner) }
    }

    override suspend fun rename(
        type: ContentType,
        rename: SeriesRename,
        journalId: Long?,
        movedUrl: (String) -> String,
    ) {
        database.transaction {
            journalId?.let { database.local_file_journalQueries.delete(it) }
            renameRows(type, rename, movedUrl)
        }
    }

    private suspend fun renameRows(type: ContentType, rename: SeriesRename, movedUrl: (String) -> String) {
        val dbType = type.toDbValue()
        setAddress(type, rename.id, rename.toSeries)
        ownerQueries.renameOwner(rename.toSeries, dbType, rename.fromSeries)
        // A kept state's address follows the rename as a row's does, so a chapter that comes back finds it.
        val urls = chapterUrls(type, rename.id) + tombstones.byOwner(type, rename.id).map { it.url }
        val moves = urls.distinct().map { ChapterMove(it, movedUrl(it)) }.filter { it.fromUrl != it.toUrl }
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
