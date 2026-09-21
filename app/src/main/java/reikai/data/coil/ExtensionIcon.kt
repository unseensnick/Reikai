package reikai.data.coil

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.extension.util.ExtensionLoader

private const val EXTENSION_ICON_SCHEME = "reikai-extension-icon"

/**
 * An address for an installed extension app's own icon. Source icons are addresses everywhere a novel
 * source is drawn, and an app's icon lives in its package rather than at a URL, so it gets one here.
 */
fun extensionIconUrl(pkgName: String): String = "$EXTENSION_ICON_SCHEME://$pkgName"

/** Draws the icon an [extensionIconUrl] names, from the app installed privately or on the system. */
class ExtensionIconFetcher(private val pkgName: String, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val context = options.context
        val icon = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)
            ?.applicationInfo
            ?.loadIcon(context.packageManager)
            ?: error("Extension $pkgName is not installed")
        return ImageFetchResult(icon.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.authority?.takeIf { data.scheme == EXTENSION_ICON_SCHEME }?.let { ExtensionIconFetcher(it, options) }
    }
}
