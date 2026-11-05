package reikai.domain.download

import java.util.concurrent.ConcurrentHashMap

/**
 * The back-off each source has built up in one downloader run, for manga and novels alike. A source's back-off
 * is forgotten once it has nothing more queued, and Off keeps none, since [DownloadPacing.next] never waits
 * there; a new instance per run forgets the rest. Novel lanes call it concurrently, one source each.
 */
class SourcePace {

    private val kept = ConcurrentHashMap<String, Long>()

    /** The wait before [sourceId]'s next chapter, after one that [succeeded] or failed. */
    fun waitAfter(sourceId: String, succeeded: Boolean, floorMs: Long, hasMore: Boolean, random: Double): Long {
        if (!hasMore) {
            kept.remove(sourceId)
            return 0L
        }
        val paceMs = DownloadPacing.next(kept[sourceId] ?: floorMs, succeeded, floorMs)
        kept[sourceId] = paceMs
        return DownloadPacing.jittered(paceMs, random)
    }
}
