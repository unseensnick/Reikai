package reikai.domain.recommendation

import java.text.Normalizer

/**
 * Normalizes a manga title into a dedup key that collapses the cosmetic differences a plain
 * lowercase-and-collapse key let slip, which is what duplicated carousel entries. One NFKD pass does
 * the work, applying compatibility decomposition (fullwidth to ASCII, circled digits to digits) and
 * canonical decomposition (accented letters to base plus combining mark); the marks are then stripped,
 * the text lowercased, and every run of non-alphanumerics folded to one space. No fuzzy matching:
 * exact normalized equality only, so distinct series are never merged.
 */
object TitleNormalizer {

    private val combiningMarks = Regex("\\p{Mn}+")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(title: String): String {
        if (title.isBlank()) return ""
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD)
        val noMarks = combiningMarks.replace(decomposed, "")
        return noMarks.lowercase().replace(nonAlphanumeric, " ").trim()
    }
}
