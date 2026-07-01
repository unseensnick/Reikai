package exh.util

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt
import exh.metadata.metadata.base.RaisedTag
import exh.source.NHENTAI_NET_SOURCE_ID
import exh.source.PURURIN_SOURCE_ID
import exh.source.eHentaiSourceIds
import java.util.Locale

object SourceTagsUtil {

    // Category accent colors for E-Hentai gallery genres (mirrors the site's own palette).
    enum class GenreColor(val color: Int) {
        DOUJINSHI_COLOR("#ff614d"),
        MANGA_COLOR("#ff9800"),
        ARTIST_CG_COLOR("#fbc02d"),
        GAME_CG_COLOR("#4caf50"),
        WESTERN_COLOR("#8bc34a"),
        NON_H_COLOR("#2c9bf8"),
        IMAGE_SET_COLOR("#3c4fb3"),
        COSPLAY_COLOR("#921aa6"),
        ASIAN_PORN_COLOR("#a685df"),
        MISC_COLOR("#f36594"),
        ;

        constructor(color: String) : this(color.toColorInt())
    }

    fun getLocaleSourceUtil(language: String?) = when (language) {
        "english", "eng" -> Locale.forLanguageTag("en")
        "japanese" -> Locale.forLanguageTag("ja")
        "chinese" -> Locale.forLanguageTag("zh")
        "spanish" -> Locale.forLanguageTag("es")
        "korean" -> Locale.forLanguageTag("ko")
        "russian" -> Locale.forLanguageTag("ru")
        "french" -> Locale.forLanguageTag("fr")
        "portuguese" -> Locale.forLanguageTag("pt")
        "thai" -> Locale.forLanguageTag("th")
        "german" -> Locale.forLanguageTag("de")
        "italian" -> Locale.forLanguageTag("it")
        "vietnamese" -> Locale.forLanguageTag("vi")
        "polish" -> Locale.forLanguageTag("pl")
        "hungarian" -> Locale.forLanguageTag("hu")
        "dutch" -> Locale.forLanguageTag("nl")
        else -> null
    }

    /** Build a source-specific browse-search query for a namespaced tag, so tapping a details tag
     *  chip filters that source. Only the metadata sources Reikai ships are handled: E-Hentai/ExHentai
     *  (namespace:"tag"$), the built-in nhentai.net, and Pururin (bare tag name); other metadata
     *  sources fall through to the E-Hentai grammar. MangaDex wrapping is intentionally omitted
     *  (source not built in; see ROADMAP "MangaDex as an adult metadata source"). */
    fun getWrappedTag(
        sourceId: Long?,
        namespace: String? = null,
        tag: String? = null,
        fullTag: String? = null,
    ): String? {
        val parsed = when {
            fullTag != null -> parseTag(fullTag)
            namespace != null && tag != null -> RaisedTag(namespace, tag, TAG_TYPE_DEFAULT)
            else -> null
        }
        val parsedNamespace = parsed?.namespace ?: return null
        val name = parsed.name.substringBefore('|').trim()
        return when {
            sourceId == NHENTAI_NET_SOURCE_ID -> wrapTagNHentai(parsedNamespace, name)
            sourceId == PURURIN_SOURCE_ID -> name
            sourceId in eHentaiSourceIds -> wrapTag(parsedNamespace, name)
            else -> wrapTag(parsedNamespace, name)
        }
    }

    private fun wrapTag(namespace: String, tag: String) = if (tag.contains(spaceRegex)) {
        "$namespace:\"$tag$\""
    } else {
        "$namespace:$tag$"
    }

    private fun wrapTagNHentai(namespace: String, tag: String) = if (tag.contains(spaceRegex)) {
        if (namespace == "tag") """"$tag"""" else """$namespace:"$tag""""
    } else {
        "$namespace:$tag"
    }

    fun parseTag(tag: String) = RaisedTag(
        (if (tag.startsWith("-")) tag.substringAfter("-") else tag)
            .substringBefore(':', missingDelimiterValue = "").trimOrNull(),
        tag.substringAfter(':', missingDelimiterValue = tag).trim(),
        if (tag.startsWith("-")) TAG_TYPE_EXCLUDE else TAG_TYPE_DEFAULT,
    )

    /** Contrast-appropriate text color for a genre badge painted with [GenreColor]. */
    @ColorInt
    fun genreTextColor(genre: GenreColor): Int = when (genre) {
        GenreColor.IMAGE_SET_COLOR, GenreColor.COSPLAY_COLOR -> Color.WHITE
        else -> Color.BLACK
    }

    private const val TAG_TYPE_DEFAULT = 1
    private const val TAG_TYPE_EXCLUDE = 69
    private val spaceRegex = "\\s".toRegex()
}
