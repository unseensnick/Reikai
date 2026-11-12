package reikai.domain.local

import kotlinx.serialization.Serializable
import reikai.domain.library.ContentType

/**
 * One volume of a local series listed by another series, its files left where they are: [volume] in the series
 * folder (or loose book) [folder], listed by the series at address [owner]. [volume] is a manga volume folder or a
 * novel book, [LOOSE] a manga folder's loose chapters, or [WHOLE] everything in the folder no row of its own names.
 * A row with [owner] equal to [folder] under [WHOLE] marks a series a split made, which has no folder of its own
 * until Organize files makes one.
 */
@Serializable
data class VolumeOwner(val folder: String, val volume: String, val owner: String) {
    companion object {
        const val WHOLE = "/"
        const val LOOSE = ""
    }
}

interface LocalVolumeOwnerRepository {
    /** Every row of [type], read from the table each time, so a listing right after a combine sees the combine. */
    suspend fun all(type: ContentType): List<VolumeOwner>
}

/**
 * Which series lists which volume, over a table's [rows]. Every volume has exactly one owner: its own row's, else
 * its folder's [VolumeOwner.WHOLE] row's, else the folder itself, so no volume lists under two series.
 */
class LocalGroups(val rows: List<VolumeOwner>) {

    private val byUnit = rows.associateBy { it.folder to it.volume }

    fun ownerOf(folder: String, volume: String): String = byUnit[folder to volume]?.owner ?: wholeOwner(folder)

    private fun wholeOwner(folder: String) = byUnit[folder to VolumeOwner.WHOLE]?.owner ?: folder

    /** The folders [series] lists, its own first unless it has none, then the others in the order they joined. */
    fun foldersOf(series: String): List<String> {
        val others = rows.filter { it.owner == series && it.folder != series }.map { it.folder }
        return ((if (hasNoFolder(series)) emptyList() else listOf(series)) + others).distinct()
    }

    /** Whether [series] was made by a split and lists only volumes in other folders. */
    fun hasNoFolder(series: String): Boolean =
        rows.any { it.folder == series && it.volume == VolumeOwner.WHOLE && it.owner == series }

    /**
     * The folders [series] lists volumes of that are not among [rootNames], the local folder's names from a listing
     * trusted whole: renamed or removed outside the app. Its own folder is never one, since a series whose own
     * folder is gone is relinked instead. Its listing leaves these out and its sync keeps their chapter rows.
     */
    fun missingMembers(series: String, rootNames: Collection<String>): Set<String> =
        foldersOf(series).filterTo(LinkedHashSet()) { it != series && it !in rootNames }

    /**
     * Whether another series lists volumes of [folder], whose chapters it addresses by that name: the folder keeps
     * its name through Organize files, and a relink or an Undo that would rename it is refused.
     */
    fun isLent(folder: String): Boolean = rows.any { it.folder == folder && it.owner != folder }

    /** Whether everything in [folder] is listed by another series, so it is no series of its own in Browse. */
    fun isLentWhole(folder: String): Boolean = wholeOwner(folder) != folder

    /**
     * Every series folder whose listing can change with [folders]': each one's owners and members, followed until
     * nothing new joins. A holder of the series lock names all of them, since a group lists across its folders.
     */
    fun closure(folders: Collection<String>): Set<String> {
        val found = LinkedHashSet<String>()
        val queue = ArrayDeque(folders.toList())
        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            if (!found.add(folder)) continue
            rows.forEach { row ->
                if (row.folder == folder) queue += row.owner
                if (row.owner == folder) queue += row.folder
            }
        }
        return found
    }
}

/**
 * The key a volume of [folder] goes by in series [owner]: its own name inside the owner's folder, as before
 * grouping, else `folder/volume`. No folder or file name holds a `/`, so the two never meet, and [localGroupUnit]
 * reads a key back. Both content types and every table keyed by volume use it.
 */
fun localGroupKey(owner: String, folder: String, volume: String): String =
    if (folder == owner) volume else "$folder/$volume"

/**
 * The key a manga chapter [file] of [folder]'s [volume] shows under in series [owner]: with [eachLoose], a file
 * loose in the owner's own folder is a volume of its own. Loose files rules only that folder, so a folder another
 * series was combined in by keeps its loose chapters as one volume.
 */
fun localVolumeKey(owner: String, folder: String, volume: String, file: String, eachLoose: Boolean): String =
    localGroupKey(owner, folder, if (eachLoose && folder == owner && volume == VolumeOwner.LOOSE) file else volume)

/**
 * The folders under the local root that hold volume [volume] of [folder]: none for a novel book named like its
 * series, which is a loose book in the local folder itself, else the series folder. A manga volume folder named
 * like its series is an ordinary volume inside it, so treating it as loose would delete the whole series.
 */
fun localVolumeContainer(type: ContentType, folder: String, volume: String): List<String> =
    if (type == ContentType.NOVELS && folder == volume) emptyList() else listOf(folder)

/** The folder and volume name [key] names in series [owner]. */
fun localGroupUnit(owner: String, key: String): Pair<String, String> =
    if ('/' in key) key.substringBefore('/') to key.substringAfter('/') else owner to key
