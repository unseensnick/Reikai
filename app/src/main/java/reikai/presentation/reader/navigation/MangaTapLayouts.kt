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
 * layouts the novel reader has that Mihon lacks, appended so a stored index keeps its meaning. An added layout
 * is built by the novel reader's own rule ([navigation]), so the two readers draw it alike.
 */
object MangaTapLayouts {

    private val added = listOf(
        NovelTapLayout.THIRDS,
        NovelTapLayout.CENTER,
        NovelTapLayout.CENTER_LARGE,
        NovelTapLayout.BOTTOM,
    )

    /** Mihon's Disabled, which is the novel reader's Disabled. */
    const val DISABLED = 5

    /** Mihon's Right and left, the same two columns the novel reader's Right and left draws. */
    private const val RIGHT_AND_LEFT = 4

    /** Every layout's name, by stored index. */
    val names: List<StringResource> = ReaderPreferences.TapZones + added.map { it.titleRes }

    /** The added layout stored at [index], null for one of Mihon's. */
    fun added(index: Int): NovelTapLayout? = added.getOrNull(index - ReaderPreferences.TapZones.size)

    /** An added layout's regions, null for Mihon's, which `NavigationSettings.navigator` builds. */
    fun navigation(
        index: Int,
        bottomZoneHeightPercent: Int,
        invertMode: TappingInvertMode,
        isReversed: Boolean,
    ): ViewerNavigation? = added(index)?.navigation(bottomZoneHeightPercent, invertMode, isReversed)

    /**
     * The novel reader's layout the one at [index] answers the inversion rules with, null for Default and
     * Mihon's three other zoned layouts, which every inversion changes.
     */
    fun layout(index: Int): NovelTapLayout? = when (index) {
        DISABLED -> NovelTapLayout.DISABLED
        RIGHT_AND_LEFT -> NovelTapLayout.RIGHT_AND_LEFT
        else -> added(index)
    }

    /** The inversions that change the layout at [index]. */
    fun invertModes(index: Int): List<TappingInvertMode> = invertModes(layout(index))

    /** The inversions that change [layout], every one for a layout the novel reader lacks (null). */
    fun invertModes(layout: NovelTapLayout?): List<TappingInvertMode> = layout?.invertModes ?: TappingInvertMode.entries

    /** Whether the layout at [index] offers an inversion choice, by the novel reader's rule. */
    fun offersInvert(index: Int): Boolean = offersInvert(layout(index))

    /** Whether [layout] offers an inversion choice, which one the novel reader lacks (null) always does. */
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
