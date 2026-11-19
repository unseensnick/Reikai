package reikai.domain.entry

/**
 * A series' other titles, the one rule every reader of them shares: each member's raw title (the lead
 * included), then the stored alternative titles, without [main] and without repeats, ignoring case and
 * surrounding space; the first spelling met wins. No cap here: a caller that searches with them caps.
 */
fun seriesAltTitles(main: String, memberTitles: List<String>, stored: List<String>): List<String> {
    val seen = hashSetOf(main.trim().lowercase())
    return (memberTitles + stored).map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
}
