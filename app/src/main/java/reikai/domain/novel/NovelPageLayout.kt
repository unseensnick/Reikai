package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * How a novel chapter is read: one scrolling column, or that column a page at a time in either direction.
 * One value, so a direction with no pages to turn cannot be stored. Persisted by name through `getEnum`.
 */
enum class NovelPageLayout(val titleRes: StringResource) {
    SCROLL(MR.strings.novel_page_layout_scroll),
    PAGED_LTR(MR.strings.left_to_right_viewer),
    PAGED_RTL(MR.strings.right_to_left_viewer),
    ;

    val isPaged: Boolean get() = this != SCROLL

    val isRtl: Boolean get() = this == PAGED_RTL

    /** Paged always shows the page slider, as manga's pager does, so the rail is a scrolling choice. */
    val offersRail: Boolean get() = !isPaged
}
