package reikai.domain.local

import reikai.domain.library.ContentType

/** The journal as a list, for tests whose subject is what gets written to it and replayed from it. */
class InMemoryLocalFileJournal : LocalFileJournal {
    val entries = mutableListOf<LocalFileJournalEntry>()
    private var nextId = 1L

    override suspend fun insert(type: ContentType, plan: LocalFileJournalPlan): Long =
        nextId++.also { entries += LocalFileJournalEntry(it, type, plan) }

    override suspend fun update(id: Long, plan: LocalFileJournalPlan) {
        entries.replaceAll { if (it.id == id) it.copy(plan = plan) else it }
    }

    override suspend fun delete(id: Long) {
        entries.removeAll { it.id == id }
    }

    override suspend fun all(): List<LocalFileJournalEntry> = entries.toList()
}
