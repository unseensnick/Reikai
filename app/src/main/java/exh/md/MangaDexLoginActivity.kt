package exh.md

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.setting.track.BaseOAuthLoginActivity
import exh.md.utils.MdUtil
import kotlinx.coroutines.flow.first
import mihon.app.di.appGraph
import tachiyomi.core.common.util.lang.launchIO

class MangaDexLoginActivity : BaseOAuthLoginActivity() {

    // Not a member injection: the base's inject() covers only the members it declares itself.
    private val sourceManager get() = appGraph.sourceManager

    override fun handleResult(uri: Uri) {
        val code = uri.getQueryParameter("code")
        if (code != null) {
            lifecycleScope.launchIO {
                MdUtil.getEnabledMangaDex(appGraph.sourcePreferences, sourceManager)?.login(code)
                returnToSettings()
            }
        } else {
            lifecycleScope.launchIO {
                MdUtil.getEnabledMangaDex(appGraph.sourcePreferences, sourceManager)?.logout()
                returnToSettings()
            }
        }
    }
}
