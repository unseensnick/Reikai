package reikai.domain.reader

/**
 * The Bottom tap layout's zone height a reader opens with, in percent of the page. Each reader stores its own
 * height, but both offer the same layouts, so they start from the same one.
 */
const val BOTTOM_ZONE_DEFAULT_PERCENT = 12

/** How far one volume-key press scrolls by default, as a fraction of the screen, in both readers' own setting. */
const val VOLUME_KEY_SCROLL_DEFAULT_FRACTION = 0.75f
