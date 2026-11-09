package reikai.presentation.details

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.chapter.volumeNumberIn
import reikai.domain.chapter.volumeRuns
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.novel.local.LocalNovelSource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.source.local.LocalSource

/**
 * Where a chapter sits in a local series split into volumes: its volume's [key], the file or folder its
 * address names, the [label] a header shows, and the [prefix] that names the volume in front of its title.
 */
@Immutable
data class ChapterVolume(val key: String, val label: VolumeLabel, val prefix: String) {
    /** [title] without the volume in front, for a row under its volume's header. */
    fun titleOf(title: String) = title.removePrefix(prefix)
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
    val key = url.split('/').takeIf { it.size == 3 }?.get(1) ?: return null
    return ChapterVolume(key, labelOf(key), "$key - ")
}

/** A local novel chapter's volume: the EPUB its address names, labelled as the source names the chapter. */
internal fun novelChapterVolume(url: String, name: String): ChapterVolume? {
    val key = LocalNovelSource.parseChapterPath(url)?.second ?: return null
    val named = name.substringBefore(" - ", "")
    return ChapterVolume(key, labelOf(named.ifEmpty { key }), if (named.isEmpty()) "" else "$named - ")
}

private fun labelOf(name: String) = volumeNumberIn(name)?.let(VolumeLabel::Number) ?: VolumeLabel.Name(name)

/** The volume chip each local series was left on, and the write that moves it, on [appScope] so it lands. */
class VolumePicking(private val repository: VolumePickRepository, private val appScope: CoroutineScope) {
    fun picks(type: ContentType): StateFlow<Map<Long, String>> = repository.picks(type)

    fun pick(entry: EntryId, volumeKey: String?) {
        appScope.launchIO { repository.set(entry.contentType, entry.rawId, volumeKey) }
    }
}

/** A local series' list grouped by volume: the [chips] in volume order, the [picked] one, and the rows. */
@Immutable
data class VolumeView(
    val chips: List<EntryChapterListItem.Volume>,
    val picked: String?,
    val items: List<EntryChapterListItem>,
)

/**
 * [this] under a header per volume, only [picked]'s when it names one, each row titled without its volume
 * in front; null where volumes cannot head the list ([volumeRuns]). Chips follow the volumes' numbers when
 * every one states a number, else the list's order.
 */
fun List<EntryChapterListItem>.inVolumes(picked: String?): VolumeView? {
    val runs = filterIsInstance<EntryChapterListItem.Chapter>().volumeRuns { it.volume?.key } ?: return null
    val headers = runs.map { (key, rows) ->
        EntryChapterListItem.Volume(key, rows.first().volume!!.label, rows.map { it.id }, rows.count { it.read })
    }
    val numbers = headers.map { (it.label as? VolumeLabel.Number)?.number }
    val chips = if (numbers.all {
            it != null
        }
    ) {
        headers.sortedBy { (it.label as VolumeLabel.Number).number }
    } else {
        headers
    }
    val shown = picked?.takeIf { key -> headers.any { it.key == key } }
    val items = runs.zip(headers).filter { (_, header) -> shown == null || header.key == shown }
        .flatMap { (run, header) -> listOf(header) + run.second.map { it.copy(name = it.volume!!.titleOf(it.name)) } }
    return VolumeView(chips, shown, items)
}
