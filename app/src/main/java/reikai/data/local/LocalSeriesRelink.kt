package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.LocalVolumeOwnerRepository
import reikai.domain.local.SeriesRename
import reikai.domain.local.relinkCandidates
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localVolumeChapter
import reikai.storage.listOrNull
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem

/**
 * A local series renamed outside the app: its address is its folder name, so its refresh fails and its rows
 * sit stranded. [candidates] finds the folders in the local root that hold every chapter file the series'
 * rows name, for the user to pick from, never to be picked for them; [relink] then moves the address and
 * every row keyed by it the way Rename files does, under the lock on both names. A loose novel book carries
 * nothing but the name that changed, so it has no candidates. Both content types.
 */
@Inject
class LocalSeriesRelink(
    private val context: Context,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val localFiles: LocalSourceFileSystem,
    private val storageManager: StorageManager,
    private val chapterMoves: LocalChapterMoveRepository,
    private val seriesLocks: LocalSeriesLocks,
    private val owners: LocalVolumeOwnerRepository,
) {

    /**
     * The folders series [id] may have been renamed to, or null while its folder is where its address says. Asked
     * from a refresh's failure handler, so a local folder that cannot be listed whole offers nothing and throws
     * nothing; whether the folder is missing is read from that trusted listing, never a document's own query,
     * which a provider hiccup answers "absent" to.
     */
    suspend fun candidates(type: ContentType, id: Long): List<String>? = withIOContext {
        val series = address(type, id) ?: return@withIOContext emptyList()
        val listed = root(type)?.listOrNull(context) ?: return@withIOContext emptyList()
        if (listed.any { it.name == series }) return@withIOContext null
        candidatesIn(type, id, series, listed)
    }

    /**
     * Links series [id] to [folder]: its address, chapter rows and everything keyed by them in one transaction.
     * Checked again under the lock that the old folder is still gone and [folder] still holds the files, since
     * the user may have put things back between the question and the answer.
     */
    suspend fun relink(type: ContentType, id: Long, folder: String): LocalMoveResult = withIOContext {
        val series = address(type, id)
            ?: return@withIOContext LocalMoveResult.Failed(IllegalStateException("No such series"))
        seriesLocks.withSeries(type, listOf(series, folder)) {
            val listed = root(type)?.listOrNull(context)
                ?: return@withSeries LocalMoveResult.Failed(IllegalStateException("The local folder cannot be read"))
            if (listed.any { it.name == series }) {
                return@withSeries LocalMoveResult.Failed(IllegalStateException("$series is back in the local folder"))
            }
            if (folder !in candidatesIn(type, id, series, listed)) {
                return@withSeries LocalMoveResult.Failed(IllegalStateException("$folder does not hold $series' files"))
            }
            // Another series addresses the volumes it lists from this folder by the old name, which a link here
            // would leave behind.
            if (owners.all(type).any { it.folder == series && it.owner != series }) {
                return@withSeries LocalMoveResult.Failed(IllegalStateException("Another series lists $series' volumes"))
            }
            chapterMoves.renameSeries(type, SeriesRename(id, series, folder, emptyMap()))
            LocalMoveResult.Moved(emptyList())
        }
    }

    /** The candidates among [listed], the local root's trusted listing; a folder that cannot be listed whole is none. */
    private suspend fun candidatesIn(type: ContentType, id: Long, series: String, listed: List<UniFile>): List<String> {
        // Only the chapters in the series' own folder: a group's others sit in folders that did not move.
        val paths = chapterUrls(type, id).filter { chapterVolumeUnit(type, it)?.first == series }
            .mapNotNull { relativePath(type, series, it) }
        if (paths.isEmpty()) return emptyList()
        val volumes = paths.mapNotNullTo(HashSet()) { it.substringBefore('/', "").lowercase().ifEmpty { null } }
        val folders = listed
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .mapNotNull { folder -> contents(folder, volumes)?.let { folder.name.orEmpty() to it } }
            .toMap()
        return relinkCandidates(paths, folders).filter {
            heldBy(type, it).let { owner -> owner == null || owner == id }
        }
    }

    /** What [dir] holds, relative to it; only the subfolders named in [volumes] are read, one level down. */
    private fun contents(dir: UniFile, volumes: Set<String>): List<String>? {
        val entries = dir.listOrNull(context) ?: return null
        return entries.flatMap { entry ->
            val name = entry.name.orEmpty()
            when {
                !entry.isDirectory -> listOf(name)
                name.lowercase() !in volumes -> listOf("$name/")
                else -> listOf("$name/") +
                    (entry.listOrNull(context) ?: return null).map { "$name/${it.name.orEmpty()}" }
            }
        }
    }

    /** A chapter [url]'s file under the series folder, or null for a loose book's, which is the series itself. */
    private fun relativePath(type: ContentType, series: String, url: String): String? = when (type) {
        ContentType.NOVELS -> LocalNovelSource.parseChapterPath(url)?.second?.takeIf { it != series }
        else -> localVolumeChapter(url)?.let { "${it.volume}/${it.file}" } ?: url.substringAfter('/')
    }

    private suspend fun address(type: ContentType, id: Long): String? = when (type) {
        ContentType.NOVELS -> novelRepository.getById(id)?.url
        else -> mangaRepository.getMangaById(id).url
    }

    private suspend fun chapterUrls(type: ContentType, id: Long): List<String> = when (type) {
        ContentType.NOVELS -> novelChapterRepository.getByNovelId(id).map { it.url }
        else -> chapterRepository.getChapterByMangaId(id).map { it.url }
    }

    private suspend fun heldBy(type: ContentType, url: String): Long? = when (type) {
        ContentType.NOVELS -> novelRepository.getByUrlAndSource(url, LocalNovelSource.ID)?.id
        else -> mangaRepository.getMangaByUrlAndSourceId(url, LocalSource.ID)?.id
    }

    private fun root(type: ContentType): UniFile? =
        if (type == ContentType.NOVELS) storageManager.getLocalNovelsDirectory() else localFiles.getBaseDirectory()
}
