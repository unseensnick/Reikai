package reikai.data.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * A document provider whose URIs are opaque ids, as a file manager's or a cloud drive's are, naming and typing
 * each file only through its columns. Register with `Robolectric.setupContentProvider(ImportDocuments::class.java,
 * AUTHORITY)` and fill [documents] before reading.
 */
class ImportDocuments : ContentProvider() {

    class Document(val name: String?, val size: Long?, val type: String?, val bytes: ByteArray = ByteArray(0))

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor {
        val document = documents.getValue(uri.lastPathSegment!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>(document.name, document.size))
        }
    }

    override fun getType(uri: Uri): String? = documents[uri.lastPathSegment]?.type

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File(context!!.cacheDir, "document-${uri.lastPathSegment}")
        file.writeBytes(documents.getValue(uri.lastPathSegment!!).bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "reikai.test.import"
        val documents = mutableMapOf<String, Document>()

        fun uri(id: String): Uri = Uri.parse("content://$AUTHORITY/document/$id")
    }
}
