package eu.kanade.tachiyomi.ui.setting.controllers

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.controllers.debug.LnPluginHostProbeController
import eu.kanade.tachiyomi.ui.setting.controllers.debug.NovelTrackProbeController
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.i18n.MR
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

/**
 * Beta-only settings shelf for the WIP light-novel reader. After Phase 8 follow-up CR11 this
 * shrinks to two genuinely-debug-only entries (plugin host probe + tracker probe) — the
 * repo-browse and novel-browse rows moved to proper user-facing UIs under Browse:
 *   - Repo URL management: Browse → Extension repos → Light novels tab.
 *   - Plugin install: Browse → main bottom sheet → Extensions tab → Light novels sub-tab.
 *   - Source catalogs: Browse → main view → Light novel sources tab → tap a source.
 *
 * The remaining two entries stay because no proper-UI replacement exists yet (plugin host
 * probe is a development-only diagnostic; tracker probe is a placeholder until the novel
 * tracker UI ships).
 */
class SettingsLightNovelController : SettingsLegacyController() {

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = MR.strings.light_novels_beta

        preference {
            title = LnPluginHostProbeController.title
            onClick { router.pushController(LnPluginHostProbeController().withFadeTransaction()) }
        }
        preference {
            title = NovelTrackProbeController.title
            onClick { router.pushController(NovelTrackProbeController().withFadeTransaction()) }
        }
        this
    }
}
