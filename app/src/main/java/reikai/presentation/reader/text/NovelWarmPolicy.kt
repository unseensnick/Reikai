package reikai.presentation.reader.text

/**
 * Whether the seamless window may try a neighbour chapter again on its own after one failed.
 *
 * Suppressing and self-clearing are both load-bearing: without the first, a boundary the reader
 * scrolls over re-requests a chapter that just failed; without the second, a session strands on one
 * that has since recovered. A manual retry and an explicit chapter open drop the record outright, so
 * neither of those waits. Why both are needed is in content-layer-reader-surface.md.
 */
object NovelWarmPolicy {

    /** How long a failed chapter is left alone before the window may reach for it again unprompted. */
    const val RETRY_COOLDOWN_MS = 15_000L

    /**
     * One boundary chapter's last failure. [failedAtElapsedMs] is monotonic uptime, not wall clock,
     * so changing the device's time cannot strand the cooldown or skip it.
     */
    data class Failure(val failedAtElapsedMs: Long, val message: String?)

    fun mayAutoWarm(failure: Failure?, nowElapsedMs: Long): Boolean =
        failure == null || nowElapsedMs - failure.failedAtElapsedMs >= RETRY_COOLDOWN_MS
}
