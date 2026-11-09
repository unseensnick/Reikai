package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.preference.getAndSet

@Inject
class TrustExtension(
    // RK: the store repository went with changes(), see below
    private val preferences: SourcePreferences,
) {

    fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>, stores: List<ExtensionStore>): Boolean {
        val trustedFingerprints = stores.mapTo(HashSet()) { it.signingKey }
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return trustedFingerprints.any { fingerprints.contains(it) } || key in preferences.trustedExtensions.get()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions.getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions.delete()
    }

    // RK: changes() is ExtensionManager's trust check against the scan (reloadWhenScanStale), since its drop(1)
    // lost a store added while the first scan ran
}
