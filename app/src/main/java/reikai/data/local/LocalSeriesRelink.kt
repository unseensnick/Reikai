package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournalPlan.UrlRule
import reikai.domain.local.LocalGroups
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeOwner
import reikai.domain.local.localGroupKey
import reikai.domain.local.localGroupUnit
import reikai.domain.local.relinkCandidates
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localVolumeChapter
import reikai.storage.listOrNull
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager

/**
 * A local series renamed outside the app: its address is its folder name, so its refresh fails and its rows
 * sit stranded. [candidates] finds the folders in the local root that hold every chapter file the series'
 * rows name, for the user to pick from, never to be picked for them; [relink] then moves the address and
 * every row keyed by it the way an organize does, under the lock on both names. A loose novel book carries
 * nothing but the name that changed, so it has no candidates. Both content types.
 */
@Inject
class LocalSeriesRelink(
    private val context: Context,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val storageManager: StorageManager,
    private val chapterMoves: LocalChapterMoveRepository,
    private val seriesLocks: LocalSeriesLocks,
    // The owner rows, and the saved volume rows a member's link renames.
    private val owners: LocalVolumeRepository,
) {

    /**
     * The folders series [id] may have been renamed to, or null while its folder is where its address says. Asked
     * from a refresh's failure handler, so a local folder that cannot be listed whole offers nothing and throws
     * nothing; whether the folder is missing is read from that trusted listing, never a document's own query,
     * which a provider hiccup answers "absent" to.
     */
    suspend fun candidates(type: ContentType, id: Long): List<String>? = withIOContext {
        val series = address(type, id) ?: return@withIOContext emptyList()
        val listed = storageManager.localRoot(type)?.listOrNull(context) ?: return@withIOContext emptyList()
        if (listed.any { it.name == series }) return@withIOContext null
        candidatesIn(type, id, series, listed)
    }

    /**
     * The folders to offer in place of a refresh's failure for series [id], or null to show the failure: a series
     * that is not local ([isLocal]), one with no folder holding its files, or the question failing too, which must
     * not throw out of the failure handler that asks it. Both details screens ask it.
     */
    suspend fun offerAfterFailure(type: ContentType, id: Long, isLocal: Boolean): List<String>? =
        if (isLocal) runCatchingCancellable { candidates(type, id) }.getOrNull()?.takeIf { it.isNotEmpty() } else null

    /**
     * Links series [id] to the [folder] the user picked, then [refreshes][refresh] it there, or tells [failed]. The
     * caller runs it on the app scope, so a closed screen does not cut the link off. Both details screens call it.
     */
    suspend fun relinkThenRefresh(
        type: ContentType,
        id: Long,
        folder: String,
        refresh: suspend () -> Unit,
        failed: suspend () -> Unit,
    ) {
        if (relink(type, id, folder) is LocalMoveResult.Moved) refresh() else failed()
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
            val listed = storageManager.localRoot(type)?.listOrNull(context)
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

    /**
     * The folders series [id]'s group lists that the local folder no longer holds: renamed or removed outside the
     * app. Empty where the local folder cannot be listed whole, since a short listing would name present ones.
     */
    suspend fun missingMembers(type: ContentType, id: Long): List<String> = withIOContext {
        val series = address(type, id) ?: return@withIOContext emptyList()
        val listed = storageManager.localRoot(type)?.listOrNull(context) ?: return@withIOContext emptyList()
        LocalGroups(owners.all(type)).missingMembers(series, listed.mapNotNull { it.name }).toList()
    }

    /**
     * Where series [id]'s missing member [member] may have gone: the folders holding every chapter file it lists from
     * there, for the user to pick from, as [candidates] finds a series' own; none another series lists or holds.
     */
    suspend fun memberCandidates(type: ContentType, id: Long, member: String): List<String> = withIOContext {
        val listed = storageManager.localRoot(type)?.listOrNull(context) ?: return@withIOContext emptyList()
        val taken = taken(type)
        candidatesIn(type, id, member, listed).filter { it !in taken }
    }

    /**
     * Every entry of the local folder the user may link [member] to by hand: its folders, or its books for a loose
     * book, none another series lists or holds. Only inside the local folder, which is all a series can address.
     */
    suspend fun memberChoices(type: ContentType, member: String): List<String> = withIOContext {
        val listed = storageManager.localRoot(type)?.listOrNull(context) ?: return@withIOContext emptyList()
        val taken = taken(type)
        val book = isBook(type, member)
        listed.filter { if (book) it.isFile && it.name.orEmpty().endsWith(".epub", true) else it.isDirectory }
            .mapNotNull { it.name }.filter { !it.startsWith('.') && it !in taken }.sorted()
    }

    /**
     * Links series [id]'s missing member [member] to [to]: every row keyed by the member's name, through the same row
     * step Organize files writes, under the lock on both names. Checked again there that [member] is still gone, [to]
     * is there and free, and the series holds [member] whole, since a folder another series lists from too is that
     * series' to link.
     */
    suspend fun relinkMember(type: ContentType, id: Long, member: String, to: String): LocalMoveResult = withIOContext {
        val series = address(type, id)
            ?: return@withIOContext LocalMoveResult.Failed(IllegalStateException("No such series"))
        seriesLocks.withSeries(type, listOf(series, to)) {
            val names = storageManager.localRoot(type)?.listOrNull(context)?.mapNotNull { it.name }
                ?: return@withSeries LocalMoveResult.Failed(IllegalStateException("The local folder cannot be read"))
            val held = owners.all(type).filter { it.folder == member }
            when {
                member in names -> return@withSeries failed("$member is back in the local folder")
                to !in names || to in taken(type) -> return@withSeries failed("$to cannot take $member's place")
                held.any { it.owner != series } || held.none { it.volume == VolumeOwner.WHOLE } ->
                    return@withSeries failed("Another series lists $member's volumes")
            }
            val book = isBook(type, member)
            val keys = volumeKeys(type, id, series).mapNotNull { key ->
                val (folder, volume) = localGroupUnit(series, key)
                if (folder != member) return@mapNotNull null
                key to localGroupKey(series, to, if (book && volume == member) to else volume)
            }.toMap()
            val rules = if (book) listOf(UrlRule(member, member, to, to)) else emptyList()
            chapterMoves.relinkMember(type, SeriesRename(id, series, series, keys), member, to) {
                organizedUrl(type, it, rules, member, to)
            }
            LocalMoveResult.Moved(emptyList())
        }
    }

    // Folders a series lists or holds, which a member cannot be linked to without meeting that series' rows.
    private suspend fun taken(type: ContentType): Set<String> =
        chapterMoves.localAddresses(type) + owners.all(type).flatMap { listOf(it.folder, it.owner) }

    // A novel member that is a loose book, a series of one.
    private fun isBook(type: ContentType, member: String) = type == ContentType.NOVELS && member.endsWith(".epub", true)

    // Every volume key series [id] keeps a saved order, name or picked chip under.
    private suspend fun volumeKeys(type: ContentType, id: Long, series: String): Set<String> =
        owners.loadedVolumes(type)[series].orEmpty().map { it.key }.toSet() +
            listOfNotNull(owners.loadedPicks(type)[id])

    private fun failed(reason: String) = LocalMoveResult.Failed(IllegalStateException(reason))

    /** The candidates among [listed], the local root's trusted listing; a folder that cannot be listed whole is none. */
    private suspend fun candidatesIn(type: ContentType, id: Long, folder: String, listed: List<UniFile>): List<String> {
        // Only the chapters in [folder]: a group's others sit in folders that did not move.
        val paths = chapterUrls(type, id).filter { chapterVolumeUnit(type, it)?.first == folder }
            .mapNotNull { relativePath(type, folder, it) }
        if (paths.isEmpty()) return emptyList()
        val volumes = paths.mapNotNullTo(HashSet()) { it.substringBefore('/', "").lowercase().ifEmpty { null } }
        val folders = listed
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .mapNotNull { folder -> contents(folder, volumes)?.let { folder.name.orEmpty() to it } }
            .toMap()
        return relinkCandidates(paths, folders).filter {
            chapterMoves.localSeriesId(type, it).let { owner -> owner == null || owner == id }
        }
    }

    /**
     * What [dir] holds, relative to it; only the subfolders named in [volumes] are read, one level down. A folder is
     * named bare, as a chapter's address names a chapter that is a folder of pages.
     */
    private fun contents(dir: UniFile, volumes: Set<String>): List<String>? {
        val entries = dir.listOrNull(context) ?: return null
        return entries.flatMap { entry ->
            val name = entry.name.orEmpty()
            when {
                !entry.isDirectory || name.lowercase() !in volumes -> listOf(name)
                else -> listOf(name) +
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
}
