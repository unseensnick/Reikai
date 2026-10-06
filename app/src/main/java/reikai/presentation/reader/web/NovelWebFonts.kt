package reikai.presentation.reader.web

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import reikai.novel.font.NovelFontManager
import reikai.novel.font.ReaderFontSource
import reikai.novel.font.readerFontSource

/**
 * The chosen face as a `data:` URI, resolved the way the native renderer resolves it in
 * `NovelTextStyle`: a user's file through the font manager, anything else as a bundled asset.
 * Inlined because the page cannot fetch either: this mode keeps file access off, and a document
 * whose origin is the source's site may not load a `file://` URL anyway. Null for a generic family.
 * One per viewport, so the face it holds goes with the reader rather than staying for the process.
 */
internal class NovelWebFonts {

    /** The last face resolved, which every document build and font push asks for again, and which for a
     *  CJK face is megabytes read and encoded. A user's file is told apart by its size and timestamp. */
    private data class Resolved(val family: String, val length: Long, val modified: Long, val uri: String)

    @Volatile
    private var last: Resolved? = null

    suspend fun dataUri(context: Context, fonts: NovelFontManager, family: String): String? {
        val source = readerFontSource(family)
        if (source == ReaderFontSource.SourceDefault || source is ReaderFontSource.Generic) return null
        return withContext(Dispatchers.IO) {
            val file = if (source is ReaderFontSource.UserFile) {
                fonts.localFile(source.fileName) ?: return@withContext null
            } else {
                null
            }
            val length = file?.length() ?: -1L
            val modified = file?.lastModified() ?: -1L
            last?.takeIf { it.family == family && it.length == length && it.modified == modified }
                ?.let { return@withContext it.uri }
            val bytes = when (source) {
                is ReaderFontSource.UserFile -> file?.readBytes()
                is ReaderFontSource.Bundled ->
                    runCatching { context.assets.open(source.assetPath).use { it.readBytes() } }.getOrNull()
                ReaderFontSource.SourceDefault, is ReaderFontSource.Generic -> null
            } ?: return@withContext null
            val type = if (family.endsWith(".otf", ignoreCase = true)) "font/otf" else "font/ttf"
            ("data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)).also {
                last = Resolved(family, length, modified, it)
            }
        }
    }
}
