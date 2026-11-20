package reikai.domain.stats

import eu.kanade.domain.ui.UiPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * The overflow menus' Statistics entry, read by both the library and the recents engines through
 * [inMenus]. Changed after the read, so a seed-only answer cannot pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsShortcutTest {

    @Test
    fun `the menus follow a shortcut changed while they are shown`() = runTest {
        val shortcut = UiPreferences(EmittingPreferenceStore()).statsShortcut
        val inMenus = shortcut.inMenus(backgroundScope)
        runCurrent()

        shortcut.set(StatsShortcut.MENUS)
        runCurrent()

        inMenus.value shouldBe true
    }
}
