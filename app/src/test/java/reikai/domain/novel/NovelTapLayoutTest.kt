package reikai.domain.novel

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

/** A layout's inversion as a settings row shows it, which must be the one the page is read with. */
class NovelTapLayoutTest {

    private val preferences = NovelPreferences(EmittingPreferenceStore())

    @Test
    fun `an inversion the new layout can draw is kept`() {
        preferences.readerTapInvert(paged = false).set(TappingInvertMode.BOTH)

        preferences.setReaderTapLayout(paged = false, NovelTapLayout.KINDLISH)

        preferences.readerTapInvert(paged = false).get() shouldBe TappingInvertMode.BOTH
    }

    /** The bottom zone spans the width, so both inversions draw it where vertical alone does. */
    @Test
    fun `the bottom layout keeps the vertical half of both`() {
        preferences.readerTapInvert(paged = false).set(TappingInvertMode.BOTH)

        preferences.setReaderTapLayout(paged = false, NovelTapLayout.BOTTOM)

        preferences.readerTapInvert(paged = false).get() shouldBe TappingInvertMode.VERTICAL
    }

    @Test
    fun `the bottom layout drops a horizontal inversion`() {
        preferences.readerTapInvert(paged = false).set(TappingInvertMode.HORIZONTAL)

        preferences.setReaderTapLayout(paged = false, NovelTapLayout.BOTTOM)

        preferences.readerTapInvert(paged = false).get() shouldBe TappingInvertMode.NONE
    }

    @Test
    fun `a centre layout drops any inversion`() {
        preferences.readerTapInvert(paged = false).set(TappingInvertMode.VERTICAL)

        preferences.setReaderTapLayout(paged = false, NovelTapLayout.CENTER)

        preferences.readerTapInvert(paged = false).get() shouldBe TappingInvertMode.NONE
    }
}
