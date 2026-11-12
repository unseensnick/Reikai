package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
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
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.entry.EntryId
import reikai.domain.entry.SetEntryTitle
import reikai.domain.library.ContentType
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournalPlan.FileStep
import reikai.domain.local.LocalFileJournalPlan.Organize
import reikai.domain.local.LocalFileJournalPlan.UrlRule
import reikai.domain.local.LocalGroups
import reikai.domain.local.LocalSeriesBlockedException
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.LocalVolumeHandBack
import reikai.domain.local.VolumeOwner
import reikai.domain.local.VolumeRowMove
import reikai.domain.local.localGroupKey
import reikai.domain.local.localGroupUnit
import reikai.domain.local.localVolumeContainer
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
import reikai.source.local.localChapterUrl
import reikai.storage.canMoveAndRename
import reikai.storage.deleteTree
import reikai.storage.freeName
import reikai.storage.listOrNull
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** A local series a combine can take in, by its library id, title and folder (or loose book). */
data class CombinableSeries(val id: Long, val title: String, val folder: String)

/**
 * A change to local volumes that went through or failed as [result]. [undo] puts the rows (and, for Organize files,
 * the files) and library entries back; [finish] tidies what only an Undo still needed, once the chance to undo has
 * passed. Both run on the app scope, so leaving the screen cuts neither off. [created] is the series a split made.
 */
class LocalVolumeMove internal constructor(
    val result: LocalMoveResult,
    private val undoing: suspend () -> LocalMoveResult,
    private val finishing: suspend () -> Unit,
    private val appScope: CoroutineScope,
    val created: Long? = null,
) {
    suspend fun undo(): LocalMoveResult = appScope.async { blockedAs({ it }) { undoing() } }.await()

    fun finish() {
        appScope.launch { finishing() }
    }
}

sealed interface LocalMoveResult {
    data object Moved : LocalMoveResult

    data class Failed(val error: Throwable) : LocalMoveResult
}

/**
 * Groups local series of one content type into volumes and takes them apart, without touching a file: combining
 * makes each other series' volumes the kept one's, splitting takes volumes out into a new series, and only rows
 * change (which series lists which volume, the chapters' owner, volume order and names), in one transaction, every
 * step undoable. Files move only by Organize files ([organizeFiles]), on the user's word.
 */
@Inject
@ContributesBinding(AppScope::class)
class CombineLocalVolumes(
    private val mangaRepository: MangaRepository,
    private val localManga: LocalSource,
    private val localNovels: LocalNovelSource,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val removeManga: RemoveMangaFromLibrary,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val storageManager: StorageManager,
    private val novelSources: NovelSourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val syncRules: NovelChapterSyncRules,
    private val tombstones: ChapterTombstoneRepository,
    private val coverCache: CoverCache,
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val removeNovels: RemoveNovelsFromLibrary,
    private val chapterMoves: LocalChapterMoveRepository,
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaAdder: MangaLibraryAdder,
    private val novelAdder: NovelLibraryAdder,
    private val setEntryTitle: SetEntryTitle,
    private val localVolumes: LocalVolumeRepository,
    private val organizer: LocalOrganizer,
    private val seriesLocks: LocalSeriesLocks,
    private val context: Context,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : LocalVolumeHandBack {

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

    /** Makes every volume [others] list volumes of [kept]; they leave the library. */
    suspend fun combine(type: ContentType, kept: CombinableSeries, others: List<CombinableSeries>): LocalVolumeMove =
        onApp {
            val folders = listOf(kept.folder) + others.map { it.folder }
            seriesLocks.withSeries(type, folders) {
                val before = localVolumes.all(type)
                val groups = LocalGroups(before)
                val remove = mutableListOf<VolumeOwner>()
                val add = mutableListOf<VolumeOwner>()
                val saved = localVolumes.loadedVolumes(type)
                val volumeRows = VolumeRows(saved, kept.folder)
                for (other in others) {
                    val held = before.filter { it.owner == other.folder }
                    remove += held
                    // A volume of the kept series' own folder that the other one held simply comes home, and a
                    // split-out series' own marker goes, since it names no folder for the kept one to list.
                    add += held.filter { it.folder != kept.folder && !groups.hasNoFolder(it.folder) }
                        .map { it.copy(owner = kept.folder) }
                    // Its own folder only when it lists it: a folder another series holds whole stays with that one.
                    if (!groups.hasNoFolder(other.folder) && !groups.isLentWhole(other.folder)) {
                        add += VolumeOwner(other.folder, VolumeOwner.WHOLE, kept.folder)
                    }
                    volumeRows.next(other.folder)
                    saved[other.folder].orEmpty().forEach { row ->
                        val (folder, volume) = localGroupUnit(other.folder, row.key)
                        volumeRows.move(row.key, localGroupKey(kept.folder, folder, volume))
                    }
                }
                regroup(
                    type = type,
                    folders = folders,
                    series = (listOf(kept) + others).associate { it.folder to it.id },
                    remove = remove,
                    add = add,
                    volumes = volumeRows.moves,
                    relist = listOf(kept.id),
                    leaving = others.map { it.id },
                )
            }
        }

    /**
     * Applies a backup's owner rows as a combine applies its own: a volume the backup puts in another series moves
     * there with its chapters' rows and kept state, a series whose own folder the backup lends whole to another
     * leaves the library, and the owners list again. The backup wins over the device, as it does for merge groups,
     * and a restore offers no Undo. A row the device already holds changes nothing.
     */
    suspend fun restoreOwners(type: ContentType, owners: List<VolumeOwner>) {
        val fresh = owners.distinct() - localVolumes.all(type).toSet()
        if (fresh.isEmpty()) return
        val units = fresh.mapTo(HashSet()) { it.folder to it.volume }
        val before = localVolumes.all(type)
        val folders = (
            fresh.flatMap { listOf(it.folder, it.owner) } + before.map { it.owner }.filter { owner ->
                before.any { it.owner == owner && (it.folder to it.volume) in units }
            }
            ).distinct()
        seriesLocks.withSeries(type, folders) {
            val now = localVolumes.all(type)
            val replaced = now.filter { (it.folder to it.volume) in units && it !in fresh }
            val series = folders.mapNotNull { folder ->
                chapterMoves.localSeriesId(type, folder)?.let { folder to it }
            }.toMap()
            val leaving = fresh.filter { it.volume == VolumeOwner.WHOLE && it.folder != it.owner }
                .mapNotNull { series[it.folder] }
            regroup(
                type = type,
                folders = folders,
                series = series,
                remove = replaced,
                add = fresh,
                volumes = emptyList(),
                relist = (fresh.map { it.owner } + replaced.map { it.owner }).distinct().mapNotNull { series[it] },
                leaving = leaving,
            ).finish()
        }
    }

    /** Takes [series]' volumes [volumeKeys] out into one series of their own named [title], in the library. */
    suspend fun splitOut(
        type: ContentType,
        series: CombinableSeries,
        volumeKeys: List<String>,
        title: String,
    ): LocalVolumeMove {
        val folder = DiskUtil.buildValidFilename(title)
        return onApp {
            val folders = listOf(series.folder, folder)
            seriesLocks.withSeries(type, folders) {
                // A name a folder, another series or a group's rows hold would list as this series' own folder, or
                // meet those rows.
                val names =
                    storageManager.localRoot(type)?.listOrNull(context) ?: return@withSeries failed(missing(series))
                val held = names.mapNotNull { it.name } + localNamesHeld(type, chapterMoves, localVolumes)
                if (held.any { it.equals(folder, true) }) return@withSeries failed(taken(folder))
                val before = localVolumes.all(type)
                val units = volumeKeys.map { localGroupUnit(series.folder, it) }
                val created = when (type) {
                    ContentType.NOVELS -> novelRepository.insertOrGet(
                        Novel.create().copy(source = LocalNovelSource.ID, url = folder, title = folder),
                    )?.id ?: return@withSeries failed(missing(series))
                    else -> networkToLocalManga(
                        SManga.create().apply {
                            url = folder
                            this.title = folder
                        }.toDomainManga(LocalSource.ID),
                    ).id
                }
                val volumes = localVolumes.loadedVolumes(type)[series.folder].orEmpty().filter { it.key in volumeKeys }
                    .map { row ->
                        val (from, volume) = localGroupUnit(series.folder, row.key)
                        val key = localGroupKey(folder, from, volume)
                        VolumeRowPair(
                            VolumeRowMove(series.folder, row.key, folder, key, row.position),
                            VolumeRowMove(folder, key, series.folder, row.key, row.position),
                        )
                    }
                regroup(
                    type = type,
                    folders = folders,
                    series = mapOf(series.folder to series.id, folder to created),
                    remove = before.filter { (it.folder to it.volume) in units },
                    add = units.map { (from, volume) -> VolumeOwner(from, volume, folder) } +
                        VolumeOwner(folder, VolumeOwner.WHOLE, folder),
                    volumes = volumes,
                    relist = listOf(created, series.id),
                    joining = created,
                )
            }
        }
    }

    // A move the screen started finishes after the screen has closed, and nobody is then left to offer its
    // Undo, so its tidy runs at once.
    private suspend fun onApp(move: suspend () -> LocalVolumeMove): LocalVolumeMove {
        val running = appScope.async { blockedAs({ failed(it) }) { move() } }
        return try {
            running.await()
        } catch (e: CancellationException) {
            appScope.launch { running.await().finish() }
            throw e
        }
    }

    /**
     * Writes one regrouping and builds its undo. [leaving] entries leave the library, [joining] joins it, and the
     * [relist] entries list their chapters again. The caller holds the lock on [folders]; the undo runs later on the
     * app scope, so it takes it again. Both place the [series]' chapters as they find them then, so a chapter first
     * listed since goes back with its volume.
     */
    private suspend fun regroup(
        type: ContentType,
        folders: List<String>,
        series: Map<String, Long>,
        remove: List<VolumeOwner>,
        add: List<VolumeOwner>,
        volumes: List<VolumeRowPair>,
        relist: List<Long>,
        leaving: List<Long> = emptyList(),
        joining: Long? = null,
    ): LocalVolumeMove {
        val removal = removal(type)
        try {
            chapterMoves.regroup(type, series, remove, add, volumes.map { it.forward }) { chapterVolumeUnit(type, it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return failed(LocalMoveResult.Failed(e))
        }
        // Who owns what in the folders the Undo gives volumes back to: it writes its rows only over this same state.
        val handed = remove.mapTo(HashSet()) { it.folder }
        val left = localVolumes.all(type).filterTo(HashSet()) { it.folder in handed }
        // The rows have moved, so a failure in the library steps after them must not lose the Undo.
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
            result = LocalMoveResult.Moved,
            undoing = {
                seriesLocks.withSeries(type, folders) undo@{
                    // A later change to these volumes (a combine built on this one, an organize) makes this Undo's
                    // rows not the ones to put back, and writing them would take that change's chapters too. A move
                    // that added no row (a split-out series combined back home) shows that only in its folders.
                    val now = localVolumes.all(type)
                    if (!now.containsAll(add) || now.filterTo(HashSet()) { it.folder in handed } != left) {
                        return@undo LocalMoveResult.Failed(IllegalStateException("The volumes changed since"))
                    }
                    try {
                        chapterMoves.regroup(
                            type,
                            series,
                            remove = add,
                            add = remove,
                            volumes = volumes.map { it.back },
                        ) { chapterVolumeUnit(type, it) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        return@undo LocalMoveResult.Failed(e)
                    }
                    removal.restore(tookOut)
                    joining?.let { removal.await(listOf(it)) }
                    (relist + leaving).filter { it != joining }.forEach { relist(type, it) }
                    LocalMoveResult.Moved
                }
            },
            finishing = { removal.finish(tookOut.keys.toList()) },
            appScope = appScope,
            created = joining,
        )
    }

    private fun removal(type: ContentType): EntryLibraryRemoval =
        if (type == ContentType.NOVELS) removeNovels else removeManga

    private suspend fun addToLibrary(type: ContentType, id: Long) {
        if (type == ContentType.NOVELS) {
            val novel = novelRepository.getById(id) ?: return
            novelAdder.addToBatch(NovelItem(novel.title, novel.url), LocalNovelSource.ID, emptyList())
        } else {
            mangaAdder.addToBatch(id, emptyList())
        }
    }

    /**
     * Lists series [id]'s chapters again from its files. A failure leaves the list as it was, since the change it
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
                syncRules,
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
     * What Organize files gives [series] from its [title] and volume [labels] (group key to label), the series'
     * name made free of every folder, every other series of [type] (one in the database that is no longer in the
     * library still holds its address) and every folder a group's rows name. A folder another series lists volumes of keeps its name,
     * since that series' chapters are addressed by it.
     */
    suspend fun organizePlan(
        type: ContentType,
        series: CombinableSeries,
        title: String,
        labels: List<Pair<String, String>>,
    ) =
        localFileNames(type, series.folder, title, labels).let { names ->
            if (LocalGroups(
                    localVolumes.all(type),
                ).isLent(series.folder)
            ) {
                return@let names.copy(series = names.series.copy(to = series.folder))
            }
            val onDisk = storageManager.localRoot(type)?.listOrNull(context)?.mapNotNull { it.name }.orEmpty()
            // Its own name is its own to keep, in any letter case.
            val held = (onDisk + localNamesHeld(type, chapterMoves, localVolumes))
                .filterNotTo(HashSet()) { it.equals(series.folder, ignoreCase = true) }
            val free = freeName(names.series.to, held)
            names.copy(series = names.series.copy(to = free), taken = names.series.to.takeIf { it != free })
        }

    /**
     * Whether Organize files can run on [series]: the provider must move and rename documents, which many do not,
     * and the action is hidden where it cannot.
     */
    suspend fun canOrganize(type: ContentType, series: CombinableSeries): Boolean {
        val parent = storageManager.localRoot(type) ?: return false
        val folders = LocalGroups(localVolumes.all(type)).foldersOf(series.folder)
        return folders.isNotEmpty() && folders.all { parent.findFile(it)?.canMoveAndRename(context) == true }
    }

    /**
     * Series [id]'s folder on disk, or for a loose book the folder it sits in. Asked as `isFile`: a failed query
     * answers false to both questions, and reading that as "a loose book" would hand a caller the local folder
     * itself, where a volume's name can be another series'.
     */
    suspend fun folderOf(type: ContentType, id: Long): UniFile? {
        val series = series(type, listOf(id)).firstOrNull() ?: return null
        val parent = storageManager.localRoot(type) ?: return null
        return parent.findFile(series.folder)?.let { if (it.isFile) parent else it }
    }

    /**
     * Deletes series [id]'s volumes [keys], a manga volume folder with all it holds or a book, wherever the group
     * keeps it, for good, and lists the series again so their chapters leave it. Stops at the first that will not
     * delete. A manga folder's loose chapters are no one thing to delete, and stop it too.
     */
    suspend fun deleteVolumes(type: ContentType, id: Long, keys: List<String>): LocalMoveResult = onAppResult {
        val series = series(type, listOf(id)).firstOrNull()
            ?: return@onAppResult LocalMoveResult.Failed(IllegalStateException("No folder"))
        seriesLocks.withSeries(type, series.folder) {
            val parent =
                storageManager.localRoot(type)
                    ?: return@withSeries LocalMoveResult.Failed(IllegalStateException("No folder"))
            // Only a volume this call deleted counts: one it could not find may be there all the same, since a
            // failed query reads as absent, and forgetting its kept state would then lose it for good.
            val gone = keys.takeWhile { key ->
                val (folder, volume) = localGroupUnit(series.folder, key)
                volume != VolumeOwner.LOOSE &&
                    containerOf(type, parent, folder, volume)?.findFile(volume)?.deleteTree() == true
            }
            val failed = keys.getOrNull(gone.size)
            val units = gone.associateWith { localGroupUnit(series.folder, it) }
            val deleted = units.mapValues { (_, unit) -> volumeChapterPrefix(type, unit.first, unit.second) }
            // A manga key may name a loose chapter that Each its own shows as a volume, addressed by itself.
            val chapters = if (type == ContentType.NOVELS) {
                emptyList()
            } else {
                units.values.map { (folder, volume) -> localChapterUrl(folder, VolumeOwner.LOOSE, volume) }
            }
            val ownerRows = localVolumes.all(type).filter { (it.folder to it.volume) in units.values }
            // Their saved order and names would otherwise wait at those names for whatever is put there next.
            chapterMoves.forgetVolumes(type, id, series.folder, deleted, ownerRows, chapters)
            // The listing removes their rows and keeps their state, as for any file gone; the user chose to
            // delete these, so what is put at those names next starts unread.
            relist(type, id)
            tombstones.forget(type, id, deleted.values.toList(), chapters)
            failed?.let { LocalMoveResult.Failed(IllegalStateException("Could not delete $it")) }
                ?: LocalMoveResult.Moved
        }
    }

    /**
     * Takes [member], a folder of [series]' group renamed or removed outside the app, out of the group, in one
     * regrouping: its chapters' rows and kept states go to the member's own series, as a combine's Undo hands them
     * back, so their reading state is there once the folder is back and listed; with no series of its own they stay
     * and the next listing keeps their state as for any file gone. Refused while the folder is in the local folder.
     */
    suspend fun removeMember(type: ContentType, series: CombinableSeries, member: String): LocalMoveResult =
        onAppResult {
            seriesLocks.withSeries(type, series.folder) {
                val names = storageManager.localRoot(type)?.listOrNull(context)?.mapNotNull { it.name }
                    ?: return@withSeries missing(series)
                val rows = localVolumes.all(type).filter { it.folder == member && it.owner == series.folder }
                if (member in names || rows.isEmpty()) {
                    return@withSeries LocalMoveResult.Failed(IllegalStateException("$member is not a missing member"))
                }
                try {
                    handBackRows(type, series, rows)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@withSeries LocalMoveResult.Failed(e)
                }
                relist(type, series.id)
                LocalMoveResult.Moved
            }
        }

    /**
     * Hands every volume each of [ids] lists from other folders back to the folders' own series, once it has left
     * the library for good; a split-out series' own marker goes with them. Those series list again, so the
     * folders show in Browse and their chapters carry their reading state.
     */
    override suspend fun handBack(type: ContentType, ids: List<Long>) {
        val local = when (type) {
            ContentType.NOVELS -> ids.mapNotNull { id -> novelRepository.getById(id) }
                .filter { LocalNovelSource.isLocal(it.source) }.map { CombinableSeries(it.id, it.title, it.url) }
            else -> ids.map { id -> mangaRepository.getMangaById(id) }
                .filter { it.source == LocalSource.ID }.map { CombinableSeries(it.id, it.title, it.url) }
        }
        for (owner in local) {
            seriesLocks.withSeries(type, owner.folder) {
                val rows = localVolumes.all(type).filter { it.owner == owner.folder }
                if (rows.isEmpty()) return@withSeries
                handBackRows(type, owner, rows)
                rows.map { it.folder }.distinct().filter { it != owner.folder }
                    .mapNotNull { chapterMoves.localSeriesId(type, it) }.forEach { relist(type, it) }
            }
        }
    }

    /**
     * Takes [rows] of [owner] out of the group: the chapters' rows and kept states go to each folder's own series
     * where it has one, as a combine's Undo hands them back, and the volumes' saved order and names go with them,
     * keyed as their own folder keys them. Called under the series' lock.
     */
    private suspend fun handBackRows(type: ContentType, owner: CombinableSeries, rows: List<VolumeOwner>) {
        val members = rows.mapTo(HashSet()) { it.folder } - owner.folder
        val volumes = localVolumes.loadedVolumes(type)[owner.folder].orEmpty().mapNotNull { row ->
            val (folder, volume) = localGroupUnit(owner.folder, row.key)
            VolumeRowMove(owner.folder, row.key, folder, volume, row.position).takeIf { folder in members }
        }
        // The owner's chapters are the ones that move; each goes to the series its folder now lists under.
        chapterMoves.regroup(type, mapOf(owner.folder to owner.id), rows, emptyList(), volumes) {
            chapterVolumeUnit(type, it)
        }
    }

    private fun containerOf(type: ContentType, parent: UniFile, folder: String, volume: String): UniFile? =
        localVolumeContainer(type, folder, volume).fold(parent as UniFile?) { dir, name ->
            dir?.findFile(name)?.takeUnless { it.isFile }
        }

    /** What every chapter address of volume [key] (a manga's folder, a novel's book) in [folder] starts with. */
    private fun volumeChapterPrefix(type: ContentType, folder: String, key: String): String = when (type) {
        ContentType.NOVELS -> LocalNovelSource.volumePathPrefix(folder, key)
        else -> localChapterUrl(folder, key, "")
    }

    private suspend fun onAppResult(work: suspend () -> LocalMoveResult) = appScope.async {
        blockedAs({ it }, work)
    }.await()

    /**
     * Moves [series]' files, wherever its group keeps them, into its own folder named as [names] gives them, with
     * every chapter's address and what is keyed by it, so read state stays. The rows go first and the files one
     * provider move or rename at a time after them, journaled; a step that fails puts back the steps before it.
     */
    suspend fun organizeFiles(
        type: ContentType,
        series: CombinableSeries,
        names: LocalFileNames,
        title: String? = null,
    ): LocalVolumeMove = onApp {
        val result = seriesLocks.withSeries(type, listOf(series.folder, names.series.to)) {
            // A folder that cannot be listed whole fails the plan before anything is written.
            val plan = try {
                organizePlanFor(type, series, names)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withSeries null to LocalMoveResult.Failed(e)
            } ?: return@withSeries null to missing(series)
            plan to organizer.run(type, plan)
        }
        val (plan, outcome) = result
        if (plan == null || outcome !is LocalMoveResult.Moved) return@onApp failed(outcome)
        // A name chosen in place of the title becomes the title, so the two stay in step for both types.
        val entry = EntryId.of(type, series.id)
        val titleBefore = title?.let { setEntryTitle.await(entry, it) }
        relist(type, series.id)
        val undoing = suspend {
            seriesLocks.withSeries(type, plan.folders()) {
                // As for a regroup's Undo: a later change to the group's rows or address is not this one's to undo.
                // A series since given volumes of this folder addresses them by its name, which the Undo changes back.
                val now = localVolumes.all(type)
                val same = now.containsAll(plan.ownersAfter) &&
                    !LocalGroups(now).isLent(plan.toSeries) &&
                    series(type, listOf(series.id)).singleOrNull()?.folder == plan.toSeries
                if (same) {
                    organizer.run(type, plan.undoing())
                } else {
                    LocalMoveResult.Failed(IllegalStateException("The files changed since"))
                }
            }.also {
                if (it !is LocalMoveResult.Moved) return@also
                if (title != null) setEntryTitle.await(entry, titleBefore)
                relist(type, series.id)
            }
        }
        LocalVolumeMove(outcome, undoing, {}, appScope)
    }

    /**
     * The plan that moves every volume [series] lists into its folder [names] names (made first when the series has
     * none, or is a loose book that now holds several), each named as [names] gives it: a manga volume folder or a
     * book is renamed in its own folder, then moved; a manga folder's loose chapters get a volume folder of their own
     * when the series holds more than them. Read from a fresh listing, so only what the series lists moves.
     */
    private suspend fun organizePlanFor(type: ContentType, series: CombinableSeries, names: LocalFileNames): Organize? {
        val root = storageManager.localRoot(type) ?: return null
        val own = series.folder
        val target = names.series.to
        val ownEntry = root.findFile(own)
        val rows = localVolumes.all(type)
        val groups = LocalGroups(rows)
        val listed = listedUrls(type, series)
        val units = listed.mapNotNull { chapterVolumeUnit(type, it) }.distinct()
        val multi = units.size > 1
        val wanted = names.volumes.associate { it.from to it.to }
        val loneBook = type == ContentType.NOVELS && ownEntry?.isFile == true && !multi
        val needsFolder = !loneBook && (groups.hasNoFolder(own) || ownEntry?.isFile == true)
        if (ownEntry == null && !groups.hasNoFolder(own)) return null
        if (own != target && groups.isLent(own)) return null
        val base = if (needsFolder) listOf(target) else listOf(own)
        val steps = mutableListOf<FileStep>(FileStep.Rows())
        if (needsFolder) steps += FileStep.MakeFolder(base)
        // Names already in the folder the volumes land in, which a moved or renamed volume must not meet.
        val taken = if (needsFolder || loneBook) {
            mutableSetOf()
        } else {
            ownEntry?.listOrNull(context)?.mapNotNullTo(mutableSetOf()) { it.name } ?: return null
        }
        val rules = mutableListOf<UrlRule>()
        val keys = HashMap<String, String>()
        // The volumes this plan moves; any other the series lists keeps its owner row.
        val moved = HashSet<Pair<String, String>>()
        for ((folder, volume) in units) {
            val key = localGroupKey(own, folder, volume)
            if (loneBook) {
                if (own != target) steps += FileStep.Rename(listOf(own), target)
                rules += UrlRule(own, own, target, target)
                break
            }
            val asked = wanted[key] ?: continue
            if (type != ContentType.NOVELS && volume == VolumeOwner.LOOSE && folder == own && !multi) continue
            val inPlace = folder == own && !needsFolder && volume == asked
            val name = if (inPlace) asked else freeName(asked, taken)
            val container = localVolumeContainer(type, folder, volume)
            if (type != ContentType.NOVELS && volume == VolumeOwner.LOOSE) {
                steps += FileStep.MakeFolder(base + name)
                // One by one, each with its own address rule: a chapter the listing hid stays, and so does its row.
                listed.filter { chapterVolumeUnit(type, it) == folder to volume }.map { it.substringAfter('/') }
                    .distinct().forEach { file ->
                        steps += FileStep.Move(container + file, base + name)
                        rules += UrlRule(folder, volume, target, name, file)
                    }
            } else {
                if (volume != name) steps += FileStep.Rename(container + volume, name)
                if (container != base) steps += FileStep.Move(container + name, base)
                rules += UrlRule(folder, volume, target, name)
            }
            if (key != name) keys[key] = name
            moved += folder to volume
        }
        if (!needsFolder && !loneBook && own != target) steps += FileStep.Rename(listOf(own), target)
        val before = rows.filter { it.owner == own }
        // A folder the series held whole, the loose chapters of one, and a volume this plan does not move (the
        // listing missed it) keep listing under it, so what is left there stays with its rows.
        val after = before.filter {
            it.folder != own &&
                (it.volume == VolumeOwner.WHOLE || it.volume == VolumeOwner.LOOSE || (it.folder to it.volume) !in moved)
        }
            .map { it.copy(owner = target) }
        return Organize(
            entryId = series.id,
            root = root.uri.toString(),
            fromSeries = own,
            toSeries = target,
            volumeKeys = keys,
            urls = rules,
            ownersBefore = before,
            ownersAfter = after,
            steps = steps,
        )
    }

    /** Every chapter address [series] lists now, read from its files under the lock the caller holds. */
    private suspend fun listedUrls(type: ContentType, series: CombinableSeries): List<String> = when (type) {
        ContentType.NOVELS -> localNovels.parseNovel(series.folder).chapters.orEmpty().map { it.path }
        else -> localManga.getMangaUpdate(
            SManga.create().apply {
                url = series.folder
                title = series.folder
            },
            emptyList(),
            fetchDetails = false,
            fetchChapters = true,
        ).chapters.map { it.url }
    }

    private fun failed(result: LocalMoveResult) = LocalVolumeMove(result, { result }, {}, appScope)

    private fun taken(folder: String) = LocalMoveResult.Failed(IllegalStateException("$folder is already taken"))

    private fun missing(series: CombinableSeries) =
        LocalMoveResult.Failed(IllegalStateException("${series.title}'s files are not in the local folder"))

    /**
     * The saved order and names of volumes joining [kept], read before they move. Each series that joins
     * is placed after the ones before it, kept order first; a series with no saved order of its own, or
     * joining one with none, keeps none, so its volumes sort among the rest as they come.
     */
    private class VolumeRows(
        private val saved: Map<String, List<LocalVolume>>,
        private val kept: String,
    ) {
        val moves = mutableListOf<VolumeRowPair>()
        private var from = kept
        private var base: Long? = 0L
        private var next: Long? = saved[kept]?.mapNotNull { it.position }?.maxOrNull()?.plus(1)

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
                VolumeRowMove(from, key, kept, toKey, position),
                VolumeRowMove(kept, toKey, from, key, row.position),
            )
        }
    }

    private class VolumeRowPair(val forward: VolumeRowMove, val back: VolumeRowMove)
}

/** A change to a series waiting for an organize's repair fails, as one over an unreadable folder does. */
private suspend fun <T> blockedAs(failed: (LocalMoveResult) -> T, work: suspend () -> T): T = try {
    work()
} catch (e: LocalSeriesBlockedException) {
    failed(LocalMoveResult.Failed(e))
}
