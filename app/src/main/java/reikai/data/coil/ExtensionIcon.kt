package reikai.data.coil

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.app.di.appGraph
import reikai.novel.source.ireader.IReaderSourceHolder

private const val EXTENSION_ICON_SCHEME = "reikai-extension-icon"

/**
 * An address for an installed extension app's own icon. Source icons are addresses everywhere a novel
 * source is drawn, and an app's icon lives in its package rather than at a URL, so it gets one here.
 */
fun extensionIconUrl(pkgName: String): String = "$EXTENSION_ICON_SCHEME://$pkgName"

/** Whether an icon has no visible pixel, which every IReader app's does: it is as good as none. */
fun Bitmap.isInvisible(): Boolean {
    val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    return pixels.all { it ushr 24 == 0 }
}

/**
 * Draws the icon an [extensionIconUrl] names, from the app installed privately or on the system. One that
 * shows nothing is replaced by an icon its store or another listing gives for its site, if any loads.
 */
class ExtensionIconFetcher(
    private val pkgName: String,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val context = options.context
        val icon = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)
            ?.applicationInfo
            ?.loadIcon(context.packageManager)
            ?: error("Extension $pkgName is not installed")
        if (!icon.toBitmap().isInvisible()) {
            return ImageFetchResult(icon.asImage(), isSampled = false, dataSource = DataSource.DISK)
        }
        return borrowedIcon() ?: error("Extension $pkgName has no icon to show")
    }

    private suspend fun borrowedIcon(): FetchResult? {
        val graph = options.context.appGraph
        val sites = graph.extensionManager.getLoadedNovelExtensions()
            .firstOrNull { it.pkgName == pkgName }
            ?.sources.orEmpty()
            .map { (it as? HttpSource)?.baseUrl ?: (it as? IReaderSourceHolder)?.baseUrl }
        for (url in graph.novelPreferences.novelIconHints().get().candidatesFor(pkgName, sites)) {
            val result = imageLoader.execute(ImageRequest.Builder(options.context).data(url).build())
            if (result is SuccessResult) {
                return ImageFetchResult(result.image, isSampled = false, dataSource = DataSource.NETWORK)
            }
        }
        return null
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.authority?.takeIf { data.scheme == EXTENSION_ICON_SCHEME }
                ?.let { ExtensionIconFetcher(it, options, imageLoader) }
    }
}
