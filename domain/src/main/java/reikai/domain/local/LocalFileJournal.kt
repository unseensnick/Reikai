package reikai.domain.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import reikai.domain.library.ContentType

/**
 * A local file operation whose disk step and row step cannot share a transaction, written down before the
 * disk step so a kill between the two is repaired at the next start, before any listing can read the half-done
 * state. Organize files and import Replace have such a window; grouping volumes changes no file. Each plan names
 * its files by name and path, so a replay touches a file only after it has found it where, and as, the plan says.
 */
@Serializable
sealed interface LocalFileJournalPlan {

    /**
     * The file [name] in [folder] stepping aside as [aside] so [part] can take [name]; it goes once that has.
     * [series] is the folder's name, which holds the series while the folder cannot be reached; null on a row
     * written before it.
     */
    @Serializable
    @SerialName("replace")
    data class Replace(
        val folder: String,
        val name: String,
        val aside: String,
        val part: String,
        val partUri: String,
        val series: String? = null,
    ) : LocalFileJournalPlan

    /**
     * Organize files over series [entryId], whose address goes from [fromSeries] to [toSeries] under the local
     * folder [root]. Its [steps] run in order, [done] of them through: the rows' step first ([volumeKeys] renamed,
     * every chapter address taken through [urls], the [ownersBefore] rows replaced by [ownersAfter]), then one
     * document change each. A run that cannot finish puts back its own steps by [putBack], which is never put
     * back in turn ([isPutBack]); an Undo runs [undoing]. The fields always describe the forward organize.
     */
    @Serializable
    @SerialName("organize")
    data class Organize(
        val entryId: Long,
        val root: String,
        val fromSeries: String,
        val toSeries: String,
        val volumeKeys: Map<String, String>,
        val urls: List<UrlRule>,
        val ownersBefore: List<VolumeOwner>,
        val ownersAfter: List<VolumeOwner>,
        val steps: List<FileStep>,
        val done: Int = 0,
        val isPutBack: Boolean = false,
    ) : LocalFileJournalPlan {
        /** Every step reversed, last first: the Undo of an organize that went through. */
        fun undoing() = copy(steps = steps.asReversed().map { it.reversed() }, done = 0)

        /** The steps this run got through, reversed, last first. */
        fun putBack() = copy(steps = steps.take(done).asReversed().map { it.reversed() }, done = 0, isPutBack = true)
    }

    /**
     * Chapter addresses in volume [fromVolume] of folder [fromFolder] go to [toVolume] of [toFolder]; only the
     * chapter [file] when one is named, since loose chapters move one by one and one a listing hid stays put.
     */
    @Serializable
    data class UrlRule(
        val fromFolder: String,
        val fromVolume: String,
        val toFolder: String,
        val toVolume: String,
        val file: String? = null,
    ) {
        fun reversed() = UrlRule(toFolder, toVolume, fromFolder, fromVolume, file)
    }

    /** One change of an organize, each document named by its path of names under the local folder. */
    @Serializable
    sealed interface FileStep {
        fun reversed(): FileStep

        /** The rows written as the organize describes them, or back when not [forward]: one transaction. */
        @Serializable
        @SerialName("rows")
        data class Rows(val forward: Boolean = true) : FileStep {
            override fun reversed() = Rows(!forward)
        }

        /** A new, empty folder at [path]; reversed, it is set aside once nothing but its cover is left. */
        @Serializable
        @SerialName("make")
        data class MakeFolder(val path: List<String>) : FileStep {
            override fun reversed() = SetAside(path)
        }

        @Serializable
        @SerialName("aside")
        data class SetAside(val path: List<String>) : FileStep {
            override fun reversed() = MakeFolder(path)
        }

        @Serializable
        @SerialName("rename")
        data class Rename(val path: List<String>, val to: String) : FileStep {
            override fun reversed() = Rename(path.dropLast(1) + to, path.last())
        }

        /** The document at [path] moved into the folder at [into], under its own name. */
        @Serializable
        @SerialName("move")
        data class Move(val path: List<String>, val into: List<String>) : FileStep {
            override fun reversed() = Move(into + path.last(), path.dropLast(1))
        }
    }
}

data class LocalFileJournalEntry(val id: Long, val type: ContentType, val plan: LocalFileJournalPlan)

interface LocalFileJournal {
    suspend fun insert(type: ContentType, plan: LocalFileJournalPlan): Long
    suspend fun update(id: Long, plan: LocalFileJournalPlan)
    suspend fun delete(id: Long)
    suspend fun all(): List<LocalFileJournalEntry>
}
