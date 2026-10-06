package reikai.domain.download

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.NetworkState
import tachiyomi.i18n.MR

/**
 * Why both downloaders pause on [state], or null when they may fetch. Offline is reported before
 * Wi-Fi only, in upstream's order, since an offline device is off Wi-Fi too.
 */
fun downloadNetworkIssue(state: NetworkState, wifiOnly: Boolean): StringResource? = when {
    !state.isOnline -> MR.strings.download_notifier_no_network
    wifiOnly && !state.isWifi -> MR.strings.download_notifier_text_only_wifi
    else -> null
}
