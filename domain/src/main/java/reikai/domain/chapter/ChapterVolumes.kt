package reikai.domain.chapter

import tachiyomi.domain.chapter.service.ChapterRecognition

/** The volume number [name] states ("Vol 02", "Volume 3", "v4"), read as the chapter recognizer reads one. */
fun volumeNumberIn(name: String): Double? =
    ChapterRecognition.volume.find(name.lowercase())?.groupValues?.get(1)?.toDoubleOrNull()

/**
 * [this] as runs of one volume each, in list order, or null where volumes cannot head the list: fewer
 * than two volumes, a chapter with none, or a volume whose chapters the sort has parted.
 */
fun <T> List<T>.volumeRuns(volumeOf: (T) -> String?): List<Pair<String, List<T>>>? {
    val runs = mutableListOf<Pair<String, MutableList<T>>>()
    for (chapter in this) {
        val volume = volumeOf(chapter) ?: return null
        if (runs.lastOrNull()?.first ==
            volume
        ) {
            runs.last().second += chapter
        } else {
            runs += volume to mutableListOf(chapter)
        }
    }
    return runs.takeIf { it.size > 1 && it.size == it.distinctBy { run -> run.first }.size }
}
