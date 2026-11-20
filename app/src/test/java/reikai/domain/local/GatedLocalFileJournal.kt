package reikai.domain.local

import kotlinx.coroutines.CompletableDeferred

/** [inner], whose next [all] once [holdNextRead] is called reads its rows and then waits to hand them back. */
class GatedLocalFileJournal(private val inner: LocalFileJournal) : LocalFileJournal by inner {
    private var gate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null

    /** Arms the next read: the first deferred completes once it has read, and it returns when the second does. */
    fun holdNextRead() = (CompletableDeferred<Unit>() to CompletableDeferred<Unit>()).also { gate = it }

    override suspend fun all(): List<LocalFileJournalEntry> {
        val read = inner.all()
        gate?.let { (reached, release) ->
            gate = null
            reached.complete(Unit)
            release.await()
        }
        return read
    }
}
