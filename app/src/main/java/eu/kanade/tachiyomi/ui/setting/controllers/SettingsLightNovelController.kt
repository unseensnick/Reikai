package eu.kanade.tachiyomi.ui.setting.controllers

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.controllers.debug.LnPluginHostProbeController
import eu.kanade.tachiyomi.ui.setting.controllers.debug.LnRepoBrowseController
import eu.kanade.tachiyomi.ui.novel.browse.NovelBrowseController
import eu.kanade.tachiyomi.ui.setting.controllers.debug.NovelTrackProbeController
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.i18n.MR
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

/**
 * Beta-only settings shelf for the WIP light-novel reader. Hosts the five LN debug screens
 * (probe, repo browse, browse, library, track probe) one tap deeper than they used to be from
 * the Debug menu so testers can reach them without drilling through Advanced. Will be removed
 * once the bottom-nav Novels tab covers everything and the debug screens stop earning rent.
 */
class SettingsLightNovelController : SettingsLegacyController() {

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = MR.strings.light_novels_beta

        preference {
            title = LnPluginHostProbeController.title
            onClick { router.pushController(LnPluginHostProbeController().withFadeTransaction()) }
        }
        preference {
            title = LnRepoBrowseController.title
            onClick { router.pushController(LnRepoBrowseController().withFadeTransaction()) }
        }
        preference {
            title = NovelBrowseController.title
            onClick { router.pushController(NovelBrowseController().withFadeTransaction()) }
        }
        preference {
            title = NovelTrackProbeController.title
            onClick { router.pushController(NovelTrackProbeController().withFadeTransaction()) }
        }
        this
    }
}
