package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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
}
