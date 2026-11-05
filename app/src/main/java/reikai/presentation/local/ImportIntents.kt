package reikai.presentation.local

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The files an Open with or Share intent hands over that an import can read, by type or by name; empty for
 * any other intent, so a shared link or an opened backup keeps its own handling.
 */
fun importedFiles(intent: Intent): List<Uri> {
    val uris = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.filter { it.scheme == "content" || it.scheme == "file" }
    val byType = intent.type?.lowercase() in IMPORT_TYPES
    return uris.filter { byType || it.lastPathSegment?.substringAfterLast('.')?.lowercase() in IMPORT_EXTENSIONS }
}

private val IMPORT_TYPES = setOf(
    "application/epub+zip",
    "application/vnd.comicbook+zip",
    "application/x-cbz",
    "application/vnd.comicbook-rar",
    "application/x-cbr",
    "application/x-cb7",
)

private val IMPORT_EXTENSIONS = setOf("epub", "cbz", "cbr", "cb7", "cbt")
