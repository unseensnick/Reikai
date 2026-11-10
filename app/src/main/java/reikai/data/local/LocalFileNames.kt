package reikai.data.local

import eu.kanade.tachiyomi.util.storage.DiskUtil
import reikai.domain.library.ContentType
import reikai.novel.export.epubFileName
import reikai.storage.freeNames

/**
 * A local series' names on disk, [from] each one now [to] what Rename files gives it. [taken] is the series
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
 * The names Rename files gives local series [folder] from the [title] and volume [labels] (key to label) on
 * screen. The folder takes the title. A manga volume folder takes its label, since the series is already its
 * parent's name; a book file takes the title and its label, as a book is often handled away from its folder.
 * A loose book is the series itself and takes the title.
 */
fun localFileNames(
    type: ContentType,
    folder: String,
    title: String,
    labels: List<Pair<String, String>>,
): LocalFileNames {
    val base = DiskUtil.buildValidFilename(title)
    val looseBook = type == ContentType.NOVELS && folder.endsWith(EPUB, ignoreCase = true)
    val series = LocalFileNames.Rename(folder, if (looseBook) epubFileName(title) else base)
    if (looseBook) return LocalFileNames(series, emptyList())
    val taken = mutableSetOf<String>()
    val volumes = labels.map { (key, label) ->
        // A book's name keeps room for its extension, so a long title is cut before ".epub", never through it.
        val name = if (type ==
            ContentType.NOVELS
        ) {
            epubFileName("$title - $label")
        } else {
            DiskUtil.buildValidFilename(label)
        }
        LocalFileNames.Rename(key, freeName(name, taken))
    }
    return LocalFileNames(series, volumes)
}

/** [name], or "name (2)" and up when [taken] holds it, taken for the next caller. */
internal fun freeName(name: String, taken: MutableSet<String>): String =
    freeNames(name).first { candidate -> taken.none { it.equals(candidate, ignoreCase = true) } }.also { taken += it }

internal const val EPUB = ".epub"
