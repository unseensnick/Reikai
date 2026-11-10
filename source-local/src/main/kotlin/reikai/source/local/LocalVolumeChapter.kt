package reikai.source.local

/**
 * A local manga chapter inside a volume folder, whose address is series/volume/file. A volume's Extras chapter
 * is the volume folder itself, so its [file] is empty. Every reader of that address shape goes through
 * [localVolumeChapter], so a change to the layout is made here once.
 */
data class LocalVolumeChapter(val series: String, val volume: String, val file: String) {
    val url get() = "$series/$volume/$file"
}

/** [url] as a chapter inside a volume folder, or null for a chapter that sits in the series folder itself. */
fun localVolumeChapter(url: String): LocalVolumeChapter? =
    url.split('/').takeIf { it.size == 3 }?.let { (series, volume, file) -> LocalVolumeChapter(series, volume, file) }
