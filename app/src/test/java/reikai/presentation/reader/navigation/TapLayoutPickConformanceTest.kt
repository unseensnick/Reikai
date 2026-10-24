package reikai.presentation.reader.navigation

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelTapLayout
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference

/**
 * Picking a tap layout replaces an inversion it cannot draw with the nearest one it can, in both readers and for
 * each reader's paged and scrolling layouts, so the inversion a settings row shows is the one in effect.
 */
class TapLayoutPickConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("writers")
    fun `an inversion the new layout can draw is kept`(writer: TapLayoutWriter) {
        writer.invert.set(TappingInvertMode.BOTH)

        writer.pick(NovelTapLayout.KINDLISH)

        writer.invert.get() shouldBe TappingInvertMode.BOTH
    }

    /** The bottom zone spans the width, so both inversions draw it where vertical alone does. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("writers")
    fun `the bottom layout keeps the vertical half of both`(writer: TapLayoutWriter) {
        writer.invert.set(TappingInvertMode.BOTH)

        writer.pick(NovelTapLayout.BOTTOM)

        writer.invert.get() shouldBe TappingInvertMode.VERTICAL
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writers")
    fun `the bottom layout drops a horizontal inversion`(writer: TapLayoutWriter) {
        writer.invert.set(TappingInvertMode.HORIZONTAL)

        writer.pick(NovelTapLayout.BOTTOM)

        writer.invert.get() shouldBe TappingInvertMode.NONE
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writers")
    fun `a centre layout drops any inversion`(writer: TapLayoutWriter) {
        writer.invert.set(TappingInvertMode.VERTICAL)

        writer.pick(NovelTapLayout.CENTER)

        writer.invert.get() shouldBe TappingInvertMode.NONE
    }

    /** A write would mark the inversion as set, which a backup then carries though nothing changed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("writers")
    fun `an inversion the layout can draw is left unwritten`(writer: TapLayoutWriter) {
        writer.pick(NovelTapLayout.BOTTOM)

        writer.invert.isSet() shouldBe false
    }

    companion object {
        @JvmStatic
        fun writers(): List<TapLayoutWriter> = listOf(
            NovelWriter(paged = true),
            NovelWriter(paged = false),
            MangaWriter(pager = true),
            MangaWriter(pager = false),
        )
    }
}

/** One reader's layout pick and the inversion it keeps, over preferences in memory. */
interface TapLayoutWriter {
    val invert: Preference<TappingInvertMode>
    fun pick(layout: NovelTapLayout)
}

private class NovelWriter(private val paged: Boolean) : TapLayoutWriter {
    private val preferences = NovelPreferences(EmittingPreferenceStore())
    override val invert = preferences.readerTapInvert(paged)
    override fun pick(layout: NovelTapLayout) = preferences.setReaderTapLayout(paged, layout)
    override fun toString() = if (paged) "novel paged" else "novel scrolling"
}

private class MangaWriter(private val pager: Boolean) : TapLayoutWriter {
    private val preferences = ReaderPreferences(EmittingPreferenceStore())
    override val invert = if (pager) preferences.pagerNavInverted else preferences.webtoonNavInverted
    override fun pick(layout: NovelTapLayout) = preferences.setTapLayout(pager, INDEX.getValue(layout))
    override fun toString() = if (pager) "manga pager" else "manga long strip"

    private companion object {
        /** The stored index manga picks each probed layout by. */
        val INDEX = mapOf(NovelTapLayout.KINDLISH to 2, NovelTapLayout.CENTER to 7, NovelTapLayout.BOTTOM to 9)
    }
}
