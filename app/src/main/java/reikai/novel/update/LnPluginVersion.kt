package reikai.novel.update

/**
 * Compares LN plugin version strings. Mirrors lnreader's `src/utils/compareVersion.ts` so the
 * outdated-detection semantics match upstream: digit-or-dot segments are parsed numerically,
 * shorter sides are padded with zeros, and non-parseable input falls back to a string compare.
 *
 * Numeric (not lexical) compare is load-bearing: `"2.0"` must rank above `"1.99"`.
 */
object LnPluginVersion {

    private val DIGITS_AND_DOTS = Regex("[^\\d.]")

    /**
     * Returns -1, 0, or 1 (matching `Int.compareTo`). Returns 0 when both inputs are blank.
     */
    fun compare(a: String, b: String): Int {
        val sa = a.replace(DIGITS_AND_DOTS, "")
        val sb = b.replace(DIGITS_AND_DOTS, "")
        if (sa.isEmpty() && sb.isEmpty()) return a.compareTo(b)
        if (sa.isEmpty()) return a.compareTo(b)
        if (sb.isEmpty()) return a.compareTo(b)

        val partsA = sa.split('.')
        val partsB = sb.split('.')
        val len = maxOf(partsA.size, partsB.size)
        for (i in 0 until len) {
            // Missing segments are padded with zero so "1" == "1.0.0". A segment that exists but
            // doesn't parse falls back to string compare on the raw input so we still return a
            // stable sign rather than throwing.
            val ai = partsA.getOrNull(i)?.let { it.toIntOrNull() ?: return a.compareTo(b) } ?: 0
            val bi = partsB.getOrNull(i)?.let { it.toIntOrNull() ?: return a.compareTo(b) } ?: 0
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
