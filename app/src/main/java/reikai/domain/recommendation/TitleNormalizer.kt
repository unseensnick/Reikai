package reikai.domain.recommendation

import java.text.Normalizer

/**
 * Normalizes a manga title into a dedup key that collapses cosmetic differences which the old
 * `lowercase + collapse-separators` key let slip (the source of duplicate carousel entries).
 *
 * One NFKD pass does the heavy lifting: it applies compatibility decomposition (fullwidth "ａｂｃ" to
 * "abc", circled "①" to "1") and canonical decomposition (accented "café" to base "e" plus a
 * combining acute mark). We then strip the combining marks, lowercase, and fold every run of
 * non-alphanumerics to a single space. So "Spice & Wolf", "Spice ＆ Wolf", and "spice and  wolf"
 * (after the caller supplies the synonym) all key the same, while genuinely different titles stay
 * apart. No fuzzy matching: only exact normalized equality, to avoid merging distinct series.
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
