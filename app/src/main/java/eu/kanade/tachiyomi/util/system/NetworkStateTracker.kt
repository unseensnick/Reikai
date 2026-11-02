package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.net.NetworkCapabilities

data class NetworkState(
    val isConnected: Boolean,
    val isValidated: Boolean,
    val isWifi: Boolean,
) {
    val isOnline = isConnected && isValidated
}

@Suppress("DEPRECATION")
fun Context.activeNetworkState(): NetworkState {
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    return NetworkState(
        isConnected = connectivityManager.activeNetworkInfo?.isConnected ?: false,
        isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false,
        isWifi = wifiManager.isWifiEnabled && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false,
    )
}
