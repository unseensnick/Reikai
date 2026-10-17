package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class NovelReaderSettingsTest {

    /**
     * The live settings are [NovelReaderSettings.read] re-run whenever [NovelReaderSettings.watched]
     * fires, so a setting read but not watched reaches the page only on the next open. Neither store is
     * collected, so the keys are the preferences each side builds.
     */
    @Test
    fun `every setting the reader's settings read is one their change stream watches`() {
        val read = EmittingPreferenceStore().also {
            NovelReaderSettings.read(NovelPreferences(it), ReaderOrientation.DEFAULT.flagValue)
        }
        val watched = EmittingPreferenceStore().also { NovelReaderSettings.watched(NovelPreferences(it)) }

        // A superset, not equal: the reader watches both modes' tap layouts and reads the one its page layout picks.
        watched.getAll().keys shouldContainAll read.getAll().keys
    }
}
