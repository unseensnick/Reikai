package reikai.domain.download

import java.util.concurrent.ConcurrentHashMap

/**
 * When each source was last reached, by a library update's check or a downloaded chapter, for manga and novels
 * alike, so the first chapter after either still waits out the source's delay. [SourcePace] spaces chapters
 * only within one run. Keyed as the downloaders pace: a manga source by its id as a string.
 */
class SourceContacts(private val clock: () -> Long) {

    private val last = ConcurrentHashMap<String, Long>()

    fun record(sourceId: String) {
        last[sourceId] = clock()
    }

    /** Runs [check], a request to [sourceId], and records the contact as it ends, failed or not. */
    inline fun <T> reaching(sourceId: String, check: () -> T): T =
        try {
            check()
        } finally {
            record(sourceId)
        }

    /**
     * What is left of [floorMs] since [sourceId] was last reached, so Off (0) never waits. Not jittered: it only
     * ends a gap already begun, and after a paced chapter [SourcePace] has already waited it out.
     */
    fun waitBefore(sourceId: String, floorMs: Long): Long {
        val at = last[sourceId] ?: return 0L
        return (at + floorMs - clock()).coerceAtLeast(0L)
    }
}
