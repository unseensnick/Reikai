package reikai.data.local

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.library.ContentType
import reikai.domain.local.ChapterMove
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.io.LocalSourceFileSystem

/** A local series a combine can take in, by its library id, title and folder (or loose book). */
data class CombinableSeries(val id: Long, val title: String, val folder: String)

/**
 * Combines local series of one content type into one, each other series becoming volumes of the [kept]
 * one: its files move into the kept series' folder, its chapters with their read state move to the kept
 * series, and it leaves the library. A manga volume is a folder of chapters and a novel volume a book, so a
 * series of loose manga chapters becomes one volume folder, the kept one's own included.
 */
@Inject
class CombineLocalVolumes(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val localFiles: LocalSourceFileSystem,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val removeManga: RemoveMangaFromLibrary,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val storageManager: StorageManager,
    private val novelSources: NovelSourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val numberOverrides: ChapterNumberOverrideRepository,
    private val coverCache: CoverCache,
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val removeNovels: RemoveNovelsFromLibrary,
    private val chapterMoves: LocalChapterMoveRepository,
) {

    suspend fun series(type: ContentType, ids: List<Long>): List<CombinableSeries> = when (type) {
        ContentType.NOVELS -> ids.mapNotNull { id ->
            novelRepository.getById(id)?.let { CombinableSeries(id, it.title, it.url) }
        }
        else -> ids.map { id -> mangaRepository.getMangaById(id).let { CombinableSeries(id, it.title, it.url) } }
    }

    suspend fun combine(type: ContentType, kept: CombinableSeries, others: List<CombinableSeries>): LocalMoveResult =
        if (type == ContentType.NOVELS) combineNovels(kept, others) else combineManga(kept, others)

    private suspend fun combineManga(kept: CombinableSeries, others: List<CombinableSeries>): LocalMoveResult {
        val keptDir = localFiles.getMangaDirectory(kept.folder) ?: return missing(kept)
        val taken = keptDir.listFiles().orEmpty().mapNotNull { it.name }.toMutableSet()
        val copies = mutableListOf<FileCopy>()
        val moves = mutableMapOf<Long, MutableList<ChapterMove>>()
        // The kept series' loose chapters go into a volume folder of their own, or its volumes would not all be folders.
        val loose = chapterRepository.getChapterByMangaId(kept.id).filter { it.url.count { c -> c == '/' } == 1 }
        if (loose.isNotEmpty()) {
            val volume = freeName(kept.folder, taken)
            loose.forEach { chapter ->
                val name = chapter.url.substringAfter('/')
                val file = keptDir.findFile(name) ?: return missing(kept)
                copies += FileCopy(file, keptDir, volume, name)
                moves.getOrPut(kept.id) { mutableListOf() } += ChapterMove(chapter.url, "${kept.folder}/$volume/$name")
            }
        }
        for (other in others) {
            val dir = localFiles.getMangaDirectory(other.folder) ?: return missing(other)
            val chapters = chapterRepository.getChapterByMangaId(other.id)
            val own = moves.getOrPut(other.id) { mutableListOf() }
            if (chapters.any { it.url.count { c -> c == '/' } == 2 }) {
                // Already split into volumes: each volume folder joins as it is.
                chapters.groupBy { it.url.split('/')[1] }.forEach { (volume, inVolume) ->
                    val name = freeName(volume, taken)
                    copies += FileCopy(dir.findFile(volume) ?: return missing(other), keptDir, null, name)
                    inVolume.forEach { own += ChapterMove(it.url, "${kept.folder}/$name/${it.url.split('/')[2]}") }
                }
            } else {
                val name = freeName(other.folder, taken)
                copies += FileCopy(dir, keptDir, null, name)
                chapters.forEach { own += ChapterMove(it.url, "${kept.folder}/$name/${it.url.substringAfter('/')}") }
            }
        }
        val result = moveLocalFiles(copies) {
            moves.forEach { (owner, list) -> chapterMoves.move(ContentType.MANGA, owner, kept.id, list) }
        }
        if (result is LocalMoveResult.Moved) {
            others.forEach { other ->
                localFiles.getMangaDirectory(other.folder)?.takeIf { it.holdsOnlyDetails() }?.delete()
            }
            removeManga.await(others.map { it.id })
            updateMangaFromRemote(mangaRepository.getMangaById(kept.id), fetchChapters = true)
        }
        return result
    }

    private suspend fun combineNovels(kept: CombinableSeries, others: List<CombinableSeries>): LocalMoveResult {
        val root = storageManager.getLocalNovelsDirectory() ?: return missing(kept)
        val keptEntry = root.findFile(kept.folder) ?: return missing(kept)
        // A loose book becomes a folder named after it, which the kept series' address then names.
        val folder = if (keptEntry.isDirectory) kept.folder else kept.folder.removeSuffix(EPUB)
        val taken = (if (keptEntry.isDirectory) keptEntry.listFiles().orEmpty() else emptyArray()).mapNotNull {
            it.name
        }.toMutableSet()
        val copies = mutableListOf<FileCopy>()
        val moves = mutableMapOf<Long, MutableList<ChapterMove>>()
        if (!keptEntry.isDirectory) {
            if (root.findFile(folder) !=
                null
            ) {
                return LocalMoveResult.Failed(IllegalStateException("$folder is already taken"))
            }
            val name = freeName(kept.folder, taken)
            copies += FileCopy(keptEntry, root, folder, name)
            moves[kept.id] = novelMoves(kept, folder) { name }
        }
        for (other in others) {
            val entry = root.findFile(other.folder) ?: return missing(other)
            val books = if (entry.isDirectory) {
                entry.listFiles().orEmpty().filter {
                    it.name.orEmpty().endsWith(EPUB, true)
                }
            } else {
                listOf(entry)
            }
            val renamed = books.associate { book -> book.name.orEmpty() to freeName(book.name.orEmpty(), taken) }
            books.forEach { copies += FileCopy(it, root, folder, renamed.getValue(it.name.orEmpty())) }
            moves[other.id] = novelMoves(other, folder) { volume -> renamed[volume] ?: volume }
        }
        val result = moveLocalFiles(copies) {
            if (folder != kept.folder) novelRepository.update(NovelUpdate(kept.id) { url = folder })
            moves.forEach { (owner, list) -> chapterMoves.move(ContentType.NOVELS, owner, kept.id, list) }
        }
        if (result is LocalMoveResult.Moved) {
            others.forEach { other ->
                root.findFile(other.folder)?.takeIf { it.isDirectory && it.holdsOnlyDetails() }?.delete()
            }
            removeNovels.await(others.map { it.id })
            refreshNovel(kept.id)
        }
        return result
    }

    private suspend fun novelMoves(series: CombinableSeries, folder: String, volumeName: (String) -> String) =
        novelChapterRepository.getByNovelId(series.id).mapNotNull { chapter ->
            val (_, volume, key) = LocalNovelSource.parseChapterPath(chapter.url) ?: return@mapNotNull null
            ChapterMove(chapter.url, LocalNovelSource.chapterPath(folder, volumeName(volume), key))
        }.toMutableList()

    private suspend fun refreshNovel(id: Long) {
        val novel = novelRepository.getById(id) ?: return
        val source = novelSources.get(novel.source) ?: return
        refreshNovelFromSource(
            novel,
            source,
            novelChapterRepository,
            novelRepository,
            libraryPreferences,
            numberOverrides,
            coverCache,
            novelDownloadManager = novelDownloadManager(),
            manualFetch = true,
        )
    }

    private fun missing(series: CombinableSeries) =
        LocalMoveResult.Failed(IllegalStateException("${series.title}'s files are not in the local folder"))

    private companion object {
        const val EPUB = ".epub"

        /** [name], or "name (2)" and up when [taken] holds it, taken for the next caller. */
        fun freeName(name: String, taken: MutableSet<String>): String {
            val stem = name.substringBeforeLast('.', name)
            val extension = name.removePrefix(stem)
            val free = generateSequence(1) { it + 1 }.map { if (it == 1) name else "$stem ($it)$extension" }
                .first { candidate -> taken.none { it.equals(candidate, ignoreCase = true) } }
            taken += free
            return free
        }

        /** Whether what is left in a series folder is only its cover and details, which went with its chapters. */
        fun UniFile.holdsOnlyDetails() = listFiles().orEmpty().all { file ->
            val name = file.name.orEmpty().lowercase()
            !file.isDirectory && (name.startsWith("cover.") || name == "details.json" || name == ".nomedia")
        }
    }
}
