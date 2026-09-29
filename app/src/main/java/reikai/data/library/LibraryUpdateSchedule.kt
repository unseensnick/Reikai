package reikai.data.library

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import java.util.concurrent.TimeUnit

// When and how the manga and novel library updates are scheduled, in Mihon's LibraryUpdateJob shape.
// Only the worker class, its tags and whose restriction preference is read differ between the two.

/**
 * Whether a scheduled update retries later instead of running: while a manual update runs, and below
 * Android 9 while a Wi-Fi-only update is off Wi-Fi, since the network request that enforces Wi-Fi
 * only applies from Android 9. [isOnWifi] is read only then, as upstream does: the Wi-Fi service can
 * be absent, and reading it throws.
 */
fun shouldDeferAutoUpdate(sdkInt: Int, restrictions: Set<String>, isOnWifi: () -> Boolean, isManualRunning: Boolean) =
    isManualRunning || (sdkInt < Build.VERSION_CODES.P && DEVICE_ONLY_ON_WIFI in restrictions && !isOnWifi())

/** [shouldDeferAutoUpdate] for this run; a manual run never defers. */
fun ListenableWorker.shouldDeferLibraryUpdate(restrictions: Set<String>, autoTag: String, manualTag: String) =
    autoTag in tags &&
        shouldDeferAutoUpdate(
            Build.VERSION.SDK_INT,
            restrictions,
            { applicationContext.isConnectedToWifi() },
            applicationContext.workManager.isRunning(manualTag),
        )

inline fun <reified W : ListenableWorker> libraryUpdatePeriodicRequest(
    intervalHours: Int,
    restrictions: Set<String>,
    tag: String,
    autoTag: String,
): PeriodicWorkRequest = PeriodicWorkRequestBuilder<W>(intervalHours.toLong(), TimeUnit.HOURS, 10, TimeUnit.MINUTES)
    .addTag(tag)
    .addTag(autoTag)
    .setConstraints(libraryUpdateConstraints(restrictions))
    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
    .build()

/** Tagged [manualTag] as well, which is what lets a scheduled run see it and wait. */
inline fun <reified W : ListenableWorker> libraryUpdateManualRequest(
    tag: String,
    manualTag: String,
    inputData: Data,
): OneTimeWorkRequest = OneTimeWorkRequestBuilder<W>()
    .addTag(tag)
    .addTag(manualTag)
    .setInputData(inputData)
    .build()

fun libraryUpdateConstraints(restrictions: Set<String>): Constraints {
    val networkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) NetworkType.UNMETERED else NetworkType.CONNECTED
    val networkRequest = NetworkRequest.Builder().apply {
        removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (DEVICE_ONLY_ON_WIFI in restrictions) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        if (DEVICE_NETWORK_NOT_METERED in restrictions) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
        .build()
    return Constraints.Builder()
        // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used
        .setRequiredNetworkRequest(networkRequest, networkType)
        .setRequiresCharging(DEVICE_CHARGING in restrictions)
        .setRequiresBatteryNotLow(true)
        .build()
}
