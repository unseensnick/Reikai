package reikai.domain.download

import reikai.domain.source.SourceKey
import java.util.concurrent.ConcurrentHashMap

/**
 * When each source was last reached, by a library update's check or a downloaded chapter, for manga and novels
 * alike, so the first chapter after either still waits out the source's delay. [SourcePace] spaces chapters
 * only within one run. Keyed by the typed [SourceKey], so every caller names a source the same way.
 */
class SourceContacts(private val clock: () -> Long) {

    private val last = ConcurrentHashMap<SourceKey, Long>()

    fun record(source: SourceKey) {
        last[source] = clock()
    }

    /** Runs [check], a request to [source], and records the contact as it ends, failed or not. */
    inline fun <T> reaching(source: SourceKey, check: () -> T): T =
        try {
            check()
        } finally {
            record(source)
        }

    /**
     * What is left of [floorMs] since [source] was last reached, so Off (0) never waits. Not jittered: it only
     * ends a gap already begun, and after a paced chapter [SourcePace] has already waited it out.
     */
    fun waitBefore(source: SourceKey, floorMs: Long): Long {
        val at = last[source] ?: return 0L
        return (at + floorMs - clock()).coerceAtLeast(0L)
    }
}
