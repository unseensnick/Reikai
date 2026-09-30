package exh.util

import androidx.core.graphics.toColorInt
import dev.icerock.moko.resources.StringResource
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.icons.FlagEmoji.Companion.getEmojiLangFlag
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

    /** A gallery genre's badge colour and label, or null for a genre the site does not list. */
    fun ehGenre(genre: String?): Pair<GenreColor, StringResource>? = when (genre) {
        "doujinshi" -> GenreColor.DOUJINSHI_COLOR to MR.strings.doujinshi
        "manga" -> GenreColor.MANGA_COLOR to MR.strings.content_type_manga
        "artistcg" -> GenreColor.ARTIST_CG_COLOR to MR.strings.artist_cg
        "gamecg" -> GenreColor.GAME_CG_COLOR to MR.strings.game_cg
        "western" -> GenreColor.WESTERN_COLOR to MR.strings.western
        "non-h" -> GenreColor.NON_H_COLOR to MR.strings.non_h
        "imageset" -> GenreColor.IMAGE_SET_COLOR to MR.strings.image_set
        "cosplay" -> GenreColor.COSPLAY_COLOR to MR.strings.cosplay
        "asianporn" -> GenreColor.ASIAN_PORN_COLOR to MR.strings.asian_porn
        "misc" -> GenreColor.MISC_COLOR to MR.strings.misc
        else -> null
    }

    /** The flag of a gallery's first language tag this app knows a locale for. */
    fun ehLanguageFlag(metadata: EHentaiSearchMetadata): String? = metadata.tags
        .filter { it.namespace == EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE }
        .firstNotNullOfOrNull { getLocaleSourceUtil(it.name) }
        ?.let { getEmojiLangFlag(it.toLanguageTag()) }

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

    /** The E-Hentai tag grammar, which Lanraragi also accepts: namespace:tag$, quoted when it has a space. */
    fun wrapTag(namespace: String, tag: String) = if (tag.contains(spaceRegex)) {
        "$namespace:\"$tag$\""
    } else {
        "$namespace:$tag$"
    }

    /** The nhentai tag grammar: a bare quoted name for the plain tag namespace. */
    fun wrapTagNHentai(namespace: String, tag: String) = if (tag.contains(spaceRegex)) {
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

    private const val TAG_TYPE_DEFAULT = 1
    private const val TAG_TYPE_EXCLUDE = 69
    private val spaceRegex = "\\s".toRegex()
}
