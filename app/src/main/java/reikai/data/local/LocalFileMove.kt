package reikai.data.local

import com.hippo.unifile.UniFile
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
suspend fun moveLocalFiles(copies: List<FileCopy>, commit: suspend () -> Unit): LocalMoveResult {
    val made = mutableListOf<UniFile>()
    try {
        copies.forEach { copy ->
            val dir = copy.folder?.let { name ->
                copy.parent.findFile(name) ?: checkNotNull(copy.parent.createDirectory(name)).also { made += it }
            } ?: copy.parent
            made += copyChecked(copy.source, dir, copy.name)
        }
        commit()
    } catch (e: Throwable) {
        made.forEach { it.delete() }
        if (e is CancellationException) throw e
        return LocalMoveResult.Failed(e)
    }
    return LocalMoveResult.Moved(copies.filterNot { it.source.delete() }.map { it.source.name.orEmpty() })
}

/** [source] copied into [dir] as [name], a folder with everything in it; a file checked by its length. */
private fun copyChecked(source: UniFile, dir: UniFile, name: String): UniFile {
    check(dir.findFile(name) == null) { "$name is already in ${dir.name}" }
    val target = if (source.isDirectory) dir.createDirectory(name) else dir.createFile(name)
    checkNotNull(target) { "Could not create $name in ${dir.name}" }
    try {
        if (source.isDirectory) {
            // A hidden file is a marker such as .nomedia, which storage may not let a copy recreate as it was.
            // A listing that fails would copy an empty folder, and the move would then delete the full one.
            val entries = checkNotNull(source.listFiles()) { "Could not list $name" }
                .filterNot { it.name.orEmpty().startsWith('.') }
            entries.forEach { copyChecked(it, target, it.name.orEmpty()) }
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
