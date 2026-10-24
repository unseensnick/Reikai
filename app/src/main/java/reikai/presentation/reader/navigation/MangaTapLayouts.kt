package reikai.presentation.reader.navigation

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import reikai.domain.novel.NovelTapLayout
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
    private const val DISABLED = 5

    /** Every layout's name, by stored index. */
    val names: List<StringResource> = ReaderPreferences.TapZones + added.map { it.titleRes }

    /** The added layout stored at [index], null for one of Mihon's. */
    fun added(index: Int): NovelTapLayout? = added.getOrNull(index - ReaderPreferences.TapZones.size)

    /** An added layout's regions, null for Mihon's, which each viewer builds as upstream does. */
    fun navigation(index: Int, bottomZoneHeightPercent: Int): ViewerNavigation? =
        added(index)?.navigation(bottomZoneHeightPercent)

    /**
     * The novel reader's layout the one at [index] answers the inversion rules with, null for Default and
     * Mihon's four zoned layouts, which every inversion changes.
     */
    fun layout(index: Int): NovelTapLayout? = if (index == DISABLED) NovelTapLayout.DISABLED else added(index)

    /** The inversions that change the layout at [index]. */
    fun invertModes(index: Int): List<TappingInvertMode> = layout(index)?.invertModes ?: TappingInvertMode.entries

    /** Whether the layout at [index] offers an inversion choice, by the novel reader's rule. */
    fun offersInvert(index: Int): Boolean = layout(index)?.offersInvert ?: true
}
