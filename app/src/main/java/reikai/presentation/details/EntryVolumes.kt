package reikai.presentation.details

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import mihon.core.metro.AppCoroutineScope
import reikai.data.local.CombinableSeries
import reikai.data.local.CombineLocalVolumes
import reikai.data.local.LocalFileNames
import reikai.data.local.LocalMoveResult
import reikai.data.local.LocalSeriesRelink
import reikai.data.local.LocalVolumeMove
import reikai.data.local.organizedLabel
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.LooseFiles
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.chapter.inUserOrder
import reikai.domain.chapter.savedVolume
import reikai.domain.chapter.volumeNumberIn
import reikai.domain.chapter.volumeRuns
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.local.VolumeOwner
import reikai.domain.local.localGroupKey
import reikai.domain.local.localGroupUnit
import reikai.domain.local.localShownVolumeKey
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localChapterUnit
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

/**
 * Where a chapter sits in a local series split into volumes: its [series] folder, its volume's [key], the
 * file or folder its address names, the [label] a header shows, and the [prefix] that names the volume in
 * front of its title. A manga chapter loose beside the volume folders also carries its [loose] file and title.
 */
@Immutable
data class ChapterVolume(
    val series: String,
    val key: String,
    val label: VolumeLabel,
    val prefix: String,
    val loose: LooseChapter? = null,
) {
    /** [title] without the volume in front, for a row under its volume's header. */
    fun titleOf(title: String) = title.removePrefix(prefix)

    /**
     * This chapter's volume as [mode] shows a loose file: the loose files' one volume, a volume of the file alone
     * (keyed by its own name, as a volume folder is), or none, which leaves the series without headers.
     */
    fun under(mode: LooseFiles?): ChapterVolume? {
        val loose = loose ?: return this
        val folder = localGroupUnit(series, key).first
        val shown = localShownVolumeKey(series, folder, VolumeOwner.LOOSE, loose.file, mode) ?: return null
        return if (shown == key) this else copy(key = shown, label = VolumeLabel.Name(loose.title))
    }

    /**
     * This chapter's volume as its series heads it, on the details screen and in both readers' chapter sheets: under
     * the series' [loose] files setting, then under the name the user gave it in [saved].
     */
    fun shown(loose: Map<String, LooseFiles>, saved: Map<String, List<LocalVolume>>): ChapterVolume? =
        under(loose[series])?.named(saved)

    /** This volume under the name the user gave it in [saved], if they gave it one. */
    fun named(saved: Map<String, List<LocalVolume>>): ChapterVolume =
        saved.savedVolume(series, key)?.label?.let { copy(label = VolumeLabel.Name(it)) } ?: this
}

/** A manga chapter loose in a folder: its [file] name, and its [title] without the folder in front. */
@Immutable
data class LooseChapter(val file: String, val title: String)

sealed interface VolumeLabel {
    /** "Vol. 3", the number the volume's name states. */
    data class Number(val number: Double) : VolumeLabel

    /** A volume whose name states no number, shown as it is named. */
    data class Name(val name: String) : VolumeLabel

    /** A manga series' own loose chapters, shown as Extras until the user names them. */
    data object Loose : VolumeLabel
}

/**
 * A local manga chapter's volume in series [owner], the address of the series that lists it: the volume folder its
 * address names, or its folder's loose chapters, each by its key in the group ([localGroupKey]). A loose chapter of
 * another folder is led by that folder's name, as the local source names it. A volume folder of the series' own
 * folder is labelled without the series' name Organize files put in front of it.
 */
internal fun mangaChapterVolume(owner: Manga?, url: String, name: String): ChapterVolume? {
    if (owner?.source != LocalSource.ID) return null
    val (folder, volume) = localChapterUnit(url)
    val key = localGroupKey(owner.url, folder, volume)
    if (volume == VolumeOwner.LOOSE) {
        val prefix = if (folder == owner.url) "" else "$folder - "
        val file = url.split('/').last { it.isNotEmpty() }
        val label = if (folder == owner.url) VolumeLabel.Loose else VolumeLabel.Name(folder)
        return ChapterVolume(owner.url, key, label, prefix, LooseChapter(file, name.removePrefix(prefix)))
    }
    val shown = if (folder == owner.url) organizedLabel(volume, owner.title, owner.url) else volume
    return ChapterVolume(owner.url, key, labelOf(shown), "$volume - ")
}

/** A local novel chapter's volume in series [owner]: the EPUB its address names, labelled as the source names it. */
internal fun novelChapterVolume(owner: String?, url: String, name: String): ChapterVolume? {
    val (folder, book) = LocalNovelSource.parseChapterPath(url) ?: return null
    val series = owner ?: folder
    val key = localGroupKey(series, folder, book)
    val named = name.substringBefore(" - ", "")
    return ChapterVolume(series, key, labelOf(named.ifEmpty { book }), if (named.isEmpty()) "" else "$named - ")
}

private fun labelOf(name: String) = volumeNumberIn(name)?.let(VolumeLabel::Number) ?: VolumeLabel.Name(name)

/**
 * A local series' volumes as the user arranges them: the chip each series was left on, and the order and
 * names its volumes were given. Writes run on [appScope], so closing the screen does not cut one off.
 */
@Inject
@SingleIn(AppScope::class)
class VolumePicking(
    private val picks: VolumePickRepository,
    private val volumes: LocalVolumeRepository,
    private val files: VolumeFiles,
    private val combine: CombineLocalVolumes,
    private val relink: LocalSeriesRelink,
    @AppCoroutineScope private val appScope: CoroutineScope,
) {
    suspend fun library(type: ContentType): List<CombinableSeries> = combine.library(type)

    suspend fun seriesFolder(entry: EntryId) = combine.folderOf(entry.contentType, entry.rawId)

    /** Deletes [entry]'s volumes [keys] from the device, for good. */
    suspend fun deleteVolumes(entry: EntryId, keys: List<String>): LocalMoveResult =
        combine.deleteVolumes(entry.contentType, entry.rawId, keys)

    suspend fun series(entry: EntryId): CombinableSeries? =
        combine.series(entry.contentType, listOf(entry.rawId)).firstOrNull()

    /** Moves [others] into [series] as its volumes. */
    suspend fun addVolumes(
        type: ContentType,
        series: CombinableSeries,
        others: List<CombinableSeries>,
    ): LocalVolumeMove =
        combine.combine(type, series, others)

    /** Takes [series]' volumes [keys] out into one series of their own named [title]. */
    suspend fun splitOut(type: ContentType, series: CombinableSeries, keys: List<String>, title: String) =
        combine.splitOut(type, series, keys, title)

    suspend fun cover(type: ContentType, series: String, key: String): Any? = files.cover(type, series, key)

    suspend fun description(type: ContentType, series: String, key: String): String? =
        files.description(type, series, key)

    fun picks(type: ContentType): StateFlow<Map<Long, String>> = picks.picks(type)

    fun saved(type: ContentType): StateFlow<Map<String, List<LocalVolume>>> = volumes.volumes(type)

    /** How each local manga series shows its loose files; a novel has none. */
    fun looseFiles(): StateFlow<Map<String, LooseFiles>> = volumes.looseFiles()

    /** Shows [series]' loose files as [mode], then [relist]s, since the source lists their order by it. */
    fun setLooseFiles(series: String, mode: LooseFiles, relist: () -> Unit) {
        appScope.launchIO {
            volumes.setLooseFiles(series, mode)
            withUIContext { relist() }
        }
    }

    fun pick(entry: EntryId, volumeKey: String?) {
        appScope.launchIO { picks.set(entry.contentType, entry.rawId, volumeKey) }
    }

    /** Orders [series]' volumes as [keys] lists them, then [relist]s, since the source lists in that order. */
    fun reorder(type: ContentType, series: String, keys: List<String>, relist: () -> Unit) {
        appScope.launchIO {
            volumes.setOrder(type, series, keys)
            withUIContext { relist() }
        }
    }

    fun rename(type: ContentType, series: String, key: String, label: String?) {
        appScope.launchIO { volumes.setLabel(type, series, key, label) }
    }

    /** What Organize files gives [entry]'s files from its [title] and volume [labels], with the series it moves. */
    suspend fun renamePlan(entry: EntryId, title: String, labels: List<Pair<String, String>>) =
        series(entry)?.let { it to combine.organizePlan(entry.contentType, it, title, labels) }

    /** Moves [series]' files into its folder and names them as [names] gives them. */
    suspend fun renameFiles(type: ContentType, series: CombinableSeries, names: LocalFileNames, title: String?) =
        combine.organizeFiles(type, series, names, title)

    /** The folders of [entry]'s group renamed or removed outside the app, which its screen asks about. */
    suspend fun missingMembers(entry: EntryId): List<String> = relink.missingMembers(entry.contentType, entry.rawId)

    /** The folders [entry]'s missing [member] may now be, by the chapter files they hold. */
    suspend fun memberCandidates(entry: EntryId, member: String) =
        relink.memberCandidates(entry.contentType, entry.rawId, member)

    /** Every folder (or book) in the local folder [entry]'s missing [member] could be linked to by hand. */
    suspend fun memberChoices(entry: EntryId, member: String) = relink.memberChoices(entry.contentType, member)

    /** Links [entry]'s missing [member] to [to]. */
    suspend fun relinkMember(entry: EntryId, member: String, to: String): LocalMoveResult =
        relink.relinkMember(entry.contentType, entry.rawId, member, to)

    /** Takes [entry]'s missing [member] out of its group. */
    suspend fun removeMember(entry: EntryId, member: String): LocalMoveResult {
        val own = series(entry) ?: return LocalMoveResult.Failed(IllegalStateException("No such series"))
        return combine.removeMember(entry.contentType, own, member)
    }

    /** Whether [entry]'s group is refused until an organize or an import Replace that cannot finish is resolved. */
    suspend fun waitsForRepair(entry: EntryId): Boolean =
        series(entry)?.let { combine.waitsForRepair(entry.contentType, it) } == true

    /** Forgets what [entry] waits on, moving no file. */
    suspend fun forgetUnfinished(entry: EntryId) {
        val own = series(entry) ?: return
        combine.forgetUnfinished(entry.contentType, own)
    }

    /** Whether [entry]'s storage can move and rename files, without which Organize files is not offered. */
    suspend fun canOrganize(entry: EntryId): Boolean = withIOContext {
        series(entry)?.let { combine.canOrganize(entry.contentType, it) } == true
    }

    // App-wide, since a request made on one screen (a combine in the library, a split) opens on another.
    private val organizeRequests = MutableStateFlow(emptyMap<EntryId, OrganizeRequest>())

    /**
     * Opens Organize files on [entry]'s screen, now or once it opens, with [title] for the files where Edit info
     * asked for one, and [undo] for the move that led there, whose screen the user is leaving.
     */
    fun requestOrganize(entry: EntryId, title: String? = null, undo: VolumeMoveUndo? = null) {
        organizeRequests.update { it + (entry to OrganizeRequest(title, undo)) }
    }

    /**
     * Opens [entry]'s screen on Organize files' preview, through [push], offering [move]'s Undo there: leaving this
     * screen ends its own snackbar.
     */
    fun organizeOn(entry: EntryId, move: LocalVolumeMove, message: String, push: (NavKey) -> Unit) {
        requestOrganize(entry, undo = VolumeMoveUndo(move, message))
        push(entry.detailsRoute())
    }

    /** The Organize files requests for [entry], each taken once. */
    fun organizeRequests(entry: EntryId): Flow<OrganizeRequest> = organizeRequests.mapNotNull { it[entry] }
        .onEach { taken -> organizeRequests.update { if (it[entry] === taken) it - entry else it } }
}

/**
 * Organize files asked for from elsewhere, with the [title] Edit info gave the files, if it gave one, and the [undo]
 * of the move that asked for it, if one did.
 */
class OrganizeRequest(val title: String?, val undo: VolumeMoveUndo? = null)

/** A local volume move whose Undo the screen it opened offers, as [message]. */
class VolumeMoveUndo(val move: LocalVolumeMove, val message: String)

/** A local series' list grouped by volume: the [chips] in volume order, the [picked] one, and the rows. */
@Immutable
data class VolumeView(
    val chips: List<EntryChapterListItem.Volume>,
    val picked: String?,
    val items: List<EntryChapterListItem>,
) {
    /** The volumes that show one label, each set in chip order, which the series' screen asks about. */
    val duplicates: List<List<EntryChapterListItem.Volume>> get() = chips.groupBy { it.label }.values.filter {
        it.size >
            1
    }
}

/**
 * [this] under a header per volume, only [picked]'s when it names one, each row titled without its volume
 * in front; the rows stay unheaded where volumes cannot head them ([volumeRuns]), and the view is null where
 * the series has fewer than two volumes or a row has none. Chips follow the volumes' numbers when every one
 * states a number, else the list's order. The chips, which the volume dialogs act on, are every one of
 * [every], the volumes of the series' unfiltered list, so a chapter filter or sort hides none from them.
 */
fun List<EntryChapterListItem>.inVolumes(
    picked: String?,
    saved: Map<String, List<LocalVolume>>,
    every: List<ChapterVolume>,
    loose: Map<String, LooseFiles> = emptyMap(),
): VolumeView? {
    val rows = filterIsInstance<EntryChapterListItem.Chapter>()
        .map { row -> row.copy(volume = row.volume?.shown(loose, saved) ?: return null) }
    val runs = rows.volumeRuns { it.volume?.key }
    val headers = (
        runs ?: rows.groupBy {
            it.volume!!.key
        }.toList()
        ).volumeHeaders({ it.volume!! }, { it.id }, { it.read })
    val series = headers.firstOrNull()?.series ?: every.firstNotNullOfOrNull { it.shown(loose, saved) }?.series
        ?: return null
    val shownByKey = headers.associateBy { it.key }
    val all = every.volumesOf(series, saved, loose).map { shownByKey[it.key] ?: it.emptyChip() }.ifEmpty { headers }
    if (all.size < 2) return null
    val chips = all.inChipOrder(saved[series])
    // Numbered in chip order.
    val duplicates = chips.duplicateIndexByKey()
    fun EntryChapterListItem.Volume.numbered() = copy(duplicate = duplicates.getValue(key))
    val shown = picked?.takeIf { key -> headers.any { it.key == key } }
    val items = when {
        runs != null -> runs.zip(headers).filter { (_, header) -> shown == null || header.key == shown }
            .flatMap { (run, header) ->
                listOf(header.numbered()) + run.second.map { it.copy(name = it.volume!!.titleOf(it.name)) }
            }
        shown == null -> this
        else -> rows.filter { it.volume!!.key == shown }
    }
    return VolumeView(chips.map { it.numbered() }, shown, items)
}

/**
 * The chip of volume [key] of [series] among the chapter volumes [this], named and numbered as the series' page
 * shows it; null where the series lists no such volume. The volume notes page heads itself with it.
 */
fun List<ChapterVolume>.chipOf(
    series: String,
    key: String,
    saved: Map<String, List<LocalVolume>>,
    loose: Map<String, LooseFiles>,
): EntryChapterListItem.Volume? {
    val chips = volumesOf(series, saved, loose).map { it.emptyChip() }.inChipOrder(saved[series])
    return chips.firstOrNull { it.key == key }?.copy(duplicate = chips.duplicateIndexByKey().getValue(key))
}

/** [series]' volumes among [this], each once, as its page heads them. */
private fun List<ChapterVolume>.volumesOf(
    series: String,
    saved: Map<String, List<LocalVolume>>,
    loose: Map<String, LooseFiles>,
) = mapNotNull { it.shown(loose, saved) }.filter { it.series == series }.distinctBy { it.key }

private fun ChapterVolume.emptyChip() = EntryChapterListItem.Volume(series, key, label, emptyList(), 0)

/** [this] chips in the order their series shows them, given the volumes the user [saved] for it. */
private fun List<EntryChapterListItem.Volume>.inChipOrder(
    saved: List<LocalVolume>?,
): List<EntryChapterListItem.Volume> {
    val numbers = map { (it.label as? VolumeLabel.Number)?.number }
    return when {
        // A series the user ordered follows their order, whatever the numbers say.
        saved.orEmpty().any { it.position != null } -> inUserOrder({ it.key }, saved)
        numbers.all { it != null } -> sortedBy { (it.label as VolumeLabel.Number).number }
        else -> this
    }
}

/** A header per run of [volumeRuns], counting the run's [isRead] rows, for the details list and the reader's sheet. */
fun <T> List<Pair<String, List<T>>>.volumeHeaders(
    volumeOf: (T) -> ChapterVolume,
    idOf: (T) -> Long,
    isRead: (T) -> Boolean,
): List<EntryChapterListItem.Volume> = map { (key, rows) ->
    val volume = volumeOf(rows.first())
    EntryChapterListItem.Volume(volume.series, key, volume.label, rows.map(idOf), rows.count(isRead))
}

/** Each volume's place among those showing its label, in this order, which tells a later one apart: its duplicate. */
fun List<EntryChapterListItem.Volume>.duplicateIndexByKey(): Map<String, Int> =
    groupBy { it.label }.values.flatMap { same -> same.mapIndexed { i, it -> it.key to i } }.toMap()

/** What a local volume's own files hold for its page: a cover the cover loader takes, and a description. */
interface VolumeFiles {
    suspend fun cover(type: ContentType, series: String, key: String): Any?

    suspend fun description(type: ContentType, series: String, key: String): String?
}

/** A manga volume folder's own picture and ComicInfo summary, or a novel book's cover and description. */
@Inject
@ContributesBinding(AppScope::class)
class LocalVolumeFiles(private val manga: LocalSource, private val novels: LocalNovelSource) : VolumeFiles {
    override suspend fun cover(type: ContentType, series: String, key: String): Any? = when (type) {
        ContentType.NOVELS -> novels.volumeCover(series, key)
        else -> withIOContext { manga.volumeCover(series, key)?.uri }
    }

    override suspend fun description(type: ContentType, series: String, key: String): String? = when (type) {
        ContentType.NOVELS -> novels.volumeDescription(series, key)
        else -> manga.volumeDescription(series, key)
    }
}

/**
 * The volumes of [structural] and [volumes] that Delete volumes offers for [keys]. A manga folder's loose files
 * shown as one Extras volume are left out: they are no one thing to delete, so the delete stops at them.
 */
internal fun volumesToDelete(structural: VolumeView?, volumes: VolumeView?, keys: List<String>) =
    (structural?.chips.orEmpty() + volumes?.chips.orEmpty()).distinctBy { it.key }
        .filter { it.key in keys && it.key != VolumeOwner.LOOSE }

/** What a picked volume's own files give its page: a [cover] where it has one, and a [description]. */
@Immutable
data class VolumeOwnFiles(val cover: Any?, val description: String?)

/**
 * This page as a picked volume heads it: the volume's own cover, else the series', and its own description and
 * [notes], none where it has none, since the series' would read as the volume's. [files] is null while they load.
 */
internal fun EntryDetailsScreenState.Loaded.headedBy(files: VolumeOwnFiles?, notes: String) = copy(
    details = details.copy(
        header = files?.cover?.let { details.header.copy(coverModel = it, coverFallbacks = emptyList()) }
            ?: details.header,
        description = files?.description,
        notes = notes,
    ),
)
