package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import reikai.storage.listFilesOrFail
import reikai.storage.listFilesOrNull
import kotlin.coroutines.cancellation.CancellationException

/** A file or folder of a local series, copied as [name] into [parent], or into its [folder] when one is named. */
data class FileCopy(val source: UniFile, val parent: UniFile, val folder: String?, val name: String)

sealed interface LocalMoveResult {
    /** Moved; [leftBehind] names the originals that could not be deleted, each still a full copy. */
    data class Moved(val leftBehind: List<String>) : LocalMoveResult

    data class Failed(val error: Throwable) : LocalMoveResult
}

/**
 * Moves a local series' files in three phases so a failure partway loses nothing: every copy is made and
 * checked, then [commit] writes the database, and only after it are the originals deleted. A copy or a
 * commit that fails deletes what was copied and leaves the series as it was. Both content types call it.
 */
suspend fun moveLocalFiles(context: Context, copies: List<FileCopy>, commit: suspend () -> Unit): LocalMoveResult {
    val made = mutableListOf<UniFile>()
    val placed = mutableListOf<Pair<UniFile, UniFile>>()
    try {
        copies.forEach { copy ->
            val dir = copy.folder?.let { name ->
                copy.parent.findFile(name) ?: checkNotNull(copy.parent.createDirectory(name)).also { made += it }
            } ?: copy.parent
            val target = copyChecked(context, copy.source, dir, copy.name)
            made += target
            placed += copy.source to target
        }
        commit()
    } catch (e: Throwable) {
        made.forEach { it.delete() }
        if (e is CancellationException) throw e
        return LocalMoveResult.Failed(e)
    }
    return LocalMoveResult.Moved(
        placed.filterNot { (source, copy) ->
            deleteMoved(context, source, copy)
        }.map { it.first.name.orEmpty() },
    )
}

/**
 * Deletes [source] now that [copy] holds it: a folder child by child, only those the copy has, and the folder
 * itself only once a fresh listing, trusted whole, finds nothing left but hidden markers. A picked folder's
 * listing can come back short without failing, and a whole-folder delete would then take what was never copied.
 */
private fun deleteMoved(context: Context, source: UniFile, copy: UniFile): Boolean {
    if (!source.isDirectory) return source.delete()
    val copied = copy.listFiles().orEmpty().associateBy { it.name }
    source.listFiles().orEmpty().forEach { child -> copied[child.name]?.let { deleteMoved(context, child, it) } }
    val left = source.listFilesOrNull(context) ?: return false
    return left.all { it.name.orEmpty().startsWith('.') } && source.delete()
}

/** [source] copied into [dir] as [name], a folder with everything in it; a file checked by its length. */
private fun copyChecked(context: Context, source: UniFile, dir: UniFile, name: String): UniFile {
    check(dir.findFile(name) == null) { "$name is already in ${dir.name}" }
    val target = if (source.isDirectory) dir.createDirectory(name) else dir.createFile(name)
    checkNotNull(target) { "Could not create $name in ${dir.name}" }
    try {
        if (source.isDirectory) {
            // A hidden file is a marker such as .nomedia, which storage may not let a copy recreate as it was.
            // A listing that fails would copy an empty folder, and the move would then delete the full one.
            val entries = source.listFilesOrFail(context).filterNot { it.name.orEmpty().startsWith('.') }
            entries.forEach { copyChecked(context, it, target, it.name.orEmpty()) }
            val copied = target.listFiles().orEmpty().mapNotNullTo(HashSet()) { it.name }
            check(entries.all { it.name in copied }) { "$name was copied short" }
        } else {
            source.openInputStream().use { input -> target.openOutputStream().use { input.copyTo(it) } }
            check(target.length() == source.length()) { "$name was copied short" }
        }
    } catch (e: Throwable) {
        target.delete()
        throw e
    }
    return target
}

/**
 * Puts [replacement] in [old]'s place as [name]: the old file steps aside as [aside] first and goes only once
 * the replacement holds the name, so a failed rename never leaves the series without either. Answers whether
 * it went through; when not, [old] is back under [name].
 */
internal fun replaceFile(old: UniFile, replacement: UniFile, name: String, aside: String): Boolean {
    if (!old.renameTo(aside)) return false
    if (!replacement.renameTo(name)) {
        old.renameTo(name)
        return false
    }
    old.delete()
    return true
}
