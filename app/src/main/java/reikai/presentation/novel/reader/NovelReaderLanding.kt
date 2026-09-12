package reikai.presentation.novel.reader

/**
 * Where a built page lands. A document rebuilt for the chapter already open (a rotation, a theme
 * switch, a chapter-text setting) lands where the reader actually got to: landing on the position
 * the chapter opened at puts them back there and lets the next scroll-end save write it over what
 * they had reached. The shared host answers the same question in NovelReaderViewModel.landingOf.
 */
class NovelReaderLanding {

    @Volatile
    var percent: Int = 0
        private set

    @Volatile
    private var openedId: Long? = null

    /** A document for [chapterId] is about to be built. [resumePercent] is where that chapter opens,
     *  used only when it is not the chapter already open. */
    fun opened(chapterId: Long, resumePercent: Int) {
        if (openedId != chapterId) percent = resumePercent.coerceIn(0, 100)
        openedId = chapterId
    }

    /** The web layer reported where the reader is now. */
    fun reported(percent: Int) {
        this.percent = percent.coerceIn(0, 100)
    }
}
