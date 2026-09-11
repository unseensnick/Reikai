package reikai.presentation.novel.reader

import reikai.novel.font.fontDisplayName
import reikai.novel.font.isGenericFont
import reikai.novel.font.isSupportedFontFile
import reikai.presentation.reader.readerDarkPreset

/*
 * Preference values on their way into the reader document's <style> block. Restoring a backup writes
 * almost every preference key, so a shared file decides these strings, and one carrying a closing
 * style tag would end the block and start whatever followed it in a page that runs JavaScript with
 * the app's cookies. Each is checked against the shape it can legitimately have rather than escaped,
 * because CSS escaping is per-context while these shapes are small enough to state exactly.
 */

/** `#rgb` through `#rrggbbaa`, which is every form the presets and the colour picker write. */
private val cssColorPattern = Regex("^#[0-9a-fA-F]{3,8}$")

private val cssTextAlignments = setOf("left", "center", "right", "justify")

fun cssColorOrDefault(value: String, fallback: String): String =
    if (cssColorPattern.matches(value)) value else fallback

fun cssBackgroundColor(value: String): String = cssColorOrDefault(value, readerDarkPreset.background)

fun cssTextColor(value: String): String = cssColorOrDefault(value, readerDarkPreset.textColor)

fun cssTextAlign(value: String): String = if (value in cssTextAlignments) value else "left"

/**
 * A family name as CSS can read it, quoted or not: the characters that would end the declaration or
 * the quotes around it are the ones dropped. An empty result is the reader's own default face, which
 * is what an unset preference already means.
 */
fun cssFontFamily(value: String): String =
    value.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()

/**
 * What a page calls [family]. A font the user added is stored as its file name, and a dot is not
 * valid in a family name, so the page uses the readable name the picker shows.
 */
fun cssFontName(family: String): String =
    cssFontFamily(if (isSupportedFontFile(family)) fontDisplayName(family) else family)

/**
 * [family] as a `font-family` value. A name is quoted, because unquoted it has to be a run of
 * identifiers, and a word that starts with a digit ("Source Sans 3") is not one: the declaration is
 * dropped and the text falls back. The generic families stay bare, since quoted they name no font.
 * Safe inside the quotes because [cssFontFamily] drops quotes and backslashes.
 */
fun cssFontFamilyValue(family: String): String {
    if (isGenericFont(family)) return family
    val name = cssFontName(family)
    return if (name.isEmpty()) "" else "'$name'"
}

/**
 * Whether a path can go inside a quoted `url('...')`. The font mirror's path carries a file name the
 * user chose, and a quote or a backslash in it would end the token early. A space is fine, since the
 * quotes are what it is inside.
 */
fun isSafeInCssUrl(url: String): Boolean =
    url.none { it == '\'' || it == '"' || it == '\\' || it == '\n' || it == '\r' }
