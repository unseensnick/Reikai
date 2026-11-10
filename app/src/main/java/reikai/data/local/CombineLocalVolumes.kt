package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.download.renameDownloadFolder
import reikai.domain.entry.EntryId
import reikai.domain.entry.SetEntryTitle
import reikai.domain.library.ContentType
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.local.ChapterMove
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.OwnerMoves
import reikai.domain.local.SeriesAddress
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeRowMove
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.NovelItem
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.source.local.localVolumeChapter
import reikai.storage.FolderUnreadableException
import reikai.storage.listFilesOrFail
import reikai.storage.listFilesOrNull
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

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
    private val context: Context,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val localFiles: LocalSourceFileSystem,
    private val localManga: LocalSource,
    private val localNovels: LocalNovelSource,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val removeManga: RemoveMangaFromLibrary,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val storageManager: StorageManager,
    private val novelSources: NovelSourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val numberOverrides: ChapterNumberOverrideRepository,
    private val copyMarks: ChapterCopyMarkRepository,
    private val coverCache: CoverCache,
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val removeNovels: RemoveNovelsFromLibrary,
    private val chapterMoves: LocalChapterMoveRepository,
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaAdder: MangaLibraryAdder,
    private val novelAdder: NovelLibraryAdder,
    private val setEntryTitle: SetEntryTitle,
    private val localVolumes: LocalVolumeRepository,
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
        volumeKeys: List<String>,
        title: String,
    ): LocalVolumeMove {
        val folder = DiskUtil.buildValidFilename(title)
        return onApp {
            if (type ==
                ContentType.NOVELS
            ) {
                splitNovel(series, volumeKeys, folder)
            } else {
                splitManga(series, volumeKeys, folder)
            }
        }
    }

    // A move the screen started finishes after the screen has closed, and nobody is then left to offer its
    // Undo, so its tidy runs at once.
    private suspend fun onApp(move: suspend () -> LocalVolumeMove): LocalVolumeMove {
        // A folder that cannot be listed whole fails the move before anything is copied.
        val running = appScope.async {
            try {
                move()
            } catch (e: FolderUnreadableException) {
                failed(LocalMoveResult.Failed(e))
            }
        }
        return try {
            running.await()
        } catch (e: CancellationException) {
            appScope.launch { running.await().finish() }
            throw e
        }
    }

    private suspend fun combineManga(kept: CombinableSeries, others: List<CombinableSeries>): LocalVolumeMove {
        val base = localFiles.getBaseDirectory() ?: return failed(missing(kept))
        val keptDir = localFiles.getMangaDirectory(kept.folder) ?: return failed(missing(kept))
        val taken = keptDir.listFilesOrFail(context).mapNotNull { it.name }.toMutableSet()
        val placements = mutableListOf<Placement>()
        val rows = mutableListOf<OwnerMoves>()
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
            rows += OwnerMoves(kept.id, kept.id, moves)
        }
        val volumeRows = VolumeRows(localVolumes.loadedVolumes(ContentType.MANGA), kept.folder, kept.folder)
        for (other in others) {
            val dir = localFiles.getMangaDirectory(other.folder) ?: return failed(missing(other))
            val chapters = chapterRepository.getChapterByMangaId(other.id)
            volumeRows.next(other.folder)
            val inVolumes = chapters.mapNotNull { localVolumeChapter(it.url) }
            val moves = if (inVolumes.isNotEmpty()) {
                // Already split into volumes: each volume folder joins as it is, and loose chapters beside them
                // join as one more volume, as the kept series' own do.
                val loose = chapters.filter { localVolumeChapter(it.url) == null }
                val looseVolume = if (loose.isEmpty()) null else freeName(other.folder, taken)
                inVolumes.groupBy { it.volume }.flatMap { (volume, inVolume) ->
                    val name = freeName(volume, taken)
                    volumeRows.move(volume, name)
                    placements +=
                        Placement(
                            FileCopy(dir.findFile(volume) ?: return failed(missing(other)), keptDir, null, name),
                            dir,
                        )
                    inVolume.map { ChapterMove(it.url, it.copy(series = kept.folder, volume = name).url) }
                } + loose.map { chapter ->
                    val name = chapter.url.substringAfter('/')
                    placements += Placement(
                        FileCopy(dir.findFile(name) ?: return failed(missing(other)), keptDir, looseVolume, name),
                        dir,
                    )
                    ChapterMove(chapter.url, "${kept.folder}/$looseVolume/$name")
                }
            } else {
                val name = freeName(other.folder, taken)
                placements += Placement(FileCopy(dir, keptDir, null, name), base)
                chapters.map { ChapterMove(it.url, "${kept.folder}/$name/${it.url.substringAfter('/')}") }
            }
            rows += OwnerMoves(other.id, kept.id, moves)
        }
        return perform(
            type = ContentType.MANGA,
            placements = placements,
            rows = rows,
            volumeRows = volumeRows.moves,
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
        val taken = keptEntry.takeIf { it.isDirectory }?.listFilesOrFail(context).orEmpty()
            .mapNotNull { it.name }.toMutableSet()
        val placements = mutableListOf<Placement>()
        val rows = mutableListOf<OwnerMoves>()
        val volumeRows = VolumeRows(localVolumes.loadedVolumes(ContentType.NOVELS), kept.folder, folder)
        if (!keptEntry.isDirectory) {
            if (root.findFile(folder) != null) return failed(taken(folder))
            val name = freeName(kept.folder, taken)
            volumeRows.move(kept.folder, name)
            placements += Placement(FileCopy(keptEntry, root, folder, name), root)
            rows += OwnerMoves(kept.id, kept.id, novelMoves(kept, folder) { name })
        }
        for (other in others) {
            val entry = root.findFile(other.folder) ?: return failed(missing(other))
            val parent = if (entry.isDirectory) entry else root
            val books = if (entry.isDirectory) entry.listFilesOrFail(context).filter { it.isEpub() } else listOf(entry)
            val renamed = books.associate { book -> book.name.orEmpty() to freeName(book.name.orEmpty(), taken) }
            volumeRows.next(other.folder)
            renamed.forEach { (from, to) -> volumeRows.move(from, to) }
            books.forEach {
                placements +=
                    Placement(FileCopy(it, root, folder, renamed.getValue(it.name.orEmpty())), parent)
            }
            rows += OwnerMoves(other.id, kept.id, novelMoves(other, folder) { volume -> renamed[volume] ?: volume })
        }
        return perform(
            type = ContentType.NOVELS,
            placements = placements,
            rows = rows,
            volumeRows = volumeRows.moves,
            leaving = others.map { it.id },
            relist = listOf(kept.id),
            renamed = UrlChange(kept.id, kept.folder, folder).takeIf { folder != kept.folder },
            leftovers = { others.mapNotNull { root.findFile(it.folder)?.takeIf { dir -> dir.isDirectory } } },
        )
    }

    private suspend fun splitManga(series: CombinableSeries, keys: List<String>, folder: String): LocalVolumeMove {
        val base = localFiles.getBaseDirectory() ?: return failed(missing(series))
        val seriesDir = localFiles.getMangaDirectory(series.folder) ?: return failed(missing(series))
        val volumes = keys.map { seriesDir.findFile(it) ?: return failed(missing(series)) }
        if (base.findFile(folder) != null) return failed(taken(folder))
        val chapters = chapterRepository.getChapterByMangaId(series.id)
            .mapNotNull { chapter -> localVolumeChapter(chapter.url)?.takeIf { it.volume in keys } }
        // One volume becomes the series folder itself; several keep their folders inside a new one, and so does
        // one with an Extras chapter, which is the volume folder itself and has no place in a series folder.
        val single = keys.size == 1 && chapters.none { it.file.isEmpty() }
        val moves = chapters
            .map { ChapterMove(it.url, if (single) "$folder/${it.file}" else it.copy(series = folder).url) }
        val created = networkToLocalManga(
            SManga.create().apply {
                url = folder
                title = folder
            }.toDomainManga(LocalSource.ID),
        )
        val placements = if (single) {
            listOf(Placement(FileCopy(volumes.single(), base, null, folder), seriesDir))
        } else {
            volumes.map { Placement(FileCopy(it, base, folder, it.name.orEmpty()), seriesDir) }
        }
        return perform(
            type = ContentType.MANGA,
            placements = placements,
            rows = listOf(OwnerMoves(series.id, created.id, moves)),
            volumeRows = if (single) emptyList() else volumeRowsOut(ContentType.MANGA, series.folder, folder, keys),
            joining = created.id,
            relist = listOf(created.id, series.id),
        )
    }

    private suspend fun splitNovel(series: CombinableSeries, keys: List<String>, folder: String): LocalVolumeMove {
        val root = storageManager.getLocalNovelsDirectory() ?: return failed(missing(series))
        val seriesDir = root.findFile(series.folder) ?: return failed(missing(series))
        val books = keys.map { seriesDir.findFile(it) ?: return failed(missing(series)) }
        if (root.findFile(folder) != null) return failed(taken(folder))
        val created = novelRepository.insertOrGet(
            Novel.create().copy(source = LocalNovelSource.ID, url = folder, title = folder),
        ) ?: return failed(missing(series))
        val moves = novelChapterRepository.getByNovelId(series.id).mapNotNull { chapter ->
            val (_, volume, key) = LocalNovelSource.parseChapterPath(chapter.url) ?: return@mapNotNull null
            ChapterMove(chapter.url, LocalNovelSource.chapterPath(folder, volume, key)).takeIf { volume in keys }
        }
        return perform(
            type = ContentType.NOVELS,
            placements = books.map { Placement(FileCopy(it, root, folder, it.name.orEmpty()), seriesDir) },
            rows = listOf(OwnerMoves(series.id, created.id, moves)),
            volumeRows = volumeRowsOut(ContentType.NOVELS, series.folder, folder, keys),
            joining = created.id,
            relist = listOf(created.id, series.id),
        )
    }

    /** The saved order and names of [keys], going from series [from] to [to] under the same keys. */
    private suspend fun volumeRowsOut(type: ContentType, from: String, to: String, keys: List<String>) =
        localVolumes.loadedVolumes(type)[from].orEmpty().filter { it.key in keys }.map { row ->
            VolumeRowPair(
                VolumeRowMove(from, row.key, to, row.key, row.position),
                VolumeRowMove(to, row.key, from, row.key, row.position),
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
        rows: List<OwnerMoves>,
        relist: List<Long>,
        volumeRows: List<VolumeRowPair> = emptyList(),
        leaving: List<Long> = emptyList(),
        joining: Long? = null,
        renamed: UrlChange? = null,
        leftovers: suspend () -> List<UniFile> = { emptyList() },
    ): LocalVolumeMove {
        val removal = removal(type)
        val result = moveLocalFiles(context, placements.map { it.copy }) {
            checkListed(type, rows.flatMap { it.moves }.map { it.toUrl })
            chapterMoves.move(type, renamed?.let { SeriesAddress(it.id, it.to) }, rows, volumeRows.map { it.forward })
        }
        if (result !is LocalMoveResult.Moved) return failed(result)
        // The files and rows have moved, so a failure in the library steps after them must not lose the Undo.
        var tookOut = emptyMap<Long, Instant>()
        try {
            tookOut = removal.takeOut(leaving)
            joining?.let { addToLibrary(type, it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Updating the library after a local volume move failed" }
        }
        relist.forEach { relist(type, it) }
        return LocalVolumeMove(
            result = result,
            undoing = undo@{
                // Every moved file is found before any is tidied, so an Undo that cannot go through deletes nothing.
                val placed = placements.map {
                    it.placed() ?: return@undo LocalMoveResult.Failed(IllegalStateException("Moved files are gone"))
                }
                val back = placements.zip(placed) { placement, file -> placement.back(file) }
                val undone = moveLocalFiles(context, back) {
                    chapterMoves.move(
                        type,
                        renamed?.let { SeriesAddress(it.id, it.from) },
                        rows.map { row ->
                            OwnerMoves(row.toOwner, row.fromOwner, row.moves.map { ChapterMove(it.toUrl, it.fromUrl) })
                        },
                        volumeRows.map { it.back },
                    )
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
                leftovers().filter { it.listFilesOrNull(context)?.holdsOnlyDetails() == true }.forEach { it.delete() }
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

    /**
     * Lists series [id]'s chapters again from its files. A failure leaves the list as it was, since the move it
     * follows has landed: a novel left with no book throws, where manga's refresh catches its own.
     */
    private suspend fun relist(type: ContentType, id: Long) = try {
        relistOrThrow(type, id)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logcat(LogPriority.WARN, e) { "Listing local series $id again failed" }
    }

    private suspend fun relistOrThrow(type: ContentType, id: Long) {
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
                copyMarks,
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

    /**
     * Fails the move, before any row moves, when the copied files would not list every chapter at its new
     * address: a refresh would then delete those rows and their read state. A series of chapter folders with
     * a picture beside them, made a volume, reads as one chapter of pages.
     */
    private suspend fun checkListed(type: ContentType, addresses: List<String>) {
        val listed = if (type == ContentType.NOVELS) {
            addresses.mapNotNull { LocalNovelSource.parseChapterPath(it)?.first }.toSet().flatMap { series ->
                localNovels.parseNovel(series).chapters.orEmpty().map { it.path }
            }
        } else {
            addresses.map { it.substringBefore('/') }.toSet().flatMap { series ->
                val manga = SManga.create().apply {
                    url = series
                    title = series
                }
                localManga.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
                    .chapters.map { it.url }
            }
        }.toSet()
        val unlisted = addresses.filterNot { it in listed }
        check(unlisted.isEmpty()) { "${unlisted.size} chapters would not list where they move to" }
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

    /** Series [id]'s folder on disk, or for a loose book the folder it sits in. */
    suspend fun folderOf(type: ContentType, id: Long): UniFile? {
        val series = series(type, listOf(id)).firstOrNull() ?: return null
        val parent = parent(type) ?: return null
        return parent.findFile(series.folder)?.let { if (it.isDirectory) it else parent }
    }

    /**
     * Deletes series [id]'s volumes [keys], a manga volume folder with all it holds or a book, for good, and
     * lists the series again so their chapters leave it. Stops at the first that will not delete.
     */
    suspend fun deleteVolumes(type: ContentType, id: Long, keys: List<String>): LocalMoveResult = onAppResult {
        val series = series(type, listOf(id)).firstOrNull()
        val folder = folderOf(type, id)
        if (series == null || folder == null) {
            return@onAppResult LocalMoveResult.Failed(IllegalStateException("No folder"))
        }
        val failed = keys.firstOrNull { key -> folder.findFile(key)?.deleteAll() == false }
        // Their saved order and names would otherwise wait at those names for whatever is put there next.
        chapterMoves.forgetVolumes(type, id, series.folder, keys.takeWhile { it != failed })
        relist(type, id)
        failed?.let { LocalMoveResult.Failed(IllegalStateException("Could not delete $it")) }
            ?: LocalMoveResult.Moved(emptyList())
    }

    private suspend fun onAppResult(work: suspend () -> LocalMoveResult) = appScope.async { work() }.await()

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
    suspend fun renameFiles(
        type: ContentType,
        series: CombinableSeries,
        names: LocalFileNames,
        title: String? = null,
    ): LocalVolumeMove = onApp {
        val result = rename(type, series, names)
        if (result !is LocalMoveResult.Moved) return@onApp failed(result)
        // A name chosen in place of the title becomes the title, so the two stay in step for both types.
        val entry = if (type == ContentType.NOVELS) EntryId.Novel(series.id) else EntryId.Manga(series.id)
        val titleBefore = title?.let { setEntryTitle.await(entry, it) }
        relist(type, series.id)
        val renamed = series.copy(folder = names.series.to)
        val back = LocalFileNames(names.series.reversed(), names.volumes.map { it.reversed() })
        val undoing = suspend {
            rename(type, renamed, back).also {
                if (it !is LocalMoveResult.Moved) return@also
                if (title != null) setEntryTitle.await(entry, titleBefore)
                relist(type, series.id)
            }
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
                val inVolume = localVolumeChapter(chapter.url)
                val moved = inVolume?.copy(
                    series = target,
                    volume = volumeNames[inVolume.volume] ?: inVolume.volume,
                )?.url
                ChapterMove(chapter.url, moved ?: (listOf(target) + chapter.url.split('/').drop(1)).joinToString("/"))
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

    /**
     * The saved order and names of volumes joining [toSeries], read before they move. Each series that joins
     * is placed after the ones before it, kept order first; a series with no saved order of its own, or
     * joining one with none, keeps none, so its volumes sort by name among the rest.
     */
    private class VolumeRows(
        private val saved: Map<String, List<LocalVolume>>,
        keptSeries: String,
        private val toSeries: String,
    ) {
        val moves = mutableListOf<VolumeRowPair>()
        private var from = keptSeries
        private var base: Long? = 0L
        private var next: Long? = saved[keptSeries]?.mapNotNull { it.position }?.maxOrNull()?.plus(1)

        /** Starts on [series]' volumes, placed after those before it. */
        fun next(series: String) {
            from = series
            base = next
            val span = saved[series]?.mapNotNull { it.position }?.maxOrNull()?.plus(1) ?: 0L
            next = next?.plus(span)
        }

        fun move(key: String, toKey: String) {
            val row = saved[from]?.firstOrNull { it.key == key } ?: return
            val position = row.position?.let { p -> base?.plus(p) }
            moves += VolumeRowPair(
                VolumeRowMove(from, key, toSeries, toKey, position),
                VolumeRowMove(toSeries, toKey, from, key, row.position),
            )
        }
    }

    private class VolumeRowPair(val forward: VolumeRowMove, val back: VolumeRowMove)

    /** A novel series whose address changed [from] a loose book [to] a folder. */
    private class UrlChange(val id: Long, val from: String, val to: String)

    /** A file copy and the folder its source sat in, which an undo copies it back into. */
    private inner class Placement(val copy: FileCopy, val origin: UniFile) {
        // Read before the move, which deletes the source and may leave its name unreadable.
        private val originalName = copy.source.name.orEmpty()
        private val makesFolder = copy.folder?.let { copy.parent.findFile(it) == null } ?: false
        private val placedIn get() = if (copy.folder == null) copy.parent else copy.parent.findFile(copy.folder)

        // Trusted whole, since an Undo deletes whatever the moved folder holds beyond it.
        private val originalFiles = copy.source.takeIf {
            it.isDirectory
        }?.listFilesOrFail(context).orEmpty().mapNotNull { it.name }.toSet()

        /** The moved file or folder where the move left it, or null when it is gone. */
        fun placed(): UniFile? = placedIn?.findFile(copy.name)

        /** The copy back of [placed], without what the folder gained since, such as the cover a relist wrote. */
        fun back(placed: UniFile): FileCopy {
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
            if (folder.listFilesOrNull(context)?.all { it.name.orEmpty().startsWith('.') } == true) folder.delete()
        }
    }

    private companion object {
        fun UniFile.isEpub() = name.orEmpty().endsWith(EPUB, true)

        /** Deletes this file, or this folder after everything in it, since a plain folder delete needs it empty. */
        fun UniFile.deleteAll(): Boolean = listFiles().orEmpty().all { it.deleteAll() } && delete()

        /**
         * Whether what is left in a series folder is only its cover and details, which went with its chapters:
         * the local source writes a ComicInfo.xml into a series folder when it reads one from an archive.
         */
        fun List<UniFile>.holdsOnlyDetails() = all { file ->
            val name = file.name.orEmpty()
            !file.isDirectory && (
                name.startsWith("cover.", ignoreCase = true) || name.equals("details.json", ignoreCase = true) ||
                    name == COMIC_INFO_FILE || name.startsWith(".")
                )
        }
    }
}
