package reikai.novel.content

/**
 * Each match of this regex in [input], as its start and end, walked by one matcher. Kotlin's `findAll` and
 * lambda `replace` build a matcher per match, and Android's hands the whole input to ICU each time, so a
 * chapter with many matches cost the match count times its length.
 */
internal inline fun Regex.forEachMatch(input: CharSequence, action: (start: Int, end: Int) -> Unit) {
    val matcher = toPattern().matcher(input)
    while (matcher.find()) action(matcher.start(), matcher.end())
}

/** [input] with each match replaced by [transform] of its text, through one matcher (see [forEachMatch]). */
internal inline fun Regex.replaceEach(input: String, transform: (String) -> CharSequence): String {
    val out = StringBuilder(input.length)
    var from = 0
    forEachMatch(input) { start, end ->
        out.append(input, from, start).append(transform(input.substring(start, end)))
        from = end
    }
    return out.append(input, from, input.length).toString()
}
