package reikai.presentation.local

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import reikai.data.local.LocalImport
import reikai.data.local.describe

/**
 * The files an Open with or Share intent hands over that an import can read, by the type or the name each
 * one's provider gives; empty for any other intent, so a shared link or an opened backup keeps its own
 * handling. Only `content:` URIs: a `file:` path would have Reikai read another app's files as its own.
 */
fun importedFiles(intent: Intent, resolver: ContentResolver): List<Uri> {
    val uris = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
    // Per file, since a mixed share arrives typed as a wildcard and a document URI has no extension.
    return uris.filter { uri ->
        resolver.getType(uri)?.lowercase() in IMPORT_TYPES ||
            resolver.describe(uri).name.substringAfterLast('.').lowercase() in IMPORT_EXTENSIONS
    }
}

private val IMPORT_TYPES = LocalImport.COMIC_TYPES + LocalImport.EPUB_TYPE

private val IMPORT_EXTENSIONS = setOf("epub", "cbz", "cbr", "cb7", "cbt")
