package reikai.presentation.reader.web

import android.content.Context

/**
 * Reads this mode's own CSS and JS out of `assets/novel-web/` and caches them, since a chapter
 * change rebuilds the document and would otherwise re-read the same bytes each time.
 *
 * Ported from tsundoku's `NovelWebViewJsAssets`. The asset is inlined into the document rather than
 * linked, so the page needs no file origin and `allowFileAccess` can stay off for this mode.
 */
object NovelWebAssets {

    private val cache = mutableMapOf<String, String>()

    fun read(context: Context, name: String): String = synchronized(cache) {
        cache.getOrPut(name) {
            context.assets.open("novel-web/$name").bufferedReader().use { it.readText() }
        }
    }

    /** Reads [name] and substitutes each `__TOKEN__` key in [tokens] with its value. */
    fun readWith(context: Context, name: String, tokens: Map<String, String>): String =
        tokens.entries.fold(read(context, name)) { text, (key, value) -> text.replace(key, value) }
}
