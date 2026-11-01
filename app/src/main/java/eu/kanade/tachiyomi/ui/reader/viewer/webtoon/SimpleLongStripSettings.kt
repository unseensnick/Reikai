package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import eu.kanade.tachiyomi.ui.reader.viewer.NavigationSettings

/**
 * Settings only the webtoon viewers use.
 */
data class SimpleLongStripSettings(
    val page: Page,
    val doubleTapZoom: Boolean,
    val navigation: NavigationSettings,
    // RK: user-settable volume-key scroll distance as a fraction of the screen, outside Page: it remakes nothing
    val volumeKeysScrollFraction: Float,
) {

    /**
     * What the pages are made with, so a change remakes them.
     */
    data class Page(
        val sidePadding: Int,
        val dualPageSplit: Boolean,
        val dualPageInvert: Boolean,
        val dualPageRotateToFit: Boolean,
        val dualPageRotateToFitInvert: Boolean,
    )
}
