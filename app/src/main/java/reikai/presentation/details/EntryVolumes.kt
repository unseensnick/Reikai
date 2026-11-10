package reikai.presentation.details

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import reikai.data.local.CombinableSeries
import reikai.data.local.CombineLocalVolumes
import reikai.data.local.LocalFileNames
import reikai.data.local.LocalMoveResult
import reikai.data.local.LocalVolumeMove
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.chapter.inUserOrder
import reikai.domain.chapter.volumeNumberIn
import reikai.domain.chapter.volumeRuns
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.novel.local.LocalNovelSource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.source.local.LocalSource

/**
 * Where a chapter sits in a local series split into volumes: its [series] folder, its volume's [key], the
 * file or folder its address names, the [label] a header shows, and the [prefix] that names the volume in
 * front of its title.
 */
@Immutable
data class ChapterVolume(val series: String, val key: String, val label: VolumeLabel, val prefix: String) {
    /** [title] without the volume in front, for a row under its volume's header. */
    fun titleOf(title: String) = title.removePrefix(prefix)

    /** This volume under the name the user gave it in [saved], if they gave it one. */
    fun named(saved: Map<String, List<LocalVolume>>): ChapterVolume =
        saved[series]?.firstOrNull { it.key == key }?.label?.let { copy(label = VolumeLabel.Name(it)) } ?: this
}

sealed interface VolumeLabel {
    /** "Vol. 3", the number the volume's name states. */
    data class Number(val number: Double) : VolumeLabel

    /** A volume whose name states no number, shown as it is named. */
    data class Name(val name: String) : VolumeLabel
}

/** A local manga chapter's volume: the folder between the series and the chapter in its address. */
internal fun mangaChapterVolume(sourceId: Long, url: String, name: String): ChapterVolume? {
    if (sourceId != LocalSource.ID) return null
    val (series, key) = url.split('/').takeIf { it.size == 3 } ?: return null
    return ChapterVolume(series, key, labelOf(key), "$key - ")
}

/** A local novel chapter's volume: the EPUB its address names, labelled as the source names the chapter. */
internal fun novelChapterVolume(url: String, name: String): ChapterVolume? {
    val (series, key) = LocalNovelSource.parseChapterPath(url) ?: return null
    val named = name.substringBefore(" - ", "")
    return ChapterVolume(series, key, labelOf(named.ifEmpty { key }), if (named.isEmpty()) "" else "$named - ")
}

private fun labelOf(name: String) = volumeNumberIn(name)?.let(VolumeLabel::Number) ?: VolumeLabel.Name(name)

/**
 * A local series' volumes as the user arranges them: the chip each series was left on, and the order and
 * names its volumes were given. Writes run on [appScope], so closing the screen does not cut one off.
 */
class VolumePicking(
    private val picks: VolumePickRepository,
    private val volumes: LocalVolumeRepository,
    private val covers: VolumeCovers,
    private val combine: CombineLocalVolumes,
    private val appScope: CoroutineScope,
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

    suspend fun cover(type: ContentType, series: String, key: String): Any? = covers.cover(type, series, key)

    fun picks(type: ContentType): StateFlow<Map<Long, String>> = picks.picks(type)

    fun saved(type: ContentType): StateFlow<Map<String, List<LocalVolume>>> = volumes.volumes(type)

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

    /** What Rename files gives [entry]'s files from its [title] and volume [labels], with the series it renames. */
    suspend fun renamePlan(entry: EntryId, title: String, labels: List<Pair<String, String>>) =
        series(entry)?.let { it to combine.renamePlan(entry.contentType, it, title, labels) }

    /** Renames [series]' files on disk as [names] gives them. */
    suspend fun renameFiles(type: ContentType, series: CombinableSeries, names: LocalFileNames, title: String?) =
        combine.renameFiles(type, series, names, title)

    private val renameRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The titles Edit info asked the files to take, which the series' screen opens Rename files for. */
    val fileRenameRequests: SharedFlow<String> = renameRequests

    fun requestFileRename(title: String) {
        renameRequests.tryEmit(title)
    }
}

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
 * in front; null where volumes cannot head the list ([volumeRuns]). Chips follow the volumes' numbers when
 * every one states a number, else the list's order.
 */
fun List<EntryChapterListItem>.inVolumes(picked: String?, saved: Map<String, List<LocalVolume>>): VolumeView? {
    val runs = filterIsInstance<EntryChapterListItem.Chapter>().volumeRuns { it.volume?.key } ?: return null
    val headers = runs.map { (key, rows) ->
        val volume = rows.first().volume!!.named(saved)
        EntryChapterListItem.Volume(volume.series, key, volume.label, rows.map { it.id }, rows.count { it.read })
    }
    val savedHere = saved[headers.first().series]
    val numbers = headers.map { (it.label as? VolumeLabel.Number)?.number }
    val chips = when {
        // A series the user ordered follows their order, whatever the numbers say.
        savedHere.orEmpty().any { it.position != null } -> headers.inUserOrder({ it.key }, savedHere)
        numbers.all { it != null } -> headers.sortedBy { (it.label as VolumeLabel.Number).number }
        else -> headers
    }
    // A later volume showing an earlier one's label is told apart by its place among them, in chip order.
    val duplicates = chips.groupBy {
        it.label
    }.values.flatMap { same -> same.mapIndexed { i, it -> it.key to i } }.toMap()
    fun EntryChapterListItem.Volume.numbered() = copy(duplicate = duplicates.getValue(key))
    val shown = picked?.takeIf { key -> headers.any { it.key == key } }
    val items = runs.zip(headers).filter { (_, header) -> shown == null || header.key == shown }
        .flatMap { (run, header) ->
            listOf(header.numbered()) + run.second.map { it.copy(name = it.volume!!.titleOf(it.name)) }
        }
    return VolumeView(chips.map { it.numbered() }, shown, items)
}

/** A local volume's cover, as an image the cover loader takes, or null when the volume has none. */
fun interface VolumeCovers {
    suspend fun cover(type: ContentType, series: String, key: String): Any?
}

/** A manga volume folder's own picture, or a novel book's cover. */
@Inject
class LocalVolumeCovers(private val manga: LocalSource, private val novels: LocalNovelSource) : VolumeCovers {
    override suspend fun cover(type: ContentType, series: String, key: String): Any? = when (type) {
        ContentType.NOVELS -> novels.volumeCover(series, key)
        else -> withIOContext { manga.volumeCover(series, key)?.uri }
    }
}
