package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class NovelViewportSettingsTest {

    /** The viewport is rebuilt only when [NovelViewportSettings.watched] fires, so a setting it is built
     *  with but does not watch is picked up on the next open instead. */
    @Test
    fun `every setting the viewport is built with is one its rebuild watches`() {
        val read = EmittingPreferenceStore().also { NovelViewportSettings.read(NovelPreferences(it)) }
        val watched = EmittingPreferenceStore().also { NovelViewportSettings.watched(NovelPreferences(it)) }

        watched.getAll().keys shouldBe read.getAll().keys
    }

    /** A spread draws its column where the column is not, and selection's handles and toolbar go where it is. */
    @ParameterizedTest
    @CsvSource("true,false,true", "true,true,false", "false,false,false", "false,true,false")
    fun `text is selectable only one page at a time`(selectable: Boolean, spread: Boolean, selects: Boolean) {
        val settings = NovelViewportSettings.read(NovelPreferences(EmittingPreferenceStore()))
            .copy(textSelectable = selectable)

        settings.selectsText(spread) shouldBe selects
    }
}
