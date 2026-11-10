package reikai.storage

import android.content.Context
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.io.OutputStream

/*
 * The one way Reikai's own code changes a file in the user's storage. A picked folder's provider swallows a failed
 * query (a listing comes back short, exists() false, length() -1), dodges a taken name with "name (1)" on a create
 * or a rename, and writes plain "w" in place, so every primitive here names its trust: a delete or a create reads a
 * listing it can prove whole, a rename is judged by the name it produced, and a replacement is written beside the
 * old file and swapped in. Rules: docs/dev/subsystems/local-novels.md.
 */

/**
 * This folder's files, or null when they cannot be trusted to be all of them. A picked folder's listing
 * swallows a failed query and hands back what it read so far, empty or short, so a second query counts the
 * children: null when it fails, is still loading, or counts a different number. A plain file's listing is
 * null on failure already. Anything that deletes, moves or drops rows by a listing reads it here.
 */
fun UniFile.listOrNull(context: Context): List<UniFile>? {
    val files = listFiles()?.toList() ?: return null
    if (!DocumentsContract.isTreeUri(uri)) return files
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
    val count = try {
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )
            ?.use { cursor -> cursor.count.takeUnless { cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) } }
    } catch (e: Exception) {
        null
    }
    return files.takeIf { count == it.size }
}

/** This folder's files as [listOrNull] trusts them, or a failure naming the folder. */
fun UniFile.list(context: Context): List<UniFile> = listOrNull(context)
    ?: throw FolderUnreadableException(context.stringResource(MR.strings.local_folder_unreadable, name.orEmpty()))

/** A folder whose listing failed or came back short, so what it holds cannot be told. */
class FolderUnreadableException(message: String) : IOException(message)

/** A name the folder already holds, or a document the provider handed back that was not made for this call. */
class FileExistsException(message: String) : IOException(message)

/**
 * Opens [this] to be written from empty. UniFile's own `openOutputStream()` asks for plain "w", which the
 * platform's document providers write in place since Android 10, so a shorter file over an existing one
 * kept the old one's tail. Mihon's `ZipWriter` opens with "wt" for the same reason.
 */
fun UniFile.openTruncatingOutputStream(context: Context): OutputStream =
    context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")

/** [name], then "name (2)" and up, keeping a file's extension last. */
fun freeNames(name: String): Sequence<String> {
    val stem = name.substringBeforeLast('.', name)
    val extension = name.removePrefix(stem)
    return generateSequence(1) { it + 1 }.map { if (it == 1) name else "$stem ($it)$extension" }
}

/** The downloaders' suffix for a file not finished yet, which neither download index lists. */
const val IN_FLIGHT_SUFFIX = "_tmp"

/**
 * A new, empty file or folder named [name] in this folder. Refused with [FileExistsException] when a trusted listing
 * holds the name in any letter case, and when the provider hands back a document that is not fresh: one holding
 * bytes or entries (some providers answer a taken name with the existing document) or one under a dodged
 * "name (1)" (which is deleted again). The file comes back under the provider's own name, which may change the
 * extension by MIME type.
 */
fun UniFile.createNew(context: Context, name: String, directory: Boolean = false): UniFile =
    createFresh(context, list(context), name, directory)

@PublishedApi
internal fun UniFile.createFresh(context: Context, listed: List<UniFile>, name: String, directory: Boolean): UniFile {
    if (listed.any { it.name.equals(name, ignoreCase = true) }) {
        throw FileExistsException("$name is already in ${this.name}")
    }
    val made = (if (directory) createDirectory(name) else createFile(name))
        ?: throw IOException("Could not create $name in ${this.name}")
    val got = made.name.orEmpty()
    val fresh = if (directory) made.listOrNull(context)?.isEmpty() == true else made.length() == 0L
    if (got != name && got.substringBeforeLast('.') != name.substringBeforeLast('.')) {
        if (fresh) made.delete()
        throw FileExistsException("$name was taken in ${this.name} meanwhile")
    }
    if (!fresh) throw FileExistsException("${this.name} handed back $got instead of a new $name")
    return made
}

/**
 * Writes [name] in this folder beside the earlier file of that name (in any letter case) and in its place only
 * once whole: the write lands in a fresh, empty in-flight sibling, which then takes the name by [replaceFile], so
 * a failed write or a refused rename keeps the earlier file, and nothing is ever written over in place. An
 * in-flight sibling an earlier killed write left is deleted first. Returns the file under [name].
 */
inline fun UniFile.replace(context: Context, name: String, write: (OutputStream) -> Unit): UniFile =
    replace(context, list(context), name, write)

@PublishedApi
internal inline fun UniFile.replace(
    context: Context,
    listed: List<UniFile>,
    name: String,
    write: (OutputStream) -> Unit,
): UniFile {
    val earlier =
        listed.firstOrNull { it.name == name } ?: listed.firstOrNull { it.name.equals(name, ignoreCase = true) }
    val stale = listed.firstOrNull { it.name == name + IN_FLIGHT_SUFFIX }?.also { it.deleteFile() }
    val names = listed.mapNotNullTo(HashSet()) { it.name.takeIf { file -> file !== stale } }
    val part = createFresh(context, listed.filter { it !== stale }, name + IN_FLIGHT_SUFFIX, directory = false)
    try {
        part.openOutputStream().use(write)
    } catch (e: Throwable) {
        part.deleteFile()
        throw e
    }
    val placed = if (earlier == null) {
        part.rename(name)
    } else {
        replaceFile(earlier, part, name, freeNames("$name.old").first { it !in names })
    }
    if (!placed) {
        part.deleteFile()
        throw IOException("Could not name $name in ${this.name}")
    }
    return part
}

/**
 * Puts [replacement] in [old]'s place as [name]: the old file steps aside as [aside] first and goes only once
 * the replacement holds the name, so a failed or dodged rename never leaves the series without either. Answers
 * whether it went through; when not, [old] is back under [name], or still beside it as [aside] when another
 * writer took [name] meanwhile.
 */
fun replaceFile(old: UniFile, replacement: UniFile, name: String, aside: String): Boolean {
    if (!old.rename(aside)) return false
    if (!replacement.rename(name)) {
        old.rename(name)
        return false
    }
    old.deleteFile()
    return true
}

/**
 * Renames this file or folder to [newName], true only when it holds exactly that name afterwards. A change of
 * letter case alone goes through an in-flight name, since a case-blind disk finds the file itself under [newName]
 * and the provider would dodge to "name (1)"; a rename the provider dodged that way, or one that failed after the
 * hop, is put back under the old name.
 */
fun UniFile.rename(newName: String): Boolean {
    val from = name ?: return false
    if (from == newName) return true
    val hop = from.equals(newName, ignoreCase = true)
    if (hop && !renameTo(newName + IN_FLIGHT_SUFFIX)) return false
    if (!renameTo(newName)) {
        if (hop) renameTo(from)
        return false
    }
    if (name != newName) {
        renameTo(from)
        return false
    }
    return true
}

/** Deletes this file. A folder is refused, so no delete is recursive by accident: that is [deleteTree]. */
fun UniFile.deleteFile(): Boolean {
    require(!isDirectory) { "$name is a folder" }
    return delete()
}

/** Deletes this folder with everything in it, on purpose: a user-asked delete, or a folder this process made. */
fun UniFile.deleteTree(): Boolean = delete()

/**
 * Deletes this folder only when a trusted listing shows nothing in it beyond what [onlyMarkers] allows (by
 * default hidden files such as `.nomedia`). A listing that failed or came back short deletes nothing, since a
 * picked folder's failed listing reads as empty and the delete would take everything it still holds.
 */
fun UniFile.deleteIfEmpty(
    context: Context,
    onlyMarkers: (UniFile) -> Boolean = { it.name.orEmpty().startsWith('.') },
): Boolean {
    val left = listOrNull(context) ?: return false
    return left.all(onlyMarkers) && delete()
}

/**
 * [source] copied into this folder as [name], new: a file checked by its length, a folder with everything in it
 * read from a trusted listing (hidden markers such as `.nomedia` left out, since storage may not let a copy
 * recreate them) and checked against a trusted listing of the copy. A copy that fails is deleted whole.
 */
fun UniFile.copyVerified(context: Context, source: UniFile, name: String): UniFile {
    val target = createNew(context, name, directory = source.isDirectory)
    try {
        copyInto(context, source, target)
    } catch (e: Throwable) {
        target.deleteTree()
        throw e
    }
    return target
}

private fun copyInto(context: Context, source: UniFile, target: UniFile) {
    if (!source.isDirectory) {
        source.openInputStream().use { input -> target.openOutputStream().use { input.copyTo(it) } }
        if (target.length() != source.length()) throw IOException("${source.name} was copied short")
        return
    }
    val entries = source.list(context).filterNot { it.name.orEmpty().startsWith('.') }
    entries.forEach { entry ->
        val entryName = entry.name ?: throw IOException("A file in ${source.name} has no name")
        // The target is a fresh folder of this call's own, so its children need no listing of their own.
        val child = (if (entry.isDirectory) target.createDirectory(entryName) else target.createFile(entryName))
            ?: throw IOException("Could not create $entryName in ${target.name}")
        copyInto(context, entry, child)
    }
    val copied = target.list(context).associateBy { it.name }
    entries.forEach { entry ->
        val copy = copied[entry.name] ?: throw IOException("${target.name} was copied short")
        if (!entry.isDirectory && copy.length() != entry.length()) throw IOException("${entry.name} was copied short")
    }
}
