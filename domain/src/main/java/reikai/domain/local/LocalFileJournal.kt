package reikai.domain.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import reikai.domain.library.ContentType

/**
 * A local file operation whose disk step and row step cannot share a transaction, written down before the
 * disk step so a kill between the two is repaired at the next start, before any listing can read the half-done
 * state. Only two operations have such a window: Rename files and import Replace; a copy-based move only
 * leaves duplicates. Each plan names the folder by uri and each step by name and document uri, so a replay
 * touches a file only after it has found it where, and as, the plan says.
 */
@Serializable
sealed interface LocalFileJournalPlan {

    /** Series [folder]'s volumes' renames, [volumes] first, then its own as [series] when it changes. */
    @Serializable
    @SerialName("rename")
    data class Rename(
        val parent: String,
        val folder: String,
        val entryId: Long,
        val series: Step?,
        val volumes: List<Step>,
    ) : LocalFileJournalPlan

    /** [old] in [folder] stepping aside as [aside] so [part] can take [name]; [old] goes once it has. */
    @Serializable
    @SerialName("replace")
    data class Replace(
        val folder: String,
        val name: String,
        val aside: String,
        val part: String,
        val old: String,
        val partUri: String,
    ) : LocalFileJournalPlan

    /** One rename, [from] to [to]; [toUri] is the document's uri once the rename took, null until then. */
    @Serializable
    data class Step(val from: String, val to: String, val fromUri: String, val toUri: String? = null)
}

data class LocalFileJournalEntry(val id: Long, val type: ContentType, val plan: LocalFileJournalPlan)

interface LocalFileJournal {
    suspend fun insert(type: ContentType, plan: LocalFileJournalPlan): Long
    suspend fun update(id: Long, plan: LocalFileJournalPlan)
    suspend fun delete(id: Long)
    suspend fun all(): List<LocalFileJournalEntry>
}
