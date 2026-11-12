package reikai.storage

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileNotFoundException

/**
 * A picked-folder (SAF) provider over a temp directory, shaped like the device's ExternalStorageProvider: document
 * ids are `primary:<path>`, a delete is recursive, a create or a rename onto a taken name lands on `name (1).ext`
 * and succeeds, a move onto a taken name throws, and a file opens in the mode asked for ("w" in place, "wt"
 * truncating). [install] registers it with Robolectric; the companion's faults and hooks say what comes next.
 * It is a plain provider wrapping the [Documents] one only because Robolectric's resolver hands a plain query to
 * the final pre-O `query`, which `DocumentsProvider` rejects; everything else is `DocumentsProvider`'s dispatch.
 */
class FakeSafProvider : ContentProvider() {

    sealed interface ChildListingFault {
        /** The query throws, as a provider whose process died does. */
        data object Throw : ChildListingFault

        /** The query answers with the first [keep] children only. */
        data class Short(val keep: Int) : ChildListingFault

        /** The query answers everything but the child [name], the same lie at every reading it is given to. */
        data class Without(val name: String) : ChildListingFault

        /** The query answers null, as a provider that refused does. */
        data object NullCursor : ChildListingFault

        /** The query answers everything, flagged as still loading. */
        data object Loading : ChildListingFault

        /** The query answers as it should; a place-holder so a later query in the queue can fail. */
        data object Healthy : ChildListingFault
    }

    private val documents = Documents()

    override fun onCreate() = true

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        documents.attachInfo(context, info)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queryCount++
        return documents.query(
            uri,
            projection,
            Bundle().apply { sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) } },
            null,
        )
    }

    override fun getType(uri: Uri): String? = documents.getType(uri)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = documents.openFile(uri, mode)
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = documents.call(method, arg, extras)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private class Documents : DocumentsProvider() {

        override fun onCreate() = true

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_PROJECTION)

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            val file = existing(documentId)
            onCall?.invoke("queryDocument", file)
            check(file !in failingDocuments) { "document query failed" }
            return MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply { addFile(file) }
        }

        override fun queryChildDocuments(
            parentDocumentId: String,
            projection: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val parent = existing(parentDocumentId)
            onCall?.invoke("queryChildDocuments", parent)
            val children = parent.listFiles().orEmpty().sortedBy { it.name }
            val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
            val faulted = faultedFolder == null || faultedFolder == parent
            val queued = if (faulted) childListingFaults.removeFirstOrNull() else null
            when (val fault = queued ?: listingFault?.invoke(parent)) {
                ChildListingFault.Throw -> throw IllegalStateException("child query failed")
                ChildListingFault.NullCursor -> return null
                is ChildListingFault.Short -> children.take(fault.keep).forEach { cursor.addFile(it) }
                is ChildListingFault.Without -> children.filter { it.name != fault.name }.forEach { cursor.addFile(it) }
                ChildListingFault.Loading -> {
                    children.forEach { cursor.addFile(it) }
                    cursor.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                }
                ChildListingFault.Healthy, null -> children.forEach { cursor.addFile(it) }
            }
            return cursor
        }

        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
            val parent = fileFor(parentDocumentId).canonicalPath
            return fileFor(documentId).canonicalPath.startsWith(parent + File.separator)
        }

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            existing(documentId).let { file ->
                onCall?.invoke("openDocument", file)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
            }

        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val parent = existing(parentDocumentId)
            onCall?.invoke("createDocument", File(parent, displayName))
            beforeCreate?.invoke()
            val (name, ext) = splitFileName(mimeType, displayName)
            val asked = File(parent, if (ext.isEmpty()) name else "$name.$ext")
            if (createReturnsExisting && asked.exists()) return docIdFor(asked)
            val file = uniqueFile(parent, name, ext)
            val made = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
            check(made) { "could not create $displayName in $parentDocumentId" }
            return docIdFor(file)
        }

        override fun deleteDocument(documentId: String) {
            onCall?.invoke("deleteDocument", existing(documentId))
            check(existing(documentId).deleteRecursively()) { "could not delete $documentId" }
        }

        // FileSystemProvider.renameDocument: FileUtils.buildUniqueFile dodges a taken name with "name (1).ext".
        override fun renameDocument(documentId: String, displayName: String): String {
            val file = existing(documentId)
            onCall?.invoke("renameDocument", File(file.parentFile, displayName))
            beforeRename?.invoke()
            val after = uniqueFile(
                file.parentFile,
                displayName.substringBeforeLast('.'),
                displayName.substringAfterLast('.', ""),
                self = file,
            )
            check(file.renameTo(after)) { "could not rename $documentId" }
            return docIdFor(after)
        }

        // FileSystemProvider.moveDocument: one File.renameTo into the target under the same name, refused when the
        // target already holds it; a provider without the flag answers as DocumentsProvider's own default does.
        override fun moveDocument(
            sourceDocumentId: String,
            sourceParentDocumentId: String,
            targetParentDocumentId: String,
        ): String {
            if (!supportsMove) throw UnsupportedOperationException("Move not supported")
            val before = existing(sourceDocumentId)
            val asked = File(existing(targetParentDocumentId), before.name)
            onCall?.invoke("moveDocument", asked)
            val after = if (moveDodgesTakenName) {
                uniqueFile(asked.parentFile, before.nameWithoutExtension, before.extension)
            } else {
                asked
            }
            check(!after.exists()) { "Already exists $after" }
            check(before.renameTo(after)) { "Failed to move to $after" }
            return docIdFor(after)
        }

        private fun MatrixCursor.addFile(file: File) {
            val row = newRow()
            columnNames.forEach { column ->
                row.add(
                    column,
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> docIdFor(file)
                        Document.COLUMN_DISPLAY_NAME -> file.name
                        Document.COLUMN_MIME_TYPE -> mimeFor(file)
                        Document.COLUMN_SIZE -> file.length()
                        Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        Document.COLUMN_FLAGS -> flagsFor(file)
                        else -> null
                    },
                )
            }
        }

        private fun existing(documentId: String): File =
            fileFor(documentId).takeIf { it.exists() } ?: throw FileNotFoundException(documentId)

        private fun mimeFor(file: File): String = when {
            file.isDirectory -> Document.MIME_TYPE_DIR
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: UNKNOWN
        }

        private fun flagsFor(file: File): Int =
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                (if (supportsMove) Document.FLAG_SUPPORTS_MOVE else 0) or
                if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE

        // The device provider's FileUtils.splitFileName: the extension the name carries is kept when it agrees with
        // the MIME type, otherwise the MIME type's own extension replaces it, so a stem sent with "text/plain"
        // comes back as "stem.txt". unifile sends the stem and the type separately and relies on this.
        private fun splitFileName(mimeType: String, displayName: String): Pair<String, String> {
            if (mimeType == Document.MIME_TYPE_DIR) return displayName to ""
            val map = MimeTypeMap.getSingleton()
            val lastDot = displayName.lastIndexOf('.')
            var name = displayName
            var ext: String? = null
            var mimeFromExt: String? = null
            if (lastDot >= 0) {
                name = displayName.substring(0, lastDot)
                ext = displayName.substring(lastDot + 1)
                mimeFromExt = map.getMimeTypeFromExtension(ext.lowercase())
            }
            if (mimeFromExt == null) mimeFromExt = UNKNOWN
            val extFromMime = if (mimeType == UNKNOWN) null else map.getExtensionFromMimeType(mimeType)
            if (mimeType != mimeFromExt && ext != extFromMime) {
                name = displayName
                ext = extFromMime
            }
            return name to ext.orEmpty()
        }

        // Android's disk is case-sensitive, so a name taken only by [self] in another letter case is free there; the
        // host running the test may not be.
        private fun uniqueFile(parent: File, name: String, ext: String, self: File? = null): File {
            fun build(stem: String) = File(parent, if (ext.isEmpty()) stem else "$stem.$ext")
            fun taken(file: File) = file.canonicalFile != self?.canonicalFile && (
                file.exists() || (caseBlind && parent.list().orEmpty().any { it.equals(file.name, ignoreCase = true) })
                )
            var file = build(name)
            var n = 0
            while (taken(file)) file = build("$name (${++n})")
            return file
        }
    }

    companion object {
        /** The device's own provider, whose authority unifile's `findFile` reaches by address instead of by listing. */
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

        /** Any other provider: a file manager's, a cloud drive's. */
        const val OTHER = "reikai.test.saf"

        private const val UNKNOWN = "application/octet-stream"
        private val ROOT_PROJECTION = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID)
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        /** What each coming child query does, in order; a query with nothing queued answers as it should. */
        val childListingFaults = ArrayDeque<ChildListingFault>()

        /** The one folder whose child queries take from [childListingFaults]; null means every folder's do. */
        var faultedFolder: File? = null

        /** Documents whose own query fails, as one does when the provider's process dies mid-call. */
        val failingDocuments = HashSet<File>()

        /** Whether a create with a taken name answers with the existing document instead of a numbered one. */
        var createReturnsExisting = false

        /** Whether documents move, as the device's own provider's do; many others' do not. */
        var supportsMove = true

        /** Whether a name is taken in any letter case, as on shared storage, whatever the host's disk does. */
        var caseBlind = false

        /** Whether a move onto a taken name lands under "name (1)" as some providers' do, rather than throwing. */
        var moveDodgesTakenName = false

        /** What a child query of a folder does when [childListingFaults] holds nothing for it. */
        var listingFault: ((File) -> ChildListingFault?)? = null

        /**
         * Runs at the head of every provider call with its name and the file it is about (for a create or a
         * rename, the file under the name asked for); what it throws is the call's own failure.
         */
        var onCall: ((call: String, target: File) -> Unit)? = null

        /** Runs inside the next creates and renames, before the name is taken: another writer in the window. */
        var beforeCreate: (() -> Unit)? = null
        var beforeRename: (() -> Unit)? = null

        /** Every query the provider has answered or refused since [install]. */
        var queryCount = 0

        /** The directory standing in for the `primary` volume. */
        lateinit var volume: File

        /** Registers the provider over [volume] under [authority] and announces it as a documents provider. */
        fun install(context: Context, volume: File, authority: String = EXTERNAL_STORAGE) {
            this.volume = volume
            childListingFaults.clear()
            faultedFolder = null
            failingDocuments.clear()
            createReturnsExisting = false
            supportsMove = true
            moveDodgesTakenName = false
            caseBlind = false
            beforeCreate = null
            beforeRename = null
            listingFault = null
            onCall = null
            queryCount = 0
            val info = ProviderInfo().apply {
                name = FakeSafProvider::class.java.name
                packageName = context.packageName
                applicationInfo = context.applicationInfo
                this.authority = authority
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
            Robolectric.buildContentProvider(FakeSafProvider::class.java).create(info)
            shadowOf(context.packageManager).apply {
                addOrUpdateProvider(info)
                addIntentFilterForProvider(
                    ComponentName(info.packageName, info.name),
                    IntentFilter(DocumentsContract.PROVIDER_INTERFACE),
                )
            }
        }

        /** The tree URI a folder picker hands back for [folder], a directory under [volume]. */
        fun treeUri(folder: File, authority: String = EXTERNAL_STORAGE): Uri =
            DocumentsContract.buildTreeDocumentUri(authority, docIdFor(folder))

        fun docIdFor(file: File): String = "primary:" + file.relativeTo(volume).path.replace(File.separatorChar, '/')

        fun fileFor(documentId: String): File = File(volume, documentId.substringAfter(':'))
    }
}
