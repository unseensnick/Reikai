package mihon.telemetry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object TelemetryConfig {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        // To stop forks/test builds from polluting our data
        if (!context.isReikaiProductionApp()) return

        // Check if Google Play Services is available before initializing Firebase
        if (!isGooglePlayServicesAvailable(context)) {
            logcat(LogPriority.WARN) { "Google Play Services not available, skipping Firebase initialization" }
            return
        }

        try {
            analytics = FirebaseAnalytics.getInstance(context)
            analytics?.setUserProperty("preferred_abi", Build.SUPPORTED_ABIS[0])
            FirebaseApp.initializeApp(context)
            crashlytics = FirebaseCrashlytics.getInstance()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize Firebase" }
        }
    }

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return try {
            context.packageManager
                .getPackageInfo("com.google.android.gms", PackageManager.GET_META_DATA)
                .applicationInfo
                ?.enabled == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    // RK --> Reikai's own packages and signing certificate, replacing Mihon's.
    private fun Context.isReikaiProductionApp(): Boolean {
        if (packageName !in REIKAI_PACKAGES) return false

        return packageManager.getPackageInfo(packageName, SignatureFlags)
            .getCertificateFingerprints()
            .any { it == REIKAI_CERTIFICATE_FINGERPRINT }
    }
}

// The stable and preview packages only: the local dev build is deliberately absent, so it never
// reports even when signed with the release key, which a local signing config makes the default.
// Matched exactly, so a package rename silently stops all reporting until this list moves with it.
private val REIKAI_PACKAGES = hashSetOf("app.reikai", "app.reikai.debug")
private const val REIKAI_CERTIFICATE_FINGERPRINT =
    "D0:E2:7C:7C:43:A6:BE:B1:66:BB:18:83:19:EE:4A:03:4F:7B:F0:A3:9B:CC:03:EC:E6:49:5C:E0:8F:5D:D6:EC"
// RK <--
