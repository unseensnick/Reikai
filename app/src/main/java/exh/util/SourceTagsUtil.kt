package exh.util

import androidx.core.graphics.toColorInt
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
}
