package reikai.presentation.details

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR

/**
 * Hands a series' download directory to whatever app browses folders.
 *
 * The directory is a document URI inside the storage tree the user picked, so the read flag forwards
 * this app's own persisted grant to the receiver, and the new-task flag is required because the caller
 * holds an application Context. A device with nothing able to browse a folder is an ordinary outcome
 * rather than an exotic one, so it is answered rather than swallowed.
 */
fun openDownloadFolder(context: Context, dir: UniFile?) {
    if (dir == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(dir.uri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.toast(MR.strings.open_folder_error)
    }
}
