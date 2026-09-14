package reikai.presentation.reader

/**
 * The bounds every control for these novel settings offers, so the reader's sheet, its text-size button
 * and Settings cannot disagree about how far a value goes. The float settings are stored as themselves
 * and stepped in tenths, which is the unit these ranges are in.
 */
object NovelTextRanges {
    val fontSize = 10..40
    val lineHeightTenths = 8..50
    val paragraphIndentTenths = 0..100

    /** Past tsundoku's own 3em ceiling, because their renderer draws a blank line under the setting
     *  that ours removes, so their top end is not ours. */
    val paragraphSpacingTenths = 0..40

    /** A third of a phone's short edge, past which a column of text stops being readable. */
    val marginDp = 0..64
    val autoSplitWords = 20..2000
}
