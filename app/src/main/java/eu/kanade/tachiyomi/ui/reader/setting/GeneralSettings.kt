package eu.kanade.tachiyomi.ui.reader.setting

/**
 * Settings for every viewer alike.
 *
 * RK: upstream also holds keep screen on, grayscale, invert, fullscreen and cutout; the host reads those off the
 * session, since a novel keeps its own.
 */
data class GeneralSettings(
    val theme: ReaderPreferences.ReaderTheme,
    val longTapEnabled: Boolean,
    val usePageTransitions: Boolean,
    val doubleTapAnimDuration: Int,
    val volumeKeysEnabled: Boolean,
    val volumeKeysInverted: Boolean,
    val alwaysShowChapterTransition: Boolean,
    val navigationOverlayOnStart: Boolean,
) {
    val automaticBackground: Boolean
        get() = theme == ReaderPreferences.ReaderTheme.AUTOMATIC
}
