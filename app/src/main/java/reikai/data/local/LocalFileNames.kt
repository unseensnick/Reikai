package reikai.data.local

import eu.kanade.tachiyomi.util.storage.DiskUtil
import reikai.domain.library.ContentType
import reikai.novel.export.epubFileName
import reikai.storage.freeNames

/**
 * A local series' names on disk, [from] each one now [to] what Organize files gives it. [taken] is the series
 * name it wanted when something already held it, so [series] carries a free one instead.
 */
data class LocalFileNames(val series: Rename, val volumes: List<Rename>, val taken: String? = null) {
    data class Rename(val from: String, val to: String) {
        val changes get() = from != to

        fun reversed() = Rename(to, from)
    }

    val changes get() = series.changes || volumes.any { it.changes }
}

/**
 * The names Organize files gives local series [folder] from the [title] and volume [labels] (group key to label)
 * on screen. The folder takes the title, and every volume, a manga volume folder or a book, `<title> - <label>`,
 * one format for both types. A loose book with no other volume is the series itself and takes the title.
 */
fun localFileNames(
    type: ContentType,
    folder: String,
    title: String,
    labels: List<Pair<String, String>>,
): LocalFileNames {
    val base = DiskUtil.buildValidFilename(title)
    val loneBook = type == ContentType.NOVELS && folder.endsWith(EPUB, ignoreCase = true) && labels.size <= 1
    val series = LocalFileNames.Rename(folder, if (loneBook) epubFileName(title) else base)
    if (loneBook) return LocalFileNames(series, emptyList())
    val taken = mutableSetOf<String>()
    val volumes = labels.map { (key, label) ->
        // A book's name keeps room for its extension, so a long title is cut before ".epub", never through it.
        val name = if (type == ContentType.NOVELS) {
            epubFileName("$title - $label")
        } else {
            DiskUtil.buildValidFilename("$title - $label")
        }
        LocalFileNames.Rename(key, freeName(name, taken))
    }
    return LocalFileNames(series, volumes)
}

/** [name], or "name (2)" and up when [taken] holds it, taken for the next caller. */
internal fun freeName(name: String, taken: MutableSet<String>): String =
    freeNames(name).first { candidate -> taken.none { it.equals(candidate, ignoreCase = true) } }.also { taken += it }

internal const val EPUB = ".epub"
