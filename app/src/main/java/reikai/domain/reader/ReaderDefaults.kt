package reikai.domain.reader

/**
 * The Bottom tap layout's zone height a reader opens with, in percent of the page. Each reader stores its own
 * height, but both offer the same layouts, so they start from the same one.
 */
const val BOTTOM_ZONE_DEFAULT_PERCENT = 12

/** How far one volume-key press scrolls by default, as a fraction of the screen, in both readers' own setting. */
const val VOLUME_KEY_SCROLL_DEFAULT_FRACTION = 0.75f

/** The page contrast a reader opens with, in percent: 100 leaves the page as it is. */
const val CONTRAST_DEFAULT_PERCENT = 100

/** Seconds between auto-scroll's page turns, in both readers' own setting. */
const val AUTO_SCROLL_INTERVAL_DEFAULT_SECONDS = 5

/** Auto-scroll's speed on a strip or a scrolled novel, in the one unit both readers' ranges share. */
const val AUTO_SCROLL_SPEED_DEFAULT = 1.0f

/** Pages between e-ink flashes, in both readers' own setting. */
const val FLASH_PAGE_INTERVAL_DEFAULT = 1
