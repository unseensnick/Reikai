package reikai.domain.entry

/**
 * The colour each entry's cover tints its screens with, by the entry's signed key, for both content types.
 * A library entry's colour is kept while it is in the library; any other's, browse covers included, only for
 * the [CAP] most recently used, so browsing cannot grow the store without limit. Whether an entry is in the
 * library is what the writer already holds, never a read. Written from IO and read on Main, so synchronized.
 */
object CoverColors {

    /** The most colours kept for entries outside the library; the least recently used go first. */
    const val CAP = 2000

    private val library = HashMap<Long, Int>()

    // Access-ordered, so a get is a write: only ever touched under this object's lock.
    private val browse = object : LinkedHashMap<Long, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?) = size > CAP
    }

    @Synchronized
    operator fun get(key: Long): Int? = library[key] ?: browse[key]

    /** Keeps [color] for [key], moving it between the two stores when its library state changed. */
    @Synchronized
    fun put(key: Long, color: Int, inLibrary: Boolean) {
        if (inLibrary) {
            browse.remove(key)
            library[key] = color
        } else {
            library.remove(key)
            browse[key] = color
        }
    }

    /**
     * Moves the colour kept for [key], if any, to the store [inLibrary] names, for a library change no cover
     * load follows: a removed entry may never be seen again, and its colour would stay uncapped.
     */
    @Synchronized
    fun refile(key: Long, inLibrary: Boolean) {
        val color = library[key] ?: browse[key] ?: return
        put(key, color, inLibrary)
    }

    @Synchronized
    fun remove(key: Long) {
        library.remove(key)
        browse.remove(key)
    }

    @Synchronized
    fun clear() {
        library.clear()
        browse.clear()
    }

    /**
     * The colours as stored across restarts: `key|color|L` for a library entry, `key|color|rank` for any other,
     * rank 0 the least recently used, so a restore evicts in the order this process would have.
     */
    @Synchronized
    fun encode(): Set<String> = buildSet {
        library.forEach { (key, color) -> add("$key|$color|$LIBRARY") }
        browse.entries.forEachIndexed { rank, (key, color) -> add("$key|$color|$rank") }
    }

    /**
     * [encoded] without its recency, so a save can skip a set whose only change is the order reads left it in:
     * each pause after browsing would otherwise rewrite the whole set. The order is stored when a colour comes,
     * goes or changes.
     */
    fun withoutRecency(encoded: Set<String>): Set<String> = encoded.mapTo(HashSet()) { line ->
        if (line.endsWith("|$LIBRARY")) line else line.split("|").take(2).joinToString("|")
    }

    /**
     * Restores [encoded] beneath what is already here: a colour a cover load found first is newer, so it is kept
     * and stays the most recently used. A two-field entry, from before ranks were stored, reads as the oldest.
     */
    @Synchronized
    fun restore(encoded: Set<String>) {
        val stored = encoded.mapNotNull { line ->
            val parts = line.split("|")
            val key = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val color = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            Stored(key, color, parts.getOrNull(2))
        }
        stored.filter { it.mark == LIBRARY && it.key !in browse }.forEach { library.putIfAbsent(it.key, it.color) }
        val newer = LinkedHashMap(browse)
        browse.clear()
        stored.filter { it.mark != LIBRARY && it.key !in library && it.key !in newer }
            .sortedBy { it.mark?.toIntOrNull() ?: -1 }
            .forEach { browse[it.key] = it.color }
        browse.putAll(newer)
    }

    private class Stored(val key: Long, val color: Int, val mark: String?)

    private const val LIBRARY = "L"
}
