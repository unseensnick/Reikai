package reikai.data.local

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.download.renameDownloadFolder
import reikai.domain.library.ContentType
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.local.ChapterMove
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.SeriesRename
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.NovelItem
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import kotlin.coroutines.cancellation.CancellationException

/** A local series a combine can take in, by its library id, title and folder (or loose book). */
data class CombinableSeries(val id: Long, val title: String, val folder: String)

/**
 * A move of local volumes that went through or failed as [result]. [undo] puts the files, rows and library
 * entries back; [finish] tidies what only an Undo still needed, once the chance to undo has passed. Both run
 * on the app scope, so leaving the screen cuts neither off.
 */
class LocalVolumeMove internal constructor(
    val result: LocalMoveResult,
    private val undoing: suspend () -> LocalMoveResult,
    private val finishing: suspend () -> Unit,
    private val appScope: CoroutineScope,
) {
    suspend fun undo(): LocalMoveResult = appScope.async { undoing() }.await()

    fun finish() {
        appScope.launch { finishing() }
    }
}

/**
 * Moves local volumes between series of one content type: combines series into one, each other series
 * becoming volumes of the kept one, or takes a volume out into a series of its own. Files, chapter rows
 * with their read state, and library entries all move, and every move can be undone. A manga volume is a
 * folder of chapters and a novel volume a book, so a series of loose manga chapters becomes one volume
 * folder, the kept one's own included.
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
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaAdder: MangaLibraryAdder,
    private val novelAdder: NovelLibraryAdder,
    @AppCoroutineScope private val appScope: CoroutineScope,
) {

    /** The local series of [type] in the library, which a series can be added to as a volume. */
    suspend fun library(type: ContentType): List<CombinableSeries> = when (type) {
        ContentType.NOVELS -> novelRepository.getFavorites().filter { LocalNovelSource.isLocal(it.source) }
            .map { CombinableSeries(it.id, it.title, it.url) }
        else -> mangaRepository.getFavorites().filter { it.source == LocalSource.ID }
            .map { CombinableSeries(it.id, it.title, it.url) }
    }

    suspend fun series(type: ContentType, ids: List<Long>): List<CombinableSeries> = when (type) {
        ContentType.NOVELS -> ids.mapNotNull { id ->
            novelRepository.getById(id)?.let { CombinableSeries(id, it.title, it.url) }
        }
        else -> ids.map { id -> mangaRepository.getMangaById(id).let { CombinableSeries(id, it.title, it.url) } }
    }

    suspend fun combine(type: ContentType, kept: CombinableSeries, others: List<CombinableSeries>): LocalVolumeMove =
        onApp { if (type == ContentType.NOVELS) combineNovels(kept, others) else combineManga(kept, others) }

    /** Takes [series]' volume [volumeKey] out into a series of its own named [title], in the library. */
    suspend fun splitOut(
        type: ContentType,
        series: CombinableSeries,
        volumeKey: String,
        title: String,
    ): LocalVolumeMove {
        val folder = DiskUtil.buildValidFilename(title)
        return onApp {
            if (type ==
                ContentType.NOVELS
            ) {
                splitNovel(series, volumeKey, folder)
            } else {
                splitManga(series, volumeKey, folder)
            }
        }
    }

    // A move the screen started finishes after the screen has closed.
    private suspend fun onApp(move: suspend () -> LocalVolumeMove) = appScope.async { move() }.await()

    private suspend fun combineManga(kept: CombinableSeries, others: List<CombinableSeries>): LocalVolumeMove {
        val base = localFiles.getBaseDirectory() ?: return failed(missing(kept))
        val keptDir = localFiles.getMangaDirectory(kept.folder) ?: return failed(missing(kept))
        val taken = keptDir.listFiles().orEmpty().mapNotNull { it.name }.toMutableSet()
        val placements = mutableListOf<Placement>()
        val rows = mutableListOf<RowMove>()
        // The kept series' loose chapters go into a volume folder of their own, or its volumes would not all be folders.
        val loose = chapterRepository.getChapterByMangaId(kept.id).filter { it.url.count { c -> c == '/' } == 1 }
        if (loose.isNotEmpty()) {
            val volume = freeName(kept.folder, taken)
            val moves = loose.map { chapter ->
                val name = chapter.url.substringAfter('/')
                placements +=
                    Placement(
                        FileCopy(keptDir.findFile(name) ?: return failed(missing(kept)), keptDir, volume, name),
                        keptDir,
                    )
                ChapterMove(chapter.url, "${kept.folder}/$volume/$name")
            }
            rows += RowMove(kept.id, kept.id, moves)
        }
        for (other in others) {
            val dir = localFiles.getMangaDirectory(other.folder) ?: return failed(missing(other))
            val chapters = chapterRepository.getChapterByMangaId(other.id)
            val moves = if (chapters.any { it.url.count { c -> c == '/' } == 2 }) {
                // Already split into volumes: each volume folder joins as it is.
                chapters.groupBy { it.url.split('/')[1] }.flatMap { (volume, inVolume) ->
                    val name = freeName(volume, taken)
                    placements +=
                        Placement(
                            FileCopy(dir.findFile(volume) ?: return failed(missing(other)), keptDir, null, name),
                            dir,
                        )
                    inVolume.map { ChapterMove(it.url, "${kept.folder}/$name/${it.url.split('/')[2]}") }
                }
            } else {
                val name = freeName(other.folder, taken)
                placements += Placement(FileCopy(dir, keptDir, null, name), base)
                chapters.map { ChapterMove(it.url, "${kept.folder}/$name/${it.url.substringAfter('/')}") }
            }
            rows += RowMove(other.id, kept.id, moves)
        }
        return perform(
            type = ContentType.MANGA,
            placements = placements,
            rows = rows,
            leaving = others.map { it.id },
            relist = listOf(kept.id),
            leftovers = { others.mapNotNull { localFiles.getMangaDirectory(it.folder) } },
        )
    }

    private suspend fun combineNovels(kept: CombinableSeries, others: List<CombinableSeries>): LocalVolumeMove {
        val root = storageManager.getLocalNovelsDirectory() ?: return failed(missing(kept))
        val keptEntry = root.findFile(kept.folder) ?: return failed(missing(kept))
        // A loose book becomes a folder named after it, which the kept series' address then names.
        val folder = if (keptEntry.isDirectory) kept.folder else kept.folder.removeSuffix(EPUB)
        val taken = keptEntry.takeIf { it.isDirectory }?.listFiles().orEmpty().mapNotNull { it.name }.toMutableSet()
        val placements = mutableListOf<Placement>()
        val rows = mutableListOf<RowMove>()
        if (!keptEntry.isDirectory) {
            if (root.findFile(folder) != null) return failed(taken(folder))
            val name = freeName(kept.folder, taken)
            placements += Placement(FileCopy(keptEntry, root, folder, name), root)
            rows += RowMove(kept.id, kept.id, novelMoves(kept, folder) { name })
        }
        for (other in others) {
            val entry = root.findFile(other.folder) ?: return failed(missing(other))
            val parent = if (entry.isDirectory) entry else root
            val books = if (entry.isDirectory) entry.listFiles().orEmpty().filter { it.isEpub() } else listOf(entry)
            val renamed = books.associate { book -> book.name.orEmpty() to freeName(book.name.orEmpty(), taken) }
            books.forEach {
                placements +=
                    Placement(FileCopy(it, root, folder, renamed.getValue(it.name.orEmpty())), parent)
            }
            rows += RowMove(other.id, kept.id, novelMoves(other, folder) { volume -> renamed[volume] ?: volume })
        }
        return perform(
            type = ContentType.NOVELS,
            placements = placements,
            rows = rows,
            leaving = others.map { it.id },
            relist = listOf(kept.id),
            renamed = UrlChange(kept.id, kept.folder, folder).takeIf { folder != kept.folder },
            leftovers = { others.mapNotNull { root.findFile(it.folder)?.takeIf { dir -> dir.isDirectory } } },
        )
    }

    private suspend fun splitManga(series: CombinableSeries, volumeKey: String, folder: String): LocalVolumeMove {
        val base = localFiles.getBaseDirectory() ?: return failed(missing(series))
        val seriesDir = localFiles.getMangaDirectory(series.folder) ?: return failed(missing(series))
        val volume = seriesDir.findFile(volumeKey) ?: return failed(missing(series))
        if (base.findFile(folder) != null) return failed(taken(folder))
        // An Extras chapter is the volume folder itself, which a series of its own has no place for.
        val moves = chapterRepository.getChapterByMangaId(series.id)
            .mapNotNull { chapter ->
                chapter.url.split('/').takeIf {
                    it.size == 3 && it[1] == volumeKey &&
                        it[2].isNotEmpty()
                }
            }
            .map { (_, _, file) -> ChapterMove("${series.folder}/$volumeKey/$file", "$folder/$file") }
        val created = networkToLocalManga(
            SManga.create().apply {
                url = folder
                title = folder
            }.toDomainManga(LocalSource.ID),
        )
        return perform(
            type = ContentType.MANGA,
            placements = listOf(Placement(FileCopy(volume, base, null, folder), seriesDir)),
            rows = listOf(RowMove(series.id, created.id, moves)),
            joining = created.id,
            relist = listOf(created.id, series.id),
        )
    }

    private suspend fun splitNovel(series: CombinableSeries, volumeKey: String, folder: String): LocalVolumeMove {
        val root = storageManager.getLocalNovelsDirectory() ?: return failed(missing(series))
        val seriesDir = root.findFile(series.folder) ?: return failed(missing(series))
        val book = seriesDir.findFile(volumeKey) ?: return failed(missing(series))
        if (root.findFile(folder) != null) return failed(taken(folder))
        val created = novelRepository.insertOrGet(
            Novel.create().copy(source = LocalNovelSource.ID, url = folder, title = folder),
        ) ?: return failed(missing(series))
        val moves = novelChapterRepository.getByNovelId(series.id).mapNotNull { chapter ->
            val (_, volume, key) = LocalNovelSource.parseChapterPath(chapter.url) ?: return@mapNotNull null
            ChapterMove(chapter.url, LocalNovelSource.chapterPath(folder, volumeKey, key)).takeIf {
                volume == volumeKey
            }
        }
        return perform(
            type = ContentType.NOVELS,
            placements = listOf(Placement(FileCopy(book, root, folder, volumeKey), seriesDir)),
            rows = listOf(RowMove(series.id, created.id, moves)),
            joining = created.id,
            relist = listOf(created.id, series.id),
        )
    }

    /**
     * Runs one move and builds its undo. [leaving] entries leave the library, [joining] joins it, and the
     * [relist] entries list their chapters again; [leftovers] are folders deleted once the chance to undo has
     * passed, if all they still hold is their cover and details.
     */
    private suspend fun perform(
        type: ContentType,
        placements: List<Placement>,
        rows: List<RowMove>,
        relist: List<Long>,
        leaving: List<Long> = emptyList(),
        joining: Long? = null,
        renamed: UrlChange? = null,
        leftovers: suspend () -> List<UniFile> = { emptyList() },
    ): LocalVolumeMove {
        val removal = removal(type)
        val result = moveLocalFiles(placements.map { it.copy }) {
            renamed?.let { novelRepository.update(NovelUpdate(it.id) { url = it.to }) }
            rows.forEach { chapterMoves.move(type, it.from, it.to, it.moves) }
        }
        if (result !is LocalMoveResult.Moved) return failed(result)
        val tookOut = removal.takeOut(leaving)
        joining?.let { addToLibrary(type, it) }
        relist.forEach { relist(type, it) }
        return LocalVolumeMove(
            result = result,
            undoing = undo@{
                val back = placements.mapNotNull { it.back() }
                if (back.size !=
                    placements.size
                ) {
                    return@undo LocalMoveResult.Failed(IllegalStateException("Moved files are gone"))
                }
                val undone = moveLocalFiles(back) {
                    renamed?.let { novelRepository.update(NovelUpdate(it.id) { url = it.from }) }
                    rows.forEach { row ->
                        chapterMoves.move(type, row.to, row.from, row.moves.map { ChapterMove(it.toUrl, it.fromUrl) })
                    }
                }
                if (undone is LocalMoveResult.Moved) {
                    placements.forEach { it.dropMadeFolder() }
                    removal.restore(tookOut)
                    joining?.let { removal.await(listOf(it)) }
                    (relist + leaving).filter { it != joining }.forEach { relist(type, it) }
                }
                undone
            },
            finishing = {
                removal.dropCovers(tookOut.keys.toList())
                leftovers().filter { it.holdsOnlyDetails() }.forEach { it.delete() }
            },
            appScope = appScope,
        )
    }

    private fun removal(type: ContentType): EntryLibraryRemoval = if (type ==
        ContentType.NOVELS
    ) {
        removeNovels
    } else {
        removeManga
    }

    private suspend fun addToLibrary(type: ContentType, id: Long) {
        if (type == ContentType.NOVELS) {
            val novel = novelRepository.getById(id) ?: return
            novelAdder.addToBatch(NovelItem(novel.title, novel.url), LocalNovelSource.ID, emptyList())
        } else {
            mangaAdder.addToBatch(id, emptyList())
        }
    }

    private suspend fun relist(type: ContentType, id: Long) {
        if (type == ContentType.NOVELS) {
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
        } else {
            updateMangaFromRemote(
                mangaRepository.getMangaById(id),
                fetchDetails = true,
                fetchChapters = true,
                manualFetch = true,
            )
        }
    }

    private suspend fun novelMoves(series: CombinableSeries, folder: String, volumeName: (String) -> String) =
        novelChapterRepository.getByNovelId(series.id).mapNotNull { chapter ->
            val (_, volume, key) = LocalNovelSource.parseChapterPath(chapter.url) ?: return@mapNotNull null
            ChapterMove(chapter.url, LocalNovelSource.chapterPath(folder, volumeName(volume), key))
        }

    /**
     * What Rename files gives [series] from its [title] and volume [labels], the series' name made free of every
     * folder and every other series of [type], since one in the database that is no longer in the library
     * still holds its address.
     */
    suspend fun renamePlan(
        type: ContentType,
        series: CombinableSeries,
        title: String,
        labels: List<Pair<String, String>>,
    ) =
        localFileNames(type, series.folder, title, labels).let { names ->
            val parent = parent(type)
            val free = freeNames(names.series.to).first { name ->
                name.equals(series.folder, ignoreCase = true) ||
                    (parent?.findFile(name) == null && heldBy(type, name).let { it == null || it == series.id })
            }
            names.copy(series = names.series.copy(to = free), taken = names.series.to.takeIf { it != free })
        }

    private suspend fun heldBy(type: ContentType, url: String): Long? = when (type) {
        ContentType.NOVELS -> novelRepository.getByUrlAndSource(url, LocalNovelSource.ID)?.id
        else -> mangaRepository.getMangaByUrlAndSourceId(url, LocalSource.ID)?.id
    }

    private fun parent(type: ContentType) =
        if (type == ContentType.NOVELS) storageManager.getLocalNovelsDirectory() else localFiles.getBaseDirectory()

    /**
     * Renames [series]' folder and volumes on disk as [names] gives them, with every chapter's address and what
     * is keyed by it, so read state stays. A rename that fails partway puts back what it renamed.
     */
    suspend fun renameFiles(type: ContentType, series: CombinableSeries, names: LocalFileNames): LocalVolumeMove =
        onApp {
            val result = rename(type, series, names)
            if (result !is LocalMoveResult.Moved) return@onApp failed(result)
            relist(type, series.id)
            val renamed = series.copy(folder = names.series.to)
            val back = LocalFileNames(names.series.reversed(), names.volumes.map { it.reversed() })
            val undoing = suspend {
                rename(type, renamed, back).also { if (it is LocalMoveResult.Moved) relist(type, series.id) }
            }
            LocalVolumeMove(result, undoing, {}, appScope)
        }

    private suspend fun rename(type: ContentType, series: CombinableSeries, names: LocalFileNames): LocalMoveResult {
        val parent = parent(type) ?: return missing(series)
        val seriesFile = parent.findFile(series.folder) ?: return missing(series)
        val target = names.series.to
        if (names.series.changes && !target.equals(series.folder, true) &&
            parent.findFile(target) != null
        ) {
            return taken(target)
        }
        val volumes = names.volumes.filter { it.changes }
        volumes.firstOrNull {
            !it.to.equals(it.from, true) && seriesFile.findFile(it.to) != null
        }?.let { return taken(it.to) }
        val volumeNames = volumes.associate { it.from to it.to }
        val chapters = if (type == ContentType.NOVELS) {
            novelChapterRepository.getByNovelId(series.id).mapNotNull { chapter ->
                val (_, volume, key) = LocalNovelSource.parseChapterPath(chapter.url) ?: return@mapNotNull null
                // A loose book's one volume is the series' own file.
                val renamed = volumeNames[volume] ?: if (volume == series.folder) target else volume
                ChapterMove(chapter.url, LocalNovelSource.chapterPath(target, renamed, key))
            }
        } else {
            chapterRepository.getChapterByMangaId(series.id).map { chapter ->
                val parts = chapter.url.split('/')
                val rest = if (parts.size == 3) listOf(volumeNames[parts[1]] ?: parts[1], parts[2]) else parts.drop(1)
                ChapterMove(chapter.url, (listOf(target) + rest).joinToString("/"))
            }
        }.filter { it.fromUrl != it.toUrl }
        val done = mutableListOf<Pair<UniFile, String>>()
        return try {
            volumes.forEach { volume ->
                val file = checkNotNull(seriesFile.findFile(volume.from)) { "${volume.from} is gone" }
                check(renameDownloadFolder(file, volume.to)) { "Could not rename ${volume.from}" }
                done += file to volume.from
            }
            if (names.series.changes) {
                check(renameDownloadFolder(seriesFile, target)) { "Could not rename ${series.folder}" }
                done += seriesFile to series.folder
            }
            chapterMoves.rename(type, SeriesRename(series.id, series.folder, target, volumeNames), chapters)
            LocalMoveResult.Moved(emptyList())
        } catch (e: Throwable) {
            done.asReversed().forEach { (file, name) -> renameDownloadFolder(file, name) }
            if (e is CancellationException) throw e
            LocalMoveResult.Failed(e)
        }
    }

    private fun failed(result: LocalMoveResult) = LocalVolumeMove(result, { result }, {}, appScope)

    private fun taken(folder: String) = LocalMoveResult.Failed(IllegalStateException("$folder is already taken"))

    private fun missing(series: CombinableSeries) =
        LocalMoveResult.Failed(IllegalStateException("${series.title}'s files are not in the local folder"))

    /** One chapter-row move from series [from] to series [to]. */
    private class RowMove(val from: Long, val to: Long, val moves: List<ChapterMove>)

    /** A novel series whose address changed [from] a loose book [to] a folder. */
    private class UrlChange(val id: Long, val from: String, val to: String)

    /** A file copy and the folder its source sat in, which an undo copies it back into. */
    private class Placement(val copy: FileCopy, val origin: UniFile) {
        // Read before the move, which deletes the source and may leave its name unreadable.
        private val originalName = copy.source.name.orEmpty()
        private val makesFolder = copy.folder?.let { copy.parent.findFile(it) == null } ?: false
        private val placedIn get() = if (copy.folder == null) copy.parent else copy.parent.findFile(copy.folder)

        private val originalFiles = copy.source.takeIf {
            it.isDirectory
        }?.listFiles().orEmpty().mapNotNull { it.name }.toSet()

        /** The copy back, without what the folder gained since, such as the cover a relist wrote into it. */
        fun back(): FileCopy? {
            val placed = placedIn?.findFile(copy.name) ?: return null
            if (placed.isDirectory) {
                placed.listFiles().orEmpty().filter {
                    it.name !in originalFiles
                }.forEach { it.delete() }
            }
            return FileCopy(placed, origin, null, originalName)
        }

        /** The folder the copy made, once an undo has emptied it, so it does not list as an empty chapter. */
        fun dropMadeFolder() {
            if (!makesFolder) return
            val folder = copy.folder?.let { copy.parent.findFile(it) } ?: return
            if (folder.listFiles().orEmpty().all { it.name.orEmpty().startsWith('.') }) folder.delete()
        }
    }

    private companion object {
        fun UniFile.isEpub() = name.orEmpty().endsWith(EPUB, true)

        /** Whether what is left in a series folder is only its cover and details, which went with its chapters. */
        fun UniFile.holdsOnlyDetails() = listFiles().orEmpty().all { file ->
            val name = file.name.orEmpty().lowercase()
            !file.isDirectory && (name.startsWith("cover.") || name == "details.json" || name.startsWith("."))
        }
    }
}
