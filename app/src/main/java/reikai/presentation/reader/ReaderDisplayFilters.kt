package reikai.presentation.reader

import tachiyomi.core.common.preference.Preference

/**
 * How the page is lit and tinted: custom brightness, a colour filter over the page, grayscale and
 * inverted colours. Both readers support all of it, and each keeps its own values, so the host applies
 * whichever set the open session hands over and the settings sheet edits that same set.
 */
class ReaderDisplayFilters(
    val customBrightness: Preference<Boolean>,
    /** -75 to 100: below 0 dims with an overlay, above 0 sets the screen brightness, 0 is the system's. */
    val customBrightnessValue: Preference<Int>,
    val colorFilter: Preference<Boolean>,
    /** Packed ARGB. */
    val colorFilterValue: Preference<Int>,
    /** An index into `ReaderPreferences.ColorFilterMode`. */
    val colorFilterMode: Preference<Int>,
    val grayscale: Preference<Boolean>,
    val invertedColors: Preference<Boolean>,
)
