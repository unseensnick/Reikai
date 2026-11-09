package reikai.novel.local

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder

/**
 * The volumes of one series in reading order: by their series number when every volume has one, else by
 * file name, so a series where any book lacks a number keeps the order its names give. Ties go by name.
 * Import numbers the files it copies in this order and the local source reads them back in it.
 */
internal fun <V> inVolumeOrder(volumes: List<V>, name: (V) -> String, position: (V) -> Double?): List<V> {
    val byName = Comparator<V> { a, b -> name(a).compareToCaseInsensitiveNaturalOrder(name(b)) }
    if (volumes.any { position(it) == null }) return volumes.sortedWith(byName)
    return volumes.sortedWith(compareBy<V> { position(it) }.then(byName))
}
