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

    /** Mihon's Disabled, which has no zones to invert. */
    private const val DISABLED = 5

    /** Every layout's name, by stored index. */
    val names: List<StringResource> = ReaderPreferences.TapZones + added.map { it.titleRes }

    /** The added layout stored at [index], null for one of Mihon's. */
    fun added(index: Int): NovelTapLayout? = added.getOrNull(index - ReaderPreferences.TapZones.size)

    /** An added layout's regions, null for Mihon's, which each viewer builds as upstream does. */
    fun navigation(index: Int, bottomZoneHeightPercent: Int): ViewerNavigation? =
        added(index)?.navigation(bottomZoneHeightPercent)

    /** The inversions that change the layout at [index]; one or none is no choice to offer. */
    fun invertModes(index: Int): List<TappingInvertMode> = when (index) {
        DISABLED -> emptyList()
        else -> added(index)?.invertModes ?: TappingInvertMode.entries
    }
}
