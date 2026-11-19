package reikai.presentation.migrate.flow

/**
 * The one rule both migration screens apply to a manual pick, per [K] (an entry on the list, the
 * screen itself on the single-entry route): a later pick supersedes one still resolving, the same
 * pick delivered again while it is in flight is refused, and picks under different keys never meet.
 * A caller [begin]s a pick, applies its result only while [isCurrent], and [end]s it when done, so
 * the same pick made again afterwards is a new pick rather than a duplicate.
 */
internal class LatestPickGate<K : Any> {

    class Ticket<K : Any> internal constructor(internal val key: K, internal val pick: Any)

    private val inFlight = HashMap<K, Ticket<K>>()

    /** Null when [pick] is already in flight for [key]; otherwise the pick now current for it. */
    fun begin(key: K, pick: Any): Ticket<K>? = synchronized(inFlight) {
        if (inFlight[key]?.pick == pick) return null
        Ticket(key, pick).also { inFlight[key] = it }
    }

    fun isCurrent(ticket: Ticket<K>): Boolean = synchronized(inFlight) { inFlight[ticket.key] === ticket }

    fun end(ticket: Ticket<K>) {
        synchronized(inFlight) { if (inFlight[ticket.key] === ticket) inFlight.remove(ticket.key) }
    }
}
