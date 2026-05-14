package yokai.presentation.settings.screen

import android.app.Activity
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import co.touchlab.kermit.Logger
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.preference.PreferenceValues.SecureScreenMode
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.startAuthentication
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentList
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.settings.ComposableSettings

object SettingsSecurityScreen : ComposableSettings() {

    private const val LOG_TAG = "SettingsSecurityScreen"

    private fun readResolve() = SettingsSecurityScreen

    @Composable
    override fun getTitleRes(): StringResource = MR.strings.security

    @Composable
    override fun getPreferences(): List<Preference> {
        val securityPreferences: SecurityPreferences by injectLazy()
        val preferences: PreferencesHelper by injectLazy()
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            Logger.i(LOG_TAG) { "entered compose security settings" }
        }

        return buildList {
            if (context.isAuthenticationSupported()) {
                add(getBiometricSwitch(securityPreferences))
                add(getLockAfter(preferences))
            }
            add(getHideNotificationContent(preferences))
            add(getSecureScreen(preferences))
            add(
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(MR.strings.secure_screen_summary),
                ),
            )
        }.toPersistentList()
    }

    @Composable
    private fun getBiometricSwitch(securityPreferences: SecurityPreferences): Preference.PreferenceItem.SwitchPreference {
        val context = LocalContext.current
        val pref = securityPreferences.useBiometrics()
        val title = stringResource(MR.strings.lock_with_biometrics)

        return Preference.PreferenceItem.SwitchPreference(
            pref = pref,
            title = title,
            onValueChanged = { newValue ->
                Logger.i(LOG_TAG) { "useBiometrics → $newValue" }
                val activity = context as? FragmentActivity
                if (activity != null && context.isAuthenticationSupported()) {
                    activity.startAuthentication(
                        title = title,
                        confirmationRequired = false,
                        callback = object : AuthenticatorUtil.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                pref.set(newValue)
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                super.onAuthenticationError(errorCode, errString)
                                activity.toast(errString.toString())
                            }
                        },
                    )
                    false
                } else {
                    true
                }
            },
        )
    }

    @Composable
    private fun getLockAfter(preferences: PreferencesHelper): Preference.PreferenceItem.ListPreference<Int> {
        val values = listOf(0, 2, 5, 10, 20, 30, 60, 90, 120, -1)
        val entries = values.associateWith { value ->
            when (value) {
                0 -> stringResource(MR.strings.always)
                -1 -> stringResource(MR.strings.never)
                else -> pluralStringResource(MR.plurals.after_minutes, quantity = value, value)
            }
        }.toImmutableMap()

        return Preference.PreferenceItem.ListPreference(
            pref = preferences.lockAfter(),
            title = stringResource(MR.strings.lock_when_idle),
            entries = entries,
            onValueChanged = {
                Logger.i(LOG_TAG) { "lockAfter → $it" }
                true
            },
        )
    }

    @Composable
    private fun getHideNotificationContent(preferences: PreferencesHelper): Preference.PreferenceItem.SwitchPreference {
        return Preference.PreferenceItem.SwitchPreference(
            pref = preferences.hideNotificationContent(),
            title = stringResource(MR.strings.hide_notification_content),
            onValueChanged = {
                Logger.i(LOG_TAG) { "hideNotificationContent → $it" }
                true
            },
        )
    }

    @Composable
    private fun getSecureScreen(preferences: PreferencesHelper): Preference.PreferenceItem.ListPreference<SecureScreenMode> {
        val context = LocalContext.current
        val entries = SecureScreenMode.entries
            .associateWith { stringResource(it.titleResId) }
            .toImmutableMap()

        return Preference.PreferenceItem.ListPreference(
            pref = preferences.secureScreen(),
            title = stringResource(MR.strings.secure_screen),
            entries = entries,
            onValueChanged = {
                Logger.i(LOG_TAG) { "secureScreen → $it" }
                SecureActivityDelegate.setSecure(context as? Activity)
                true
            },
        )
    }
}
