package reikai.presentation.reader.navigation

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import reikai.domain.novel.NovelTapLayout
import reikai.domain.novel.fitTo
import reikai.presentation.reader.navigation

/**
 * Manga's tap layouts by the index its preference stores: Mihon's six ([ReaderPreferences.TapZones]), then the
 * layouts the novel reader has that Mihon lacks, appended so a stored index keeps its meaning. Every layout but
 * Default is the novel reader's, so the two readers draw it and offer its inversions by one rule.
 */
object MangaTapLayouts {

    private val added = listOf(
        NovelTapLayout.THIRDS,
        NovelTapLayout.CENTER,
        NovelTapLayout.CENTER_LARGE,
        NovelTapLayout.BOTTOM,
    )

    /** In [ReaderPreferences.TapZones] order; Default (null) is each viewer's own. */
    private val layouts: List<NovelTapLayout?> = listOf(
        null,
        NovelTapLayout.L_SHAPED,
        NovelTapLayout.KINDLISH,
        NovelTapLayout.EDGE,
        NovelTapLayout.RIGHT_AND_LEFT,
        NovelTapLayout.DISABLED,
    ) + added

    /** Every layout's name, by stored index. */
    val names: List<StringResource> = ReaderPreferences.TapZones + added.map { it.titleRes }

    /** The added layout stored at [index], null for one of Mihon's. */
    fun added(index: Int): NovelTapLayout? = added.getOrNull(index - ReaderPreferences.TapZones.size)

    /** The layout stored at [index], null for Default or an index no layout has. */
    fun layout(index: Int): NovelTapLayout? = layouts.getOrNull(index)

    /** The regions of the layout at [index], null for Default, which the viewer picks. */
    fun navigation(
        index: Int,
        bottomZoneHeightPercent: Int,
        invertMode: TappingInvertMode,
        isReversed: Boolean,
    ): ViewerNavigation? = layout(index)?.navigation(bottomZoneHeightPercent, invertMode, isReversed)

    /** The inversions that change the layout at [index]. */
    fun invertModes(index: Int): List<TappingInvertMode> = invertModes(layout(index))

    /** The inversions that change [layout], every one for Default (null). */
    fun invertModes(layout: NovelTapLayout?): List<TappingInvertMode> = layout?.invertModes ?: TappingInvertMode.entries

    /** Whether the layout at [index] offers an inversion choice, by the novel reader's rule. */
    fun offersInvert(index: Int): Boolean = offersInvert(layout(index))

    /** Whether [layout] offers an inversion choice, which Default (null) always does. */
    fun offersInvert(layout: NovelTapLayout?): Boolean = layout?.offersInvert ?: true
}

/**
 * Sets the pager's or the long strip's layout to [index], replacing an inversion it cannot draw as the novel
 * reader's `setReaderTapLayout` does. WebGPU reads the pager's.
 */
fun ReaderPreferences.setTapLayout(pager: Boolean, index: Int) {
    (if (pager) navigationModePager else navigationModeWebtoon).set(index)
    MangaTapLayouts.layout(index)?.let { (if (pager) pagerNavInverted else webtoonNavInverted).fitTo(it) }
}
