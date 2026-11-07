package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.DualPageView

/** Whether this dual page view setting shows two pages on a screen [width] by [height], in any one unit: When wide
 *  is a screen wider than tall, upstream's rule (WebGpuViewer.isDualPageMode). Manga's sheet and the novel reader
 *  both answer it here. */
fun DualPageView.showsTwoPages(width: Int, height: Int): Boolean = when (this) {
    DualPageView.NEVER -> false
    DualPageView.ALWAYS -> true
    DualPageView.WIDE -> width > 0 && height > 0 && width > height
}
