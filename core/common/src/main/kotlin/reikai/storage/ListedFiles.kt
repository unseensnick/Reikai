package reikai.storage

import android.content.Context
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException

/**
 * This folder's files, or null when they cannot be trusted to be all of them. A picked folder's listing
 * swallows a failed query and hands back what it read so far, empty or short, so a second query counts the
 * children: null when it fails, is still loading, or counts a different number. A plain file's listing is
 * null on failure already. Anything that deletes, moves or drops rows by a listing reads it here.
 */
fun UniFile.listFilesOrNull(context: Context): List<UniFile>? {
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

/** This folder's files as [listFilesOrNull] trusts them, or a failure naming the folder. */
fun UniFile.listFilesOrFail(context: Context): List<UniFile> = listFilesOrNull(context)
    ?: throw FolderUnreadableException(context.stringResource(MR.strings.local_folder_unreadable, name.orEmpty()))

/** A folder whose listing failed or came back short, so what it holds cannot be told. */
class FolderUnreadableException(message: String) : IOException(message)
