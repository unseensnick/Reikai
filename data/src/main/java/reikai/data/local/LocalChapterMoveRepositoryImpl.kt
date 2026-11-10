package reikai.data.local

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.data.toDbValue
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
) : LocalChapterMoveRepository {

    private val queries = database.local_moveQueries

    override suspend fun move(
        type: ContentType,
        address: SeriesAddress?,
        chapters: List<OwnerMoves>,
        volumes: List<VolumeRowMove>,
    ) {
        val dbType = type.toDbValue()
        database.transaction {
            address?.let { setAddress(type, it.id, it.url) }
            chapters.forEach { owner -> owner.moves.forEach { moveChapter(type, owner.fromOwner, owner.toOwner, it) } }
            volumes.forEach {
                queries.forgetVolume(dbType, it.toSeries, it.toKey)
                queries.moveVolume(it.toSeries, it.toKey, it.position, dbType, it.fromSeries, it.fromKey)
            }
        }
    }

    override suspend fun forgetVolumes(type: ContentType, entryId: Long, series: String, keys: List<String>) {
        val dbType = type.toDbValue()
        database.transaction {
            keys.forEach {
                queries.forgetVolume(dbType, series, it)
                queries.forgetVolumePick(dbType, entryId, it)
            }
        }
    }

    override suspend fun rename(type: ContentType, rename: SeriesRename, chapters: List<ChapterMove>) {
        val dbType = type.toDbValue()
        database.transaction {
            setAddress(type, rename.id, rename.toSeries)
            chapters.forEach { moveChapter(type, rename.id, rename.id, it) }
            rename.volumes.forEach { (from, to) ->
                if (to != from) queries.forgetVolume(dbType, rename.fromSeries, to)
                queries.renameVolume(to, dbType, rename.fromSeries, from)
                queries.renameVolumePick(to, dbType, rename.id, from)
            }
            if (rename.toSeries != rename.fromSeries) queries.forgetSeriesVolumes(dbType, rename.toSeries)
            queries.renameVolumeSeries(rename.toSeries, dbType, rename.fromSeries)
        }
    }

    private suspend fun setAddress(type: ContentType, id: Long, url: String) {
        when (type) {
            ContentType.NOVELS -> queries.renameNovel(url, id)
            else -> queries.renameManga(url, id)
        }
    }

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
    }
}
