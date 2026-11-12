package reikai.data.local

import eu.kanade.tachiyomi.util.storage.DiskUtil
import reikai.domain.library.ContentType
import reikai.novel.export.epubFileName
import reikai.storage.freeName

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
    val loneBook = isLooseBook(type, folder) && labels.size <= 1
    val series = LocalFileNames.Rename(folder, if (loneBook) epubFileName(title) else base)
    if (loneBook) return LocalFileNames(series, emptyList())
    val taken = mutableSetOf<String>()
    val volumes = labels.map { (key, label) ->
        // A book's name keeps room for its extension, so a long title is cut before ".epub", never through it.
        val name = if (type == ContentType.NOVELS) {
            epubFileName("$title - $label")
        } else {
            organizedVolumeFolder(title, label)
        }
        LocalFileNames.Rename(key, freeName(name, taken))
    }
    return LocalFileNames(series, volumes)
}

/** A manga volume folder's name: the title and label sanitized together, so a title's own trailing dots stay. */
private fun organizedVolumeFolder(title: String, label: String) = DiskUtil.buildValidFilename("$title - $label")

/**
 * The label a volume named [name] in its series' own folder shows: the name less the `<title> - ` Organize files
 * gave it, read against the series' [title] and its [folder] (the title, unless another folder held that name).
 * The title's prefix is read off a name built the way [organizedVolumeFolder] builds one.
 */
fun organizedLabel(name: String, title: String, folder: String): String =
    listOf(organizedVolumeFolder(title, LABEL_PROBE).removeSuffix(LABEL_PROBE), "$folder - ").firstNotNullOfOrNull {
        name.removePrefix(it).takeIf { label -> label != name && label.isNotBlank() }
    } ?: name

private const val LABEL_PROBE = "x"

internal const val EPUB = ".epub"
