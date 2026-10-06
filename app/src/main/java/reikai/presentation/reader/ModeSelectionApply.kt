package reikai.presentation.reader

/**
 * What Apply in a quick mode menu (reading mode, rotation) writes to the series. The menu highlights the
 * mode in use, which for a series on Default is inherited, so only a tile the reader actually tapped
 * counts, and only when it differs from the series' own [stored] mode. Tapping the inherited tile still pins it.
 */
object ModeSelectionApply {

    fun <T> modeToApply(picked: T?, stored: T): T? = picked?.takeIf { it != stored }
}
