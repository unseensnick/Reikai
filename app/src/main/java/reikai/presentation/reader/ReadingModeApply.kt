package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode

/**
 * What Apply in the quick reading-mode menu writes to the series. The menu highlights the mode in use,
 * which for a series on Default is inherited, so only a tile the reader actually tapped counts, and only
 * when it differs from the series' own [stored] mode. Tapping the inherited tile still pins it.
 */
object ReadingModeApply {

    fun modeToApply(picked: ReadingMode?, stored: ReadingMode): ReadingMode? = picked?.takeIf { it != stored }
}
