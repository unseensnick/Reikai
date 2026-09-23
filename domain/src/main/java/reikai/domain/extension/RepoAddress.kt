package reikai.domain.extension

import mihon.domain.extension.model.ExtensionStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The signing key of a store that publishes none, which matches no extension's signature. */
const val NO_SIGNING_KEY = "NO_SIGNING_KEY"

val ExtensionStore.hasSigningKey: Boolean get() = signingKey != NO_SIGNING_KEY

/**
 * A name and website for a repo that publishes neither: a GitHub raw address names its owner and
 * links the repo, anything else its host.
 */
fun repoNameFromAddress(url: String): Pair<String, String?> {
    val httpUrl = url.toHttpUrlOrNull() ?: return url to null
    val segments = httpUrl.pathSegments
    return if (httpUrl.host == "raw.githubusercontent.com" && segments.size >= 2) {
        segments[0] to "https://github.com/${segments[0]}/${segments[1]}"
    } else {
        httpUrl.host to "${httpUrl.scheme}://${httpUrl.host}"
    }
}
