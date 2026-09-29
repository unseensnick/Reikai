package eu.kanade.presentation.browse.components

import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.domain.source.model.icon
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import exh.assets.BuiltInSourceLogo
import exh.assets.builtInSourceLogo
import exh.assets.painter
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Dangerous
import mihon.icons.materialsymbols.rounded.Warning
import reikai.data.coil.extensionIconUrl
import reikai.data.coil.isInvisible
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.isLocal

private val defaultModifier = Modifier
    .height(40.dp)
    .aspectRatio(1f)

@Composable
fun SourceIcon(
    source: Source,
    modifier: Modifier = Modifier,
) {
    val icon = produceState<ImageBitmap?>(initialValue = null, source.id) { value = source.icon() }.value
    val builtInLogo = builtInSourceLogo(source.id) // RK

    when {
        source.isStub && icon == null -> {
            Image(
                imageVector = MaterialSymbols.Rounded.Warning,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
                modifier = modifier.then(defaultModifier),
            )
        }
        icon != null -> {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
        // RK -->
        // Built-in adult sources ship no extension icon, so draw their bundled logo. The 5dp inset
        // matches the transparent safe-zone margin baked into extension launcher icons, so the tile
        // reads the same size as its neighbours instead of full-bleed.
        builtInLogo != null -> {
            val tile = modifier
                .then(defaultModifier)
                .padding(5.dp)
                .clip(RoundedCornerShape(2.dp))
            if (builtInLogo.isTiled) {
                Box(modifier = tile.background(Color.White), contentAlignment = Alignment.Center) {
                    Image(
                        painter = builtInLogo.painter(),
                        contentDescription = null,
                        // The EH mark is a bare vector with no margin of its own.
                        modifier = Modifier.fillMaxSize(if (builtInLogo == BuiltInSourceLogo.EHENTAI) 0.72f else 1f),
                    )
                }
            } else {
                Image(painter = builtInLogo.painter(), contentDescription = null, modifier = tile)
            }
        }
        // RK <--
        source.isLocal() -> {
            Image(
                painter = painterResource(R.mipmap.ic_local_source),
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
        else -> {
            Image(
                painter = painterResource(R.mipmap.ic_default_source),
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
    }
}

@Composable
fun ExtensionIcon(
    extension: Extension,
    modifier: Modifier = Modifier,
    density: Int = DisplayMetrics.DENSITY_DEFAULT,
) {
    when (extension) {
        is Extension.Available -> {
            AsyncImage(
                model = extension.iconUrl,
                contentDescription = null,
                placeholder = ColorPainter(Color(0x1F888888)),
                // RK: the installed rows' fallback, since many stores publish no icon for some extensions
                error = painterResource(R.mipmap.ic_default_source),
                modifier = modifier
                    .clip(MaterialTheme.shapes.extraSmall),
            )
        }
        is Extension.Loaded -> {
            val icon by extension.getIcon(density)
            when (icon) {
                Result.Loading -> Box(modifier = modifier)
                is Result.Success -> Image(
                    bitmap = (icon as Result.Success<ImageBitmap>).value,
                    contentDescription = null,
                    modifier = modifier,
                )
                // RK: an app whose icon shows nothing may borrow one through the novel source icon's fetcher
                Result.Error -> AsyncImage(
                    model = extensionIconUrl(extension.pkgName),
                    contentDescription = null,
                    error = painterResource(R.mipmap.ic_default_source),
                    modifier = modifier,
                )
            }
        }
        is Extension.NotLoaded -> Image(
            imageVector = MaterialSymbols.Rounded.Dangerous,
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
            modifier = modifier.then(defaultModifier),
        )
    }
}

@Composable
private fun Extension.getIcon(density: Int = DisplayMetrics.DENSITY_DEFAULT): State<Result<ImageBitmap>> {
    val context = LocalContext.current
    return produceState<Result<ImageBitmap>>(initialValue = Result.Loading, this) {
        withIOContext {
            value = try {
                val appInfo = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)!!.applicationInfo!!
                val appResources = context.packageManager.getResourcesForApplication(appInfo)
                // RK: an icon with no visible pixel is as good as none
                val bitmap = appResources.getDrawableForDensity(appInfo.icon, density, null)!!.toBitmap()
                if (bitmap.isInvisible()) Result.Error else Result.Success(bitmap.asImageBitmap())
            } catch (e: Exception) {
                Result.Error
            }
        }
    }
}

sealed class Result<out T> {
    data object Loading : Result<Nothing>()
    data object Error : Result<Nothing>()
    data class Success<out T>(val value: T) : Result<T>()
}
