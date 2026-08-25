package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.webview.TrackerLoginWebViewScreen
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Signs into a [CookieLoginTracker] by showing the service's own login page and watching for the
 * session cookie it sets. The tracker owns the extraction, so this screen never learns what any
 * particular service's cookies look like.
 *
 * Nothing is returned to the caller: the credential is saved here and every tracker row already
 * renders from `isLoggedInFlow`, so the settings screen updates on its own.
 */
class TrackerWebViewLoginActivity : BaseActivity() {

    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!WebViewUtil.supportsWebView(this)) {
            toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }

        val tracker = appGraph.trackerManager.get(intent.getLongExtra(TRACKER_ID, -1L))
        val cookieLogin = tracker as? CookieLoginTracker
        if (tracker == null || cookieLogin == null) {
            finish()
            return
        }

        setComposeContent {
            TrackerLoginWebViewScreen(
                title = stringResourceLoginTitle(tracker),
                url = cookieLogin.cookieLoginUrl,
                onUp = { finish() },
                onPageFinished = { tryCapture(tracker, cookieLogin) },
            )
        }
    }

    /**
     * Runs after every page load, because the cookie can appear on any of them: the sign-in POST,
     * the redirect that follows it, or immediately when the user was already signed in.
     */
    private fun tryCapture(tracker: Tracker, cookieLogin: CookieLoginTracker) {
        if (captured) return
        val cookies = CookieManager.getInstance().getCookie(cookieLogin.cookieDomain) ?: return
        val credential = cookieLogin.credentialFromCookies(cookies) ?: return

        captured = true
        lifecycleScope.launch {
            try {
                cookieLogin.loginWithCookie(credential)
                toast(MR.strings.login_success)
                finish()
            } catch (e: Throwable) {
                // The message is the service's, never the credential.
                logcat { "${tracker.name} cookie login rejected: ${e.message}" }
                tracker.logout()
                toast(e.message)
                captured = false
            }
        }
    }

    private fun stringResourceLoginTitle(tracker: Tracker): String =
        getString(MR.strings.login_title.resourceId, tracker.name)

    companion object {
        private const val TRACKER_ID = "tracker_id"

        fun newIntent(context: Context, trackerId: Long): Intent =
            Intent(context, TrackerWebViewLoginActivity::class.java).apply {
                putExtra(TRACKER_ID, trackerId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
