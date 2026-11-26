package reikai.data

/**
 * [text] folded the way `contains(ignoreCase = true)` compares it on Android, so the `chapter:` term matches as
 * every other library field does: per UTF-16 char, upper then lower (İ finds i, ß does not find ss). Android leaves
 * a surrogate pair unfolded in that comparison, and so does this.
 */
fun foldCase(text: String): String = String(CharArray(text.length) { text[it].uppercaseChar().lowercaseChar() })

/**
 * The `name_folded` a chapter row stores beside [name]: its [foldCase] when a non-ASCII char folds, else null.
 * SQLite's LIKE folds ASCII itself, so a null row is searched by its name and most rows store nothing.
 */
fun chapterNameFold(name: String): String? =
    if (name.any { it.code > 127 && it.uppercaseChar().lowercaseChar() != it }) foldCase(name) else null
