package eu.kanade.tachiyomi.ui.setting.controllers.debug

import android.os.Build
import androidx.preference.PreferenceScreen
import androidx.webkit.WebViewCompat
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import java.text.DateFormat
import yokai.i18n.MR
import yokai.presentation.settings.screen.about.getFormattedBuildTime

class DebugController : SettingsLegacyController() {

    override fun getTitleRes() = MR.strings.pref_debug_info

    private val dateFormat: DateFormat by lazy {
        preferences.dateFormat()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        preference {
            title = WorkerInfoController.title
            onClick {
                router.pushController(WorkerInfoController().withFadeTransaction())
            }
        }
        preference {
            title = BackupSchemaController.title
            onClick {
                router.pushController(BackupSchemaController().withFadeTransaction())
            }
        }
        preference {
            title = LnPluginHostProbeController.title
            onClick {
                router.pushController(LnPluginHostProbeController().withFadeTransaction())
            }
        }
        preference {
            title = LnRepoBrowseController.title
            onClick {
                router.pushController(LnRepoBrowseController().withFadeTransaction())
            }
        }
        preference {
            title = NovelBrowseController.title
            onClick {
                router.pushController(NovelBrowseController().withFadeTransaction())
            }
        }
        preference {
            title = NovelLibraryController.title
            onClick {
                router.pushController(NovelLibraryController().withFadeTransaction())
            }
        }
        preferenceCategory {
            title = "App Info"
            preference {
                key = "pref_version"
                title = "Version"
                summary = if (BuildConfig.DEBUG || BuildConfig.BETA) {
                    "r" + BuildConfig.COMMIT_COUNT
                } else {
                    BuildConfig.VERSION_NAME
                }
            }
            preference {
                key = "pref_build_time"
                title = "Build Time"
                summary = getFormattedBuildTime(dateFormat)
            }
            preference {
                key = "pref_webview_version"
                title = "WebView version"
                summary = getWebViewVersion()
            }
        }

        preferenceCategory {
            title = "Device info"
            preference {
                title = "Model"
                summary = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
            }
            if (DeviceUtil.oneUiVersion != null) {
                preference {
                    title = "OneUI version"
                    summary = "${DeviceUtil.oneUiVersion}"
                }
            } else if (DeviceUtil.miuiMajorVersion != null) {
                preference {
                    title = "MIUI version"
                    summary = "${DeviceUtil.miuiMajorVersion}"
                }
            }
            val androidVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Build.VERSION.RELEASE_OR_PREVIEW_DISPLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Build.VERSION.RELEASE_OR_CODENAME
            } else {
                Build.VERSION.RELEASE
            }
            preference {
                title = "Android version"
                summary = "$androidVersion (${Build.DISPLAY})"
            }
        }
    }

    private fun getWebViewVersion(): String {
        val activity = activity ?: return "Unknown"
        val webView =
            WebViewCompat.getCurrentWebViewPackage(activity) ?: return "how did you get here?"
        val label = webView.applicationInfo!!.loadLabel(activity.packageManager)
        val version = webView.versionName
        return "$label $version"
    }
}
