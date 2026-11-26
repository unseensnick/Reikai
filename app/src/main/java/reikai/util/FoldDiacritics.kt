package reikai.util

import java.text.Normalizer

private val combiningMarks = Regex("""\p{Mn}+""")

/**
 * This text with accented letters folded to their base letter ("caída" to "caida") and compatibility
 * forms to plain ones (fullwidth to ASCII, circled digits to digits): one NFKD pass, marks stripped.
 * What two sites spelling one title differently are matched through.
 */
fun String.foldDiacritics(): String = combiningMarks.replace(Normalizer.normalize(this, Normalizer.Form.NFKD), "")
