package reikai.presentation.reader.web

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import reikai.novel.font.NovelFontManager
import reikai.novel.font.isSupportedFontFile

/**
 * The chosen face as a `data:` URI, resolved the way the native renderer resolves it in
 * `NovelTextStyle`: a user's file through the font manager, anything else as a bundled asset.
 * Inlined because the page cannot fetch either: this mode keeps file access off, and a document
 * whose origin is the source's site may not load a `file://` URL anyway. Null for a generic family.
 */
internal object NovelWebFonts {

    suspend fun dataUri(context: Context, fonts: NovelFontManager, family: String): String? {
        if (family.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val bytes = if (isSupportedFontFile(family)) {
                fonts.localFile(family)?.readBytes()
            } else {
                runCatching { context.assets.open("fonts/$family.ttf").use { it.readBytes() } }.getOrNull()
            } ?: return@withContext null
            val type = if (family.endsWith(".otf", ignoreCase = true)) "font/otf" else "font/ttf"
            "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}
