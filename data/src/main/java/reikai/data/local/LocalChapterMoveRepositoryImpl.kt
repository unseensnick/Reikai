package reikai.data.local

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.data.toDbValue
import reikai.domain.library.ContentType
import reikai.domain.local.ChapterMove
import reikai.domain.local.LocalChapterMoveRepository
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

    override suspend fun move(type: ContentType, fromOwner: Long, toOwner: Long, moves: List<ChapterMove>) {
        database.transaction {
            moves.forEach { moveChapter(type, fromOwner, toOwner, it) }
        }
    }

    override suspend fun moveVolumes(type: ContentType, moves: List<VolumeRowMove>) {
        if (moves.isEmpty()) return
        val dbType = type.toDbValue()
        database.transaction {
            moves.forEach { queries.moveVolume(it.toSeries, it.toKey, it.position, dbType, it.fromSeries, it.fromKey) }
        }
    }

    override suspend fun rename(type: ContentType, rename: SeriesRename, chapters: List<ChapterMove>) {
        val dbType = type.toDbValue()
        database.transaction {
            when (type) {
                ContentType.NOVELS -> queries.renameNovel(rename.toSeries, rename.id)
                else -> queries.renameManga(rename.toSeries, rename.id)
            }
            chapters.forEach { moveChapter(type, rename.id, rename.id, it) }
            rename.volumes.forEach { (from, to) ->
                queries.renameVolume(to, dbType, rename.fromSeries, from)
                queries.renameVolumePick(to, dbType, rename.id, from)
            }
            queries.renameVolumeSeries(rename.toSeries, dbType, rename.fromSeries)
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
