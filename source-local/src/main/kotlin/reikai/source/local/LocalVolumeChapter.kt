package reikai.source.local

import reikai.domain.local.VolumeOwner

/**
 * A local manga chapter inside a volume folder, whose address is series/volume/file. A volume's Extras chapter
 * is the volume folder itself, so its [file] is empty. Every reader of that address shape goes through
 * [localVolumeChapter], so a change to the layout is made here once.
 */
data class LocalVolumeChapter(val series: String, val volume: String, val file: String) {
    val url get() = "$series/$volume/$file"
}

/**
 * The folder and volume a manga chapter's file sits in by its [url]: a volume folder, or [VolumeOwner.LOOSE] for a
 * chapter straight in its folder. The folder is where the file is, which in a group need not be the owner's.
 */
fun localChapterUnit(url: String): Pair<String, String> =
    localVolumeChapter(url)?.let { it.series to it.volume } ?: (url.substringBefore('/') to VolumeOwner.LOOSE)

/** [url] as a chapter inside a volume folder, or null for a chapter that sits in the series folder itself. */
fun localVolumeChapter(url: String): LocalVolumeChapter? =
    url.split('/').takeIf { it.size == 3 }?.let { (series, volume, file) -> LocalVolumeChapter(series, volume, file) }
