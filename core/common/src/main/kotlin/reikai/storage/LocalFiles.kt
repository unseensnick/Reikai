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

/** [name], or "name (2)" and up when [taken] holds it in any letter case, taken for the next caller. */
fun freeName(name: String, taken: MutableSet<String>): String =
    freeNames(name).first { candidate -> taken.none { it.equals(candidate, ignoreCase = true) } }.also { taken += it }

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
    // A folder is also asked for by its own query, a second reading the listing cannot hide it from: the create
    // hands back a folder already there, and one whose children a listing hides reads as new and empty.
    if (directory && findFile(name) != null) throw FileExistsException("$name is already in ${this.name}")
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
 * A new file [name] in this folder with what [write] puts in it, by [createNew], and checked against
 * [expectedLength] when the caller knows it, since a provider can end a stream early without failing. A write
 * that fails or comes up short deletes the file and throws, so no half-written file is left under a real name.
 */
inline fun UniFile.writeNew(
    context: Context,
    name: String,
    expectedLength: Long? = null,
    write: (OutputStream) -> Unit,
): UniFile = writeNew(context, list(context), name, expectedLength, write)

@PublishedApi
internal inline fun UniFile.writeNew(
    context: Context,
    listed: List<UniFile>,
    name: String,
    expectedLength: Long?,
    write: (OutputStream) -> Unit,
): UniFile {
    val made = createFresh(context, listed, name, directory = false)
    try {
        made.openOutputStream().use(write)
        if (expectedLength != null && made.length() != expectedLength) throw IOException("$name was written short")
    } catch (e: Throwable) {
        // Not deleteFile: a provider failing the write fails the "is it a file" query too, and this one is ours.
        made.delete()
        throw e
    }
    return made
}

/**
 * Writes [name] in this folder beside the earlier file of that name (in any letter case) and in its place only
 * once whole: the write lands in a fresh, empty in-flight sibling, which then takes the name by [replaceFile], so
 * a failed write or a refused rename keeps the earlier file, and nothing is ever written over in place. An
 * in-flight sibling an earlier killed write left is deleted first. [expectedLength] checks the write as for
 * [writeNew]. Returns the file under [name].
 */
inline fun UniFile.replace(
    context: Context,
    name: String,
    expectedLength: Long? = null,
    write: (OutputStream) -> Unit,
): UniFile = replace(context, list(context), name, expectedLength, write)

@PublishedApi
internal inline fun UniFile.replace(
    context: Context,
    listed: List<UniFile>,
    name: String,
    expectedLength: Long? = null,
    write: (OutputStream) -> Unit,
): UniFile {
    val earlier =
        listed.firstOrNull { it.name == name } ?: listed.firstOrNull { it.name.equals(name, ignoreCase = true) }
    val stale = listed.firstOrNull { it.name == name + IN_FLIGHT_SUFFIX }?.also { it.deleteFile() }
    val names = listed.mapNotNullTo(HashSet()) { it.name.takeIf { file -> file !== stale } }
    val part = writeNew(context, listed.filter { it !== stale }, name + IN_FLIGHT_SUFFIX, expectedLength, write)
    val placed = if (earlier == null) {
        part.rename(name)
    } else {
        replaceFile(earlier, part, name, freeName("$name.old", names))
    }
    if (!placed) {
        part.delete()
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
 * hop, is put back under the old name. False never says where the file is: a put back onto an old name another
 * writer took meanwhile is dodged in turn, so a caller that needs the name reads it from the file again.
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

/**
 * Whether the provider lets this document be moved ([DocumentsContract.Document.FLAG_SUPPORTS_MOVE]) and renamed
 * ([DocumentsContract.Document.FLAG_SUPPORTS_RENAME]), read from its own flags. False off a picked folder (unifile
 * has no move) and when the query fails, so an operation that needs both is not offered rather than tried.
 */
fun UniFile.canMoveAndRename(context: Context): Boolean {
    if (!DocumentsContract.isDocumentUri(context, uri)) return false
    val needed = DocumentsContract.Document.FLAG_SUPPORTS_MOVE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
    val flags = try {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
    } catch (e: Exception) {
        null
    }
    return flags != null && flags and needed == needed
}

/**
 * Moves this document out of [from] into [into] under its own name, as one provider move, and answers it where it
 * landed, or null when it did not move. A device provider's move is one rename on disk and refuses a name [into]
 * already holds, so the caller frees the name first. A move that landed under another name is moved back and
 * answers null, since nothing could find it there. Never moves a tree root, whose grant would not follow it.
 */
fun UniFile.moveTo(context: Context, from: UniFile, into: UniFile): UniFile? {
    val name = name ?: return null
    if (!DocumentsContract.isTreeUri(uri)) return null
    if (DocumentsContract.getDocumentId(uri) == DocumentsContract.getTreeDocumentId(uri)) return null
    val moved = try {
        DocumentsContract.moveDocument(context.contentResolver, uri, from.uri, into.uri)
    } catch (e: Exception) {
        null
    } ?: return null
    val landed = UniFile.fromUri(context, moved) ?: return null
    if (landed.name == name) return landed
    try {
        // It goes back under the dodged name it carries now, so it takes its own name again there.
        DocumentsContract.moveDocument(context.contentResolver, moved, into.uri, from.uri)
            ?.let { UniFile.fromUri(context, it) }
            ?.rename(name)
    } catch (e: Exception) {
        // Left where it landed: the caller's reversal finds it by its document, not its name.
    }
    return null
}

/**
 * Deletes this file, answering whether it went. Anything not answered as a file is left, a folder included, so no
 * delete is recursive by accident: that is [deleteTree]. Asked as `isFile`, since a picked folder's failed query
 * answers false to both questions, and answered with false rather than a throw, which no caller would catch.
 */
fun UniFile.deleteFile(): Boolean = isFile && delete()

/** Deletes this folder with everything in it, on purpose: a user-asked delete, or a folder this process made. */
fun UniFile.deleteTree(): Boolean = delete()

/**
 * Deletes this folder only when a trusted listing shows nothing in it but hidden marker files such as `.nomedia`,
 * never a hidden folder. A listing that failed or came back short deletes nothing, since a picked folder's failed
 * listing reads as empty and the delete would take everything it still holds.
 */
fun UniFile.deleteIfEmpty(context: Context): Boolean {
    val left = listOrNull(context) ?: return false
    return left.all(isHiddenMarker) && delete()
}

/** A hidden file such as `.nomedia`, which a folder's tidy does not count as content. */
private val isHiddenMarker: (UniFile) -> Boolean = { it.isFile && it.name.orEmpty().startsWith('.') }

/** The hidden name a moved folder is set aside under; every listing skips a dot-named entry. */
const val PARKED_PREFIX = ".moved-"

/**
 * Clears away this original of a move, once [copy] has been checked against it. A file is deleted. A folder never
 * is: a picked folder's delete takes all it holds, and its listing can hide the same file at every reading, so
 * no listing proves it empty. It loses only the files [copy] holds under the same name and size, level by level,
 * and is then set aside by [park], so what the copy lacks stays on disk. False when the original still stands;
 * it never throws, since the move it follows has landed. `isFile` is asked once: [deleteFile] asks again, and a
 * provider that fails the second query would turn a file just seen into an exception.
 */
fun UniFile.retireMoved(copy: UniFile): Boolean = when {
    isFile -> delete()
    isDirectory -> {
        removeCopied(copy, this)
        park()
    }
    else -> false
}

private fun removeCopied(copy: UniFile, source: UniFile) {
    val copied = copy.listFiles().orEmpty().associateBy { it.name }
    source.listFiles().orEmpty().forEach { child ->
        val twin = copied[child.name] ?: return@forEach
        when {
            child.isDirectory -> if (twin.isDirectory) removeCopied(twin, child)
            // A failed query answers -1 for both lengths, which is no match.
            child.isFile -> if (twin.isFile && child.length() >= 0 && child.length() == twin.length()) child.delete()
        }
    }
}

/**
 * Renames this folder to a hidden name where it stands, so no listing reads it as a series, a volume, a chapter or
 * a download, and nothing in it is deleted. A second folder set aside from the same name takes the time with it.
 */
fun UniFile.park(): Boolean {
    val parked = PARKED_PREFIX + (name ?: return false)
    return rename(parked) || rename("$parked ${System.currentTimeMillis().toString(Character.MAX_RADIX)}")
}

/**
 * Sets this folder aside by [park] when a trusted listing shows nothing left in it but what [spent] allows (by
 * default hidden marker files) and folders already set aside: the tidy for a folder a move emptied, in place of a
 * delete. A listing that failed or came back short leaves it where it is.
 */
fun UniFile.parkIfSpent(
    context: Context,
    spent: (UniFile) -> Boolean = isHiddenMarker,
): Boolean {
    val left = listOrNull(context) ?: return false
    return left.all { it.name.orEmpty().startsWith(PARKED_PREFIX) || spent(it) } && park()
}

/**
 * [source] copied into this folder as [name], new: a file checked by its length, a folder with everything in it
 * read from a trusted listing (every hidden entry left out, since storage may not let a copy recreate a marker
 * such as `.nomedia`) and checked against a trusted listing of the copy. A copy that fails is deleted whole.
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
