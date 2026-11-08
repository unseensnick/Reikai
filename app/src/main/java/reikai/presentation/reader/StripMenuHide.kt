package reikai.presentation.reader

/**
 * The high quality renderer's long strip menu, hidden once the strip has scrolled further than [threshold] screen
 * pixels from where it showed, by the rule the other long strips use ([scrollHidesMenu]). That renderer reports
 * its scroll a frame at a time, too little to pass any threshold alone, so the distance adds up.
 */
internal class StripMenuHide(private val threshold: Int) {
    private var scrolled = 0f

    /** Whether a scroll of [dy] hides the menu. */
    fun onScroll(dy: Float, menuVisible: Boolean): Boolean {
        if (!menuVisible) {
            scrolled = 0f
            return false
        }
        scrolled += dy
        if (!scrollHidesMenu(scrolled, threshold)) return false
        scrolled = 0f
        return true
    }
}
