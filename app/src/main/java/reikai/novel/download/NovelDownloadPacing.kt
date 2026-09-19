package reikai.novel.download

/**
 * How long the novel downloader waits before the next chapter from a source. The floor is the user's
 * delay, a source's own when one is set and the global one otherwise. Above it the wait adapts: it
 * halves back toward the floor after a success and doubles after a failure, up to [MAX_DELAY_MS] or
 * the floor if that is higher. Manga has no twin: its extensions rate-limit their own clients, which
 * LN plugins cannot.
 */
object NovelDownloadPacing {

    const val MAX_DELAY_MS = 30_000L

    /** The delays Settings offers, the global one and a source's own alike. */
    val DELAY_OPTIONS_MS = listOf(500L, 1_000L, 2_000L, 3_000L, 5_000L, 10_000L)

    fun floorFor(sourceId: String, globalMs: Long, perSourceMs: Map<String, Long>): Long =
        perSourceMs[sourceId] ?: globalMs

    fun next(currentMs: Long, succeeded: Boolean, floorMs: Long): Long = if (succeeded) {
        (currentMs / 2).coerceAtLeast(floorMs)
    } else {
        (currentMs.coerceAtLeast(floorMs) * 2).coerceAtMost(maxOf(MAX_DELAY_MS, floorMs))
    }

    /** Per-source delays are stored as `sourceId=ms` entries; a malformed one is skipped. */
    fun parse(entries: Set<String>): Map<String, Long> = entries.mapNotNull { entry ->
        val split = entry.lastIndexOf('=')
        if (split <= 0) return@mapNotNull null
        entry.substring(split + 1).toLongOrNull()?.let { entry.substring(0, split) to it }
    }.toMap()

    fun format(perSourceMs: Map<String, Long>): Set<String> =
        perSourceMs.mapTo(HashSet()) { (sourceId, ms) -> "$sourceId=$ms" }
}
